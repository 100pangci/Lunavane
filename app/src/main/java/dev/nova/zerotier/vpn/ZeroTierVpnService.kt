package dev.nova.zerotier.vpn

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.VpnService
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.ParcelFileDescriptor
import androidx.core.app.NotificationCompat
import dev.nova.zerotier.DiagnosticLog
import dev.nova.zerotier.MainActivity
import dev.nova.zerotier.MoonSeed
import dev.nova.zerotier.MoonStore
import dev.nova.zerotier.NodeState
import dev.nova.zerotier.TunnelState
import dev.nova.zerotier.TunnelUiState
import dev.nova.zerotier.VirtualNetworkState
import dev.nova.zerotier.core.NativeCallbacks
import dev.nova.zerotier.core.NativeCore
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.util.ArrayDeque
import java.util.ConcurrentModificationException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.CancelledKeyException
import java.nio.channels.ClosedSelectorException
import java.nio.channels.DatagramChannel
import java.nio.channels.SelectionKey
import java.nio.channels.Selector
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors
import java.util.concurrent.FutureTask
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlin.concurrent.thread

class ZeroTierVpnService : VpnService() {
    companion object {
        const val ACTION_CONNECT = "dev.nova.zerotier.CONNECT"
        const val ACTION_DISCONNECT = "dev.nova.zerotier.DISCONNECT"
        const val ACTION_ORBIT = "dev.nova.zerotier.ORBIT"
        const val ACTION_DEORBIT = "dev.nova.zerotier.DEORBIT"
        const val EXTRA_NETWORK_ID = "network_id"
        const val EXTRA_MOON_WORLD_ID = "moon_world_id"
        const val EXTRA_MOON_SEED = "moon_seed"
        private const val CHANNEL_ID = "zerotier_tunnel"
        private const val NOTIFICATION_ID = 7
        private const val PORT = 9993
        private const val ARP_ETHER_TYPE = 0x0806
        private const val IPV4_ETHER_TYPE = 0x0800
        private const val IPV6_ETHER_TYPE = 0x86dd
        private const val BROADCAST_MAC = 0xffffffffffffL
        private const val IPV4_MULTICAST_BASE = 0x01005e000000L
        private const val IPV6_MULTICAST_BASE = 0x333300000000L
        private const val MAX_DATAGRAM = 65507
        private const val MAX_PENDING_PACKETS = 256
        private const val MAX_PENDING_BYTES = 2 * 1024 * 1024
        private const val MAX_PACKET_WORK_ITEMS = 1024
        private const val MAX_PACKET_WORK_BYTES = 8L * 1024 * 1024
        private const val PEER_REFRESH_MILLIS = 5_000L
        private const val NETWORK_SWITCH_DEBOUNCE_MILLIS = 300L
        private const val DEFAULT_ROUTE_REBIND_COOLDOWN_NANOS = 5_000_000_000L
        private val RECOVERABLE_WIRE_FAILURES = setOf(
            WirePacketFailureCategory.NETWORK_UNREACHABLE,
            WirePacketFailureCategory.SOCKET_CLOSED,
            WirePacketFailureCategory.SOCKET_ERROR,
            WirePacketFailureCategory.IO_ERROR,
        )
    }

    private data class UnderlayCandidate(
        val network: Network,
        val descriptor: NetworkCandidateDescriptor,
        val linkFingerprint: String,
    ) {
        val recoveryKey: String get() =
            "${descriptor.key}|${descriptor.transport}|${descriptor.hasInternet}|${descriptor.validated}|$linkFingerprint"
    }

    private class NetworkObservation {
        var capabilities: NetworkCapabilities? = null
        var linkProperties: LinkProperties? = null
    }

    private enum class DefaultRouteFallbackOutcome {
        INSTALLED,
        OBSOLETE,
        FAILED,
    }

    private class PreparedUdpSocket(
        val channel: DatagramChannel,
        val socket: DatagramSocket,
        val boundToNetwork: Boolean,
    ) {
        var selector: Selector? = null
    }

    private val stopping = AtomicBoolean(false)
    private val networkSwitchRestartPending = AtomicBoolean(false)
    private val networkSwitchDebouncePending = AtomicBoolean(false)
    private val defaultRouteFallbackPending = AtomicBoolean(false)
    private val sessionGeneration = AtomicLong(0)
    private val lifecycleLock = Any()
    private val mainHandler = Handler(Looper.getMainLooper())
    private val networkConfigExecutor = Executors.newSingleThreadExecutor()
    private val tunOutputLock = Any()
    private val udpSocketLock = Any()
    private val sessionBoundNetworks = mutableSetOf<Network>()
    private val networkCallbackLock = Any()
    private val networkCandidateLock = Any()
    private val networkSwitchDebounceLock = Any()
    private val networkSwitchStateLock = Any()
    private var hasObservedCandidateKey = false
    private var lastObservedCandidateKey: String? = null
    private val coreExecutor = Executors.newSingleThreadExecutor()
    private val packetExecutor = Executors.newSingleThreadExecutor()
    private val recoveryExecutor = Executors.newSingleThreadScheduledExecutor()
    private val networkAttemptExecutor = Executors.newFixedThreadPool(2)
    private val packetWorkBudget = PacketWorkBudget(MAX_PACKET_WORK_ITEMS, MAX_PACKET_WORK_BYTES)
    private val networkAvailableOrder = AtomicLong()
    private val networkCandidates = mutableMapOf<Network, UnderlayCandidate>()
    private val networkObservations = mutableMapOf<Network, NetworkObservation>()
    private val arpTable = ConcurrentHashMap<Long, Long>()
    private val pendingIpv4 = mutableMapOf<Long, ArrayDeque<ByteArray>>()
    private var pendingIpv4Count = 0
    private var pendingIpv4Bytes = 0
    private val neighborTable = ConcurrentHashMap<String, Long>()
    private val pendingIpv6 = mutableMapOf<String, ArrayDeque<ByteArray>>()
    private var pendingIpv6Count = 0
    private var pendingIpv6Bytes = 0
    private val lastNeighborSolicitation = ConcurrentHashMap<String, Long>()
    private val multicastSubscribed = ConcurrentHashMap.newKeySet<Long>()
    private val packetDiagnosticKeys = ConcurrentHashMap.newKeySet<String>()
    @Volatile private var networkIdText = ""
    @Volatile private var networkId = 0L
    private val coreHandle = CoreHandleSlot()
    @Volatile private var tun: ParcelFileDescriptor? = null
    @Volatile private var tunInput: FileInputStream? = null
    @Volatile private var tunOutput: FileOutputStream? = null
    @Volatile private var udp: DatagramSocket? = null
    @Volatile private var udpChannel: DatagramChannel? = null
    @Volatile private var udpSelector: Selector? = null
    @Volatile private var selectedNetwork: Network? = null
    @Volatile private var socketNetwork: Network? = null
    @Volatile private var socketBoundToNetwork = false
    @Volatile private var lastBindFallbackNanos = 0L
    @Volatile private var lastBoundNetwork: Network? = null
    @Volatile private var activeUnderlayWasLost = false
    @Volatile private var lastWireFailureCategory: String? = null
    @Volatile private var config: NetworkConfig? = null
    @Volatile private var tunReader: Thread? = null
    @Volatile private var udpReader: Thread? = null
    @Volatile private var networkCallback: ConnectivityManager.NetworkCallback? = null
    @Volatile private var networkRecovery: NetworkRecoveryManager<UnderlayCandidate>? = null
    private var pendingNetworkSwitchRunnable: Runnable? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_DISCONNECT -> stopTunnel()
            ACTION_ORBIT -> requestOrbit(
                intent.getStringExtra(EXTRA_MOON_WORLD_ID).orEmpty(),
                intent.getStringExtra(EXTRA_MOON_SEED).orEmpty(),
                startId,
            )
            ACTION_DEORBIT -> requestDeorbit(intent.getStringExtra(EXTRA_MOON_WORLD_ID).orEmpty(), startId)
            ACTION_CONNECT -> {
                val requested = intent.getStringExtra(EXTRA_NETWORK_ID).orEmpty()
                if (requested.length != 16 || requested.any { it.digitToIntOrNull(16) == null }) {
                    stopSelf(startId)
                    return START_NOT_STICKY
                }
                startForeground(NOTIFICATION_ID, notification("Starting ZeroTier node"))
                stopTunnel(keepForeground = true)
                networkIdText = requested.lowercase()
                networkId = networkIdText.toULong(16).toLong()
                synchronized(lifecycleLock) { stopping.set(false) }
                publish("Starting", "Initializing ZeroTier node") {
                    it.copy(tunnelActive = true, nodeState = NodeState.STARTING, networkState = VirtualNetworkState.IDLE)
                }
                try {
                    startTunnel()
                } catch (exception: Exception) {
                    publish("Failed", exception.message ?: "Unable to start the tunnel")
                    stopTunnel()
                }
            }
        }
        return START_NOT_STICKY
    }

    private fun startTunnel() {
        val session = synchronized(lifecycleLock) { sessionGeneration.get() }
        executeCoreTask {
            try {
                val callbacks = SessionCallbacks(session)
                val handle = NativeCore.create(callbacks, filesDir.resolve("zerotier").absolutePath)
                if (handle == 0L) error("ZeroTier core initialization failed")
                callbacks.attachHandle(handle)
                val installed = synchronized(lifecycleLock) {
                    if (!isCurrentSession(session)) {
                        false
                    } else {
                        coreHandle.set(handle)
                        true
                    }
                }
                if (!installed) {
                    NativeCore.destroy(handle)
                    return@executeCoreTask
                }
                val nodeAddress = runCatching { NativeCore.nodeAddress(handle) }.getOrNull()
                TunnelState.update { current ->
                    if (isCurrentCore(session, handle)) current.copy(
                        nodeReady = true,
                        nodeState = NodeState.READY,
                        nodeAddress = nodeAddress ?: current.nodeAddress,
                    ) else current
                }
                restoreMoonSeeds(handle, session)
                observeNetworks(session, handle)
                if (!isCurrentCore(session, handle)) return@executeCoreTask
                val joinResult = NativeCore.join(handle, networkIdText)
                TunnelState.update { current ->
                    if (isCurrentCore(session, handle)) current.copy(
                        networkState = if (joinResult == 0) VirtualNetworkState.JOINING else VirtualNetworkState.JOIN_FAILED,
                    ) else current
                }
                publishForCoreIf(
                    session,
                    handle,
                    "Requesting configuration",
                    "Joining ZeroTier network",
                    shouldPublish = { it.status == "Starting" },
                )
                schedulePeerRefresh(session, handle)
            } catch (exception: Exception) {
                if (isCurrentSession(session)) {
                    publishForSession(
                        session,
                        null,
                        "Failed",
                        exception.cause?.message ?: exception.message ?: "Unable to start the tunnel",
                    ) { it.copy(nodeState = NodeState.FAILED) }
                    stopTunnel(expectedSession = session)
                }
            }
        }
    }

    private fun isCurrentSession(session: Long): Boolean =
        !stopping.get() && sessionGeneration.get() == session

    private fun isCurrentCore(session: Long, handle: Long): Boolean =
        isCurrentCoreSession(session, handle, sessionGeneration.get(), coreHandle.get(), stopping.get())

    private fun executeCoreTask(action: () -> Unit): Boolean = try {
        coreExecutor.execute(action)
        true
    } catch (_: RejectedExecutionException) {
        false
    }

    private fun executePacketTask(action: () -> Unit): Boolean = try {
        packetExecutor.execute(action)
        true
    } catch (_: RejectedExecutionException) {
        false
    }

    private fun restoreMoonSeeds(handle: Long, session: Long) {
        if (!isCurrentSession(session)) return
        val entries = MoonStore.read(this)
        updateStateForCore(session, handle) { it.copy(moonSeeds = entries, moonMessage = "") }
        if (entries.isEmpty()) return
        var failed = 0
        for (entry in entries) {
            if (!isCurrentSession(session)) return
            if (NativeCore.orbit(handle, entry.worldId, entry.seed) != 0) failed++
        }
        if (!isCurrentSession(session)) return
        val message = if (failed == 0) {
            "Restored ${entries.size} saved Moon orbit request(s); Moon definition status is pending confirmation."
        } else {
            "Restored ${entries.size - failed} Moon request(s); $failed could not be submitted. Moon definition status is pending confirmation."
        }
        publishMoonStatusForCore(session, handle, message, entries)
    }

    private fun requestOrbit(moonWorldId: String, moonSeed: String, startId: Int) {
        val session = sessionGeneration.get()
        val handle = coreHandle.get()
        if (!isCurrentCore(session, handle)) {
            publishMoonStatus("Connect the ZeroTier node before orbiting a Moon.")
            if (handle == 0L) stopSelf(startId)
            return
        }
        if (!isValidHex(moonWorldId, 16) || !isValidHex(moonSeed, 10) ||
            moonWorldId.toULong(16) == 0uL || moonSeed.toULong(16) == 0uL
        ) {
            publishMoonStatus("Enter a 16-digit Moon ID and a non-zero 10-digit seed node address.")
            return
        }
        val entry = MoonSeed(moonWorldId.lowercase(), moonSeed.lowercase())
        executeCoreTask {
            if (!isCurrentCore(session, handle)) return@executeCoreTask
            val result = NativeCore.orbit(handle, entry.worldId, entry.seed)
            if (result == 0) {
                val stored = MoonStore.upsert(this, entry)
                if (isCurrentCore(session, handle)) {
                    val message = if (stored.persisted) {
                        "Orbit request accepted by the Core and saved; Moon definition status is pending confirmation."
                    } else {
                        "Core accepted the orbit request, but saving it locally failed; it will be lost after restart."
                    }
                    publishMoonStatusForCore(session, handle, message, stored.entries)
                }
            } else if (isCurrentCore(session, handle)) {
                publishMoonStatusForCore(session, handle, "Core could not submit the Moon orbit request (code $result).")
            }
        }
    }

    private fun requestDeorbit(moonWorldId: String, startId: Int) {
        val session = sessionGeneration.get()
        val handle = coreHandle.get()
        if (!isCurrentCore(session, handle)) {
            publishMoonStatus("Connect the ZeroTier node before deorbiting a Moon.")
            if (handle == 0L) stopSelf(startId)
            return
        }
        if (!isValidHex(moonWorldId, 16) || moonWorldId.toULong(16) == 0uL) {
            publishMoonStatus("Enter a valid 16-digit Moon ID.")
            return
        }
        val worldId = moonWorldId.lowercase()
        executeCoreTask {
            if (!isCurrentCore(session, handle)) return@executeCoreTask
            val result = NativeCore.deorbit(handle, worldId)
            if (result == 0) {
                val fileStatus = runCatching { NativeCore.moonFileStatus(handle, worldId) }.getOrDefault(-1)
                val stored = if (fileStatus == 0) MoonStore.remove(this, worldId) else null
                if (isCurrentCore(session, handle)) {
                    if (stored == null) {
                        val message = if (fileStatus > 0) {
                            "Core accepted deorbit, but the Moon file is still present; the saved record was kept."
                        } else {
                            "Core accepted deorbit, but the Moon file status could not be checked; the saved record was kept."
                        }
                        publishMoonStatusForCore(session, handle, message, MoonStore.read(this))
                    } else {
                        val message = if (stored.persisted) {
                            "Deorbit accepted; the Core Moon file is gone and the saved record was removed."
                        } else {
                            "Deorbit accepted and the Core Moon file is gone, but removing the saved record failed."
                        }
                        publishMoonStatusForCore(session, handle, message, stored.entries)
                    }
                }
            } else if (isCurrentCore(session, handle)) {
                publishMoonStatusForCore(session, handle, "Core could not submit the Moon deorbit request (code $result).")
            }
        }
    }

    private fun isValidHex(value: String, length: Int): Boolean =
        value.length == length && value.all { it.digitToIntOrNull(16) != null }

    private fun publishMoonStatus(message: String, entries: List<MoonSeed> = MoonStore.read(this)) {
        synchronized(lifecycleLock) {
            TunnelState.update { it.copy(moonSeeds = entries, moonMessage = message) }
            DiagnosticLog.append("Moon: $message")
        }
    }

    private fun publishMoonStatusForCore(
        session: Long,
        handle: Long,
        message: String,
        entries: List<MoonSeed> = MoonStore.read(this),
    ) {
        synchronized(lifecycleLock) {
            if (!isCurrentCore(session, handle)) return
            TunnelState.update { current ->
                if (isCurrentCore(session, handle)) current.copy(moonSeeds = entries, moonMessage = message) else current
            }
            DiagnosticLog.append("Moon: $message")
        }
    }

    private inner class SessionCallbacks(private val session: Long) : NativeCallbacks {
        @Volatile private var handle = 0L

        fun attachHandle(handle: Long) {
            this.handle = handle
        }

        private fun isCurrent(): Boolean = isCurrentCore(session, handle)

        override fun onWirePacket(address: ByteArray, port: Int, packet: ByteArray): Boolean =
            this@ZeroTierVpnService.sendWirePacket(session, handle, address, port, packet)

        override fun onVirtualFrame(
            networkId: Long,
            sourceMac: Long,
            destinationMac: Long,
            etherType: Int,
            vlanId: Int,
            payload: ByteArray,
        ) {
            if (isCurrent()) {
                this@ZeroTierVpnService.handleVirtualFrame(
                    session,
                    handle,
                    networkId,
                    sourceMac,
                    destinationMac,
                    etherType,
                    vlanId,
                    payload,
                )
            }
        }

        override fun onNetworkConfig(networkId: Long, operation: Int, status: Int, configJson: String): Int {
            if (!isCurrent()) return 0
            val parsedConfig = if (operation == 3 || operation == 4) {
                null
            } else {
                try {
                    NetworkConfig.parse(configJson)
                } catch (_: Exception) {
                    executePacketTask {
                        publishForCore(session, handle, "Configuration error", "Invalid network configuration")
                    }
                    return 22
                }
            }
            // Core consumes this return value as the virtual port configuration result.
            // Run Binder/TUN work elsewhere, then return the completed result to Core.
            val task = FutureTask<Int> {
                applyNetworkConfig(session, handle, networkId, operation, status, parsedConfig)
            }
            return try {
                networkConfigExecutor.execute(task)
                task.get()
            } catch (_: RejectedExecutionException) {
                5
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
                5
            } catch (exception: ExecutionException) {
                DiagnosticLog.append("Network config callback failed: ${exception.cause?.message ?: exception.message}")
                22
            }
        }

        override fun onNodeEvent(eventType: Int, message: String) {
            if (!isCurrent()) return
            // Publishing a status also updates the notification, which is a blocking
            // Binder call, so keep it off the Core native worker.
            executeCoreTask {
                handleNodeEvent(session, handle, eventType, message)
            }
        }
    }

    private fun openUdpSocket(
        network: Network,
        candidateKey: String,
        session: Long,
        handle: Long,
        recovery: NetworkRecoveryManager<UnderlayCandidate>,
        revision: Long,
    ): Boolean {
        val alreadyReady = recovery.runIfCurrentRevision(revision) {
            synchronized(networkCandidateLock) {
                if (networkCandidates[network]?.recoveryKey != candidateKey) {
                    false
                } else {
                    synchronized(lifecycleLock) {
                        if (!isCurrentCore(session, handle) || networkRecovery !== recovery || networkSwitchRestartPending.get() || networkSwitchDebouncePending.get()) {
                            false
                        } else {
                            synchronized(udpSocketLock) {
                                val ready = udpSocketCoversNetworkLocked(network)
                                if (ready) {
                                    selectedNetwork = network
                                }
                                ready
                            }
                        }
                    }
                }
            }
        }
        if (alreadyReady) {
            updateUnderlyingNetworksForCore(session, handle, arrayOf(network))
            return true
        }

        val prepared = try {
            prepareUdpSocket(network)
        } catch (exception: Exception) {
            if (isCurrentCore(session, handle)) {
                publishForCore(session, handle, "Degraded", "Could not prepare ZeroTier UDP socket: ${exception.message}")
            }
            return false
        }
        var installedPrepared = false
        val accepted = recovery.runIfCurrentRevision(revision) {
            synchronized(networkCandidateLock) {
                if (networkCandidates[network]?.recoveryKey != candidateKey) {
                    false
                } else {
                    synchronized(lifecycleLock) {
                        if (!isCurrentCore(session, handle) || networkRecovery !== recovery || networkSwitchRestartPending.get() || networkSwitchDebouncePending.get()) {
                            false
                        } else {
                            synchronized(udpSocketLock) {
                                if (udpSocketCoversNetworkLocked(network)) {
                                    selectedNetwork = network
                                    true
                                } else {
                                    val oldNetwork = socketNetwork
                                    closeUdpSocketLocked()
                                    try {
                                        bindPreparedUdpSocket(prepared)
                                        installPreparedUdpSocketLocked(prepared, network, session, handle)
                                        installedPrepared = true
                                        true
                                    } catch (exception: Exception) {
                                        closePreparedUdpSocket(prepared)
                                        val restored = oldNetwork?.let { restoreUdpSocketLocked(it, session, handle) } ?: false
                                        if (!restored) selectedNetwork = null
                                        publishForCore(
                                            session,
                                            handle,
                                            "Degraded",
                                            "Could not bind ZeroTier UDP socket: ${exception.message}",
                                        )
                                        false
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
        if (!installedPrepared) closePreparedUdpSocket(prepared)
        if (!accepted) return false
        updateUnderlyingNetworksForCore(session, handle, arrayOf(network))
        if (installedPrepared) {
            if (prepared.boundToNetwork) {
                DiagnosticLog.append("Protected ZeroTier UDP socket bound to ${network}")
            } else {
                DiagnosticLog.append("Protected ZeroTier UDP socket is using the default physical route")
            }
        }
        return true
    }

    private fun udpSocketCoversNetworkLocked(network: Network): Boolean {
        val socket = udp
        val channel = udpChannel
        if (socket == null || channel == null || socketNetwork != network) return false
        if (socket.isClosed || !channel.isOpen) return false
        if (socketBoundToNetwork) return true
        return !shouldRetryNetworkBinding(
            socketBoundToNetwork = false,
            lastBindFallbackNanos = lastBindFallbackNanos,
            nowNanos = System.nanoTime(),
            cooldownNanos = DEFAULT_ROUTE_REBIND_COOLDOWN_NANOS,
        )
    }

    private fun prepareUdpSocket(network: Network?): PreparedUdpSocket {
        val channel = DatagramChannel.open()
        try {
            channel.configureBlocking(false)
            val socket = channel.socket()
            socket.reuseAddress = true
            check(protect(socket)) { "Could not protect the ZeroTier UDP socket" }
            var boundToNetwork = false
            if (network != null) {
                try {
                    network.bindSocket(socket)
                    boundToNetwork = true
                } catch (exception: IOException) {
                    DiagnosticLog.append(
                        "Could not bind UDP socket to $network; using its protected default route: ${exception.message}",
                    )
                }
            }
            return PreparedUdpSocket(channel, socket, boundToNetwork)
        } catch (exception: Exception) {
            runCatching { channel.close() }
            throw exception
        }
    }

    private fun bindPreparedUdpSocket(prepared: PreparedUdpSocket) {
        prepared.socket.bind(InetSocketAddress(PORT))
        val selector = Selector.open()
        prepared.selector = selector
        prepared.channel.register(selector, SelectionKey.OP_READ)
    }

    private fun installPreparedUdpSocketLocked(
        prepared: PreparedUdpSocket,
        network: Network,
        session: Long,
        handle: Long,
    ) {
        val channel = prepared.channel
        val selector = checkNotNull(prepared.selector)
        udp = prepared.socket
        udpChannel = channel
        udpSelector = selector
        socketNetwork = network
        selectedNetwork = network
        socketBoundToNetwork = prepared.boundToNetwork
        lastBindFallbackNanos = if (prepared.boundToNetwork) 0L else System.nanoTime()
        lastBoundNetwork = network
        sessionBoundNetworks.add(network)
        udpReader = thread(name = "ZeroTier-udp-$session") {
            val bytes = ByteArray(MAX_DATAGRAM)
            val buffer = ByteBuffer.wrap(bytes)
            while (isCurrentCore(session, handle) && udpChannel === channel && channel.isOpen) {
                try {
                    selector.select()
                    val keys = selector.selectedKeys().iterator()
                    while (keys.hasNext()) {
                        val key = keys.next()
                        keys.remove()
                        if (!key.isValid || !key.isReadable) continue
                        buffer.clear()
                        val remote = channel.receive(buffer) as? InetSocketAddress ?: continue
                        val length = buffer.position()
                        if (length == 0) continue
                        val address = remote.address.address.copyOf()
                        val payload = bytes.copyOf(length)
                        val port = remote.port
                        if (!enqueuePacketWork(session, handle, length) {
                                if (isCurrentCore(session, handle)) {
                                    NativeCore.processWirePacket(handle, address, port, payload)
                                }
                            }
                        ) {
                            logPacketOnce("packet-work-budget", "Dropped packet work because the bounded packet queue is full")
                        }
                    }
                } catch (exception: IOException) {
                    if (isCurrentCore(session, handle) && udpChannel === channel) {
                        publishForCore(session, handle, "Degraded", "UDP receive failed: ${exception.message}")
                        invalidateUdpSocket(session, handle, channel)
                        notifyTransportFailure(session, handle)
                    }
                    break
                } catch (_: ClosedSelectorException) {
                    break
                } catch (_: CancelledKeyException) {
                    break
                } catch (_: ConcurrentModificationException) {
                    break
                }
            }
        }
    }

    private fun restoreUdpSocketLocked(network: Network, session: Long, handle: Long): Boolean = try {
        val restored = prepareUdpSocket(network)
        try {
            bindPreparedUdpSocket(restored)
            installPreparedUdpSocketLocked(restored, network, session, handle)
            true
        } catch (exception: Exception) {
            closePreparedUdpSocket(restored)
            DiagnosticLog.append("Could not restore the previous UDP network: ${exception.message}")
            false
        }
    } catch (exception: Exception) {
        DiagnosticLog.append("Could not restore the previous UDP network: ${exception.message}")
        false
    }

    private fun closePreparedUdpSocket(prepared: PreparedUdpSocket) {
        runCatching { prepared.selector?.close() }
        runCatching { prepared.channel.close() }
        prepared.socket.close()
    }

    private fun updateUnderlyingNetworksForCore(session: Long, handle: Long, networks: Array<Network>) {
        mainHandler.post {
            if (!isCurrentCore(session, handle) || socketNetwork != networks.firstOrNull()) return@post
            val updated = runCatching { setUnderlyingNetworks(networks) }.getOrElse {
                DiagnosticLog.append("Could not update VPN underlying network: ${it.message}")
                return@post
            }
            if (!updated) DiagnosticLog.append("Android did not accept the VPN underlying network update yet")
        }
    }

    private fun clearUnderlyingNetworksAfterStop() {
        val stoppedGeneration = sessionGeneration.get()
        mainHandler.post {
            if (sessionGeneration.get() != stoppedGeneration || !stopping.get()) return@post
            runCatching { setUnderlyingNetworks(emptyArray()) }
                .onFailure { DiagnosticLog.append("Could not clear VPN underlying network: ${it.message}") }
        }
    }

    private fun closeUdpSocketLocked() {
        val socket = udp
        val channel = udpChannel
        val selector = udpSelector
        udp = null
        udpChannel = null
        udpSelector = null
        socketNetwork = null
        socketBoundToNetwork = false
        udpReader?.interrupt()
        udpReader = null
        runCatching { selector?.close() }
        runCatching { channel?.close() }
        socket?.close()
    }

    private fun invalidateUdpSocket(session: Long, handle: Long, channel: DatagramChannel?) {
        synchronized(udpSocketLock) {
            if (!isCurrentCore(session, handle)) return
            if (channel == null || udpChannel === channel) closeUdpSocketLocked()
        }
    }

    private fun observeNetworks(session: Long, handle: Long) {
        if (!isCurrentCore(session, handle)) return
        val connectivity = getSystemService(ConnectivityManager::class.java)
        lateinit var recovery: NetworkRecoveryManager<UnderlayCandidate>
        recovery = NetworkRecoveryManager(
            scheduler = ExecutorRecoveryScheduler(recoveryExecutor),
            sessionIsCurrent = { isCurrentCore(session, handle) },
            onNetworkAvailable = { candidate, revision ->
                openUdpSocketWithFallback(candidate, session, handle, recovery, revision)
            },
            onNoNetwork = {
                val current = synchronized(lifecycleLock) {
                    if (!isCurrentCore(session, handle)) {
                        false
                    } else {
                        selectedNetwork = null
                        true
                    }
                }
                if (current) {
                    synchronized(udpSocketLock) {
                        if (isCurrentCore(session, handle)) closeUdpSocketLocked()
                    }
                    updateUnderlyingNetworksForCore(session, handle, emptyArray())
                    publishForCore(session, handle, "Reconnecting", "Waiting for a physical network for ZeroTier UDP")
                }
            },
            onPhaseChanged = { phase, sequence ->
                val applied = synchronized(lifecycleLock) {
                    if (!isCurrentCore(session, handle)) {
                        false
                    } else {
                        var phaseApplied = false
                        TunnelState.update { current ->
                            if (isCurrentCore(session, handle) && sequence > current.recoveryPhaseSequence) {
                                phaseApplied = true
                                current.copy(recoveryPhase = phase, recoveryPhaseSequence = sequence)
                            } else {
                                phaseApplied = false
                                current
                            }
                        }
                        phaseApplied
                    }
                }
                if (applied && isCurrentCore(session, handle) &&
                    TunnelState.state.value.recoveryPhaseSequence == sequence
                ) {
                        when (phase) {
                            NetworkRecoveryPhase.RETRY_WAIT -> DiagnosticLog.append("UDP transport recovery retry scheduled")
                            NetworkRecoveryPhase.READY -> {
                                DiagnosticLog.append("UDP transport is ready")
                                publishRecoveryStatus(session, handle, sequence, "Connected", "UDP transport is ready") {
                                    it.status == "Degraded" && it.networkState == VirtualNetworkState.AUTHORIZED
                                }
                            }
                            NetworkRecoveryPhase.EXHAUSTED -> publishRecoveryStatus(
                                session,
                                handle,
                                sequence,
                                "Degraded",
                                "UDP transport recovery attempts were exhausted",
                            ) { true }
                            else -> Unit
                        }
                    }
            },
            attemptExecutor = networkAttemptExecutor,
        )
        val recoveryInstalled = synchronized(lifecycleLock) {
            if (!isCurrentCore(session, handle)) {
                false
            } else {
                networkRecovery = recovery
                true
            }
        }
        if (!recoveryInstalled) {
            recovery.stop()
            return
        }
        synchronized(networkCandidateLock) {
            networkCandidates.clear()
            networkObservations.clear()
        }
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                if (!isCurrentCore(session, handle)) return
                synchronized(networkCandidateLock) {
                    if (!isCurrentCore(session, handle) || networkRecovery !== recovery) return
                    networkObservations.getOrPut(network) { NetworkObservation() }
                }
            }

            override fun onCapabilitiesChanged(network: Network, networkCapabilities: NetworkCapabilities) {
                if (!isCurrentCore(session, handle)) return
                refreshNetworkCandidate(
                    network,
                    capabilities = networkCapabilities,
                    recovery = recovery,
                    session = session,
                    handle = handle,
                )
            }

            override fun onLinkPropertiesChanged(network: Network, linkProperties: LinkProperties) {
                if (!isCurrentCore(session, handle)) return
                refreshNetworkCandidate(
                    network,
                    linkProperties = linkProperties,
                    recovery = recovery,
                    session = session,
                    handle = handle,
                )
            }

            override fun onLost(network: Network) {
                if (!isCurrentCore(session, handle)) return
                synchronized(networkCandidateLock) {
                    if (!isCurrentCore(session, handle) || networkRecovery !== recovery) return
                    networkObservations.remove(network)
                    networkCandidates.remove(network)
                }
                val lostBoundNetwork = synchronized(lifecycleLock) {
                    if (!isCurrentCore(session, handle)) {
                        false
                    } else {
                        synchronized(udpSocketLock) {
                            if (!sessionBoundNetworks.contains(network)) {
                                false
                            } else {
                                if (socketNetwork == network) {
                                    selectedNetwork = null
                                    closeUdpSocketLocked()
                                } else if (selectedNetwork == network) {
                                    selectedNetwork = null
                                }
                                true
                            }
                        }
                    }
                }
                if (lostBoundNetwork) {
                    activeUnderlayWasLost = true
                    if (socketNetwork == null) updateUnderlyingNetworksForCore(session, handle, emptyArray())
                    cancelPendingNetworkSwitchReconnect()
                    requestNetworkSwitchReconnect(
                        session,
                        handle,
                        recovery,
                        "Active physical network was lost",
                    )
                } else {
                    updateNetworkCandidate(recovery, session, handle)
                }
            }
        }
        synchronized(networkCallbackLock) {
            if (!isCurrentCore(session, handle) || networkCallback != null) return
            try {
                connectivity.registerNetworkCallback(
                    NetworkRequest.Builder()
                        .addCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)
                        .build(),
                    callback,
                )
                networkCallback = callback
            } catch (exception: Exception) {
                if (isCurrentCore(session, handle)) {
                    publishForCore(session, handle, "Degraded", "Could not monitor physical networks: ${exception.message}")
                }
            }
        }
    }

    private fun refreshNetworkCandidate(
        network: Network,
        recovery: NetworkRecoveryManager<UnderlayCandidate>,
        session: Long,
        handle: Long,
        capabilities: NetworkCapabilities? = null,
        linkProperties: LinkProperties? = null,
    ) {
        val updated = synchronized(networkCandidateLock) {
            if (!isCurrentCore(session, handle) || networkRecovery !== recovery) return
            val observation = networkObservations.getOrPut(network) { NetworkObservation() }
            if (capabilities != null) observation.capabilities = capabilities
            if (linkProperties != null) observation.linkProperties = linkProperties
            val currentCapabilities = observation.capabilities ?: return@synchronized false
            val currentLinkProperties = observation.linkProperties ?: return@synchronized false
            val transport = when {
                currentCapabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> UnderlayTransport.WIFI
                currentCapabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> UnderlayTransport.CELLULAR
                currentCapabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> UnderlayTransport.ETHERNET
                currentCapabilities.hasTransport(NetworkCapabilities.TRANSPORT_VPN) -> UnderlayTransport.VPN
                else -> UnderlayTransport.OTHER
            }
            val previous = networkCandidates[network]
            val descriptor = NetworkCandidateDescriptor(
                key = network.toString(),
                notVpn = currentCapabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN),
                transport = transport,
                hasInternet = currentCapabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET),
                validated = currentCapabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED),
                availableOrder = previous?.descriptor?.availableOrder ?: networkAvailableOrder.incrementAndGet(),
            )
            if (!descriptor.notVpn || transport !in setOf(UnderlayTransport.WIFI, UnderlayTransport.CELLULAR, UnderlayTransport.ETHERNET)) {
                networkCandidates.remove(network)
            } else {
                networkCandidates[network] = UnderlayCandidate(
                    network = network,
                    descriptor = descriptor,
                    linkFingerprint = linkPropertiesFingerprint(
                        currentLinkProperties.interfaceName,
                        currentLinkProperties.linkAddresses.map { it.toString() },
                        currentLinkProperties.routes.map { it.toString() },
                        linkPropertiesMtu(currentLinkProperties),
                    ),
                )
            }
            true
        }
        if (updated) updateNetworkCandidate(recovery, session, handle)
    }

    private fun linkPropertiesMtu(linkProperties: LinkProperties): Int =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) linkProperties.mtu else 0

    private fun openUdpSocketWithFallback(
        preferred: UnderlayCandidate,
        session: Long,
        handle: Long,
        recovery: NetworkRecoveryManager<UnderlayCandidate>,
        revision: Long,
    ): Boolean {
        if (!isCurrentCore(session, handle) || networkSwitchRestartPending.get() || networkSwitchDebouncePending.get()) return false
        val candidates = synchronized(networkCandidateLock) {
            val descriptors = networkCandidates.values.map { it.descriptor }
            rankNetworkCandidates(descriptors, socketNetwork?.toString())
                .mapNotNull { descriptor ->
                    networkCandidates.values.firstOrNull { it.descriptor.key == descriptor.key }
                }
        }
        val ordered = listOf(preferred) + candidates.filterNot { it.network == preferred.network }
        for (candidate in ordered) {
            if (!isCurrentCore(session, handle)) return false
            val stillAvailable = synchronized(networkCandidateLock) {
                networkCandidates[candidate.network]?.recoveryKey == candidate.recoveryKey
            }
            if (!stillAvailable) continue
            if (openUdpSocket(candidate.network, candidate.recoveryKey, session, handle, recovery, revision)) return true
        }
        return false
    }

    private fun updateNetworkCandidate(
        recovery: NetworkRecoveryManager<UnderlayCandidate>,
        session: Long,
        handle: Long,
    ) {
        if (!isCurrentCore(session, handle) || networkRecovery !== recovery) return
        val candidate = currentNetworkCandidate(recovery, session, handle)
        val candidateKey = candidate?.recoveryKey
        val candidateChanged = synchronized(networkSwitchStateLock) {
            if (sessionGeneration.get() != session || networkRecovery !== recovery) return
            val changed = hasCandidateKeyChanged(
                lastObservedCandidateKey,
                candidateKey,
                hasObservedCandidateKey,
            )
            hasObservedCandidateKey = true
            lastObservedCandidateKey = candidateKey
            changed
        }
        if (candidateChanged) {
            val shouldRestart = shouldRestartTunnelForNetworkSwitch(
                lastBoundNetwork?.toString(),
                socketNetwork?.toString(),
                candidate?.network?.toString(),
            )
            if (shouldRestart && candidate != null) {
                scheduleNetworkSwitchReconnect(session, handle, recovery, candidate.network)
            } else {
                cancelPendingNetworkSwitchReconnect()
            }
        }
        recovery.updateCandidate(candidateKey, candidate)
    }

    private fun currentNetworkCandidate(
        recovery: NetworkRecoveryManager<UnderlayCandidate>,
        session: Long,
        handle: Long,
    ): UnderlayCandidate? {
        if (!isCurrentCore(session, handle) || networkRecovery !== recovery) return null
        return synchronized(networkCandidateLock) {
            if (!isCurrentCore(session, handle) || networkRecovery !== recovery) {
                null
            } else {
                val descriptors = networkCandidates.values.map { it.descriptor }
                val selected = selectNetworkCandidate(
                    descriptors,
                    socketNetwork?.toString() ?: selectedNetwork?.toString(),
                ) ?: return@synchronized null
                networkCandidates.values.firstOrNull { it.descriptor.key == selected.key }
            }
        }
    }

    private fun scheduleNetworkSwitchReconnect(
        session: Long,
        handle: Long,
        recovery: NetworkRecoveryManager<UnderlayCandidate>,
        targetNetwork: Network,
    ) {
        if (!isCurrentCore(session, handle) || networkSwitchRestartPending.get()) return
        lateinit var task: Runnable
        task = Runnable {
            val wasPending = synchronized(networkSwitchDebounceLock) {
                if (pendingNetworkSwitchRunnable !== task) {
                    false
                } else {
                    pendingNetworkSwitchRunnable = null
                    true
                }
            }
            if (!wasPending) return@Runnable
            if (!isCurrentCore(session, handle) || networkSwitchRestartPending.get()) {
                clearNetworkSwitchDebouncePending()
                return@Runnable
            }
            val current = currentNetworkCandidate(recovery, session, handle)
            val activeNetwork = socketNetwork
            val switchStillNeeded = shouldRestartTunnelForNetworkSwitch(
                lastBoundNetwork?.toString(),
                activeNetwork?.toString(),
                targetNetwork.toString(),
            )
            if (current?.network == targetNetwork && switchStillNeeded) {
                requestNetworkSwitchReconnect(
                    session,
                    handle,
                    recovery,
                    "Preferred physical network changed",
                    targetNetwork,
                )
            }
            clearNetworkSwitchDebouncePending()
        }
        synchronized(networkSwitchDebounceLock) {
            pendingNetworkSwitchRunnable?.let(mainHandler::removeCallbacks)
            pendingNetworkSwitchRunnable = task
            networkSwitchDebouncePending.set(true)
            if (!mainHandler.postDelayed(task, NETWORK_SWITCH_DEBOUNCE_MILLIS)) {
                pendingNetworkSwitchRunnable = null
                networkSwitchDebouncePending.set(false)
            }
        }
    }

    private fun clearNetworkSwitchDebouncePending() {
        synchronized(networkSwitchDebounceLock) {
            if (pendingNetworkSwitchRunnable == null) networkSwitchDebouncePending.set(false)
        }
    }

    private fun cancelPendingNetworkSwitchReconnect() {
        synchronized(networkSwitchDebounceLock) {
            pendingNetworkSwitchRunnable?.let(mainHandler::removeCallbacks)
            if (pendingNetworkSwitchRunnable != null) networkSwitchDebouncePending.set(false)
            pendingNetworkSwitchRunnable = null
        }
    }

    private fun requestNetworkSwitchReconnect(
        session: Long,
        handle: Long,
        recovery: NetworkRecoveryManager<UnderlayCandidate>,
        reason: String,
        targetNetwork: Network? = null,
    ) {
        if (!isCurrentCore(session, handle) || !networkSwitchRestartPending.compareAndSet(false, true)) return
        if (!mainHandler.post {
                synchronized(this) {
                    if (!isCurrentCore(session, handle) || networkRecovery !== recovery) {
                        networkSwitchRestartPending.set(false)
                        return@synchronized
                    }
                    if (targetNetwork != null && !activeUnderlayWasLost) {
                        val candidate = currentNetworkCandidate(recovery, session, handle)
                        val activeNetwork = socketNetwork
                        val switchStillNeeded = shouldRestartTunnelForNetworkSwitch(
                            lastBoundNetwork?.toString(),
                            activeNetwork?.toString(),
                            targetNetwork.toString(),
                        )
                        if (candidate?.network != targetNetwork || !switchStillNeeded) {
                            networkSwitchRestartPending.set(false)
                            return@synchronized
                        }
                    }
                    cancelPendingNetworkSwitchReconnect()
                    stopTunnel(keepForeground = true, expectedSession = session)
                    runCatching { setUnderlyingNetworks(emptyArray()) }
                        .onFailure { DiagnosticLog.append("Could not clear VPN underlying network: ${it.message}") }
                    synchronized(lifecycleLock) { stopping.set(false) }
                    try {
                        publish("Reconnecting", "$reason; restarting the ZeroTier tunnel") {
                            it.copy(
                                tunnelActive = true,
                                nodeReady = false,
                                nodeState = NodeState.STARTING,
                                networkState = VirtualNetworkState.IDLE,
                            )
                        }
                        startTunnel()
                    } finally {
                        networkSwitchRestartPending.set(false)
                    }
                }
            }
        ) {
            networkSwitchRestartPending.set(false)
        }
    }

    private fun schedulePeerRefresh(session: Long, handle: Long, delayMillis: Long = 0) {
        if (!isCurrentCore(session, handle)) return
        try {
            recoveryExecutor.schedule({
                if (!isCurrentCore(session, handle)) return@schedule
                refreshPeers(session, handle)
                schedulePeerRefresh(session, handle, PEER_REFRESH_MILLIS)
            }, delayMillis, TimeUnit.MILLISECONDS)
        } catch (_: RejectedExecutionException) {
        }
    }

    private fun refreshPeers(session: Long, handle: Long) {
        executeCoreTask {
            if (!isCurrentCore(session, handle)) return@executeCoreTask
            val lines = runCatching { NativeCore.peers(handle) }.getOrNull()
            if (!isCurrentCore(session, handle)) return@executeCoreTask
            if (lines == null) {
                DiagnosticLog.append("Peer query failed")
                return@executeCoreTask
            }
            val peers = parsePeerLines(lines)
            TunnelState.update { current ->
                if (isCurrentCore(session, handle)) {
                    current.copy(peers = peers, peersQueried = true)
                } else {
                    current
                }
            }
        }
    }

    private fun applyNetworkConfig(
        session: Long,
        handle: Long,
        networkId: Long,
        operation: Int,
        status: Int,
        parsedConfig: NetworkConfig?,
    ): Int {
        if (!isCurrentCore(session, handle) || networkId != this.networkId) return 0
        if (operation == 3 || operation == 4) {
            if (!setConfigForCore(session, handle, null)) return 0
            closeTunInterface(session, handle)
            updateStateForCore(session, handle) {
                it.copy(
                    assignedAddresses = emptyList(),
                    activeRoutes = emptyList(),
                    networkState = if (operation == 4) VirtualNetworkState.LEFT else VirtualNetworkState.PENDING,
                )
            }
            if (operation == 4) {
                publishForCore(session, handle, "Disconnected", "Left ZeroTier network") {
                    it.copy(networkName = "", assignedAddresses = emptyList(), activeRoutes = emptyList())
                }
            }
            return 0
        }
        return try {
            val updated = parsedConfig ?: return 22
            if (!isCurrentCore(session, handle)) return 0
            if (updated.status == 1) {
                val activeRoutes = effectiveRoutes(updated)
                if (requiresTunUpdate(config, updated)) {
                    if (!applyVpnConfig(session, handle, updated)) return 0
                } else if (!setConfigForCore(session, handle, updated)) {
                    return 0
                }
                if (!isCurrentCore(session, handle)) return 0
                subscribeNetworkGroups(updated, session, handle)
                publishForCore(session, handle, "Connected", "Authorized virtual network") {
                    it.copy(
                        networkId = networkIdText,
                        networkName = updated.name,
                        networkState = VirtualNetworkState.AUTHORIZED,
                        assignedAddresses = updated.addresses.map { address -> "${address.address}/${address.prefix}" },
                        activeRoutes = activeRoutes.map { route ->
                            "${route.address}/${route.prefix} via ${route.gateway ?: "direct"}"
                        },
                    )
                }
            } else {
                if (!setConfigForCore(session, handle, null)) return 0
                closeTunInterface(session, handle)
                publishForCore(session, handle, statusText(updated.status), "Waiting for network authorization") {
                    it.copy(
                        networkId = networkIdText,
                        networkName = updated.name,
                        networkState = networkStateFor(updated.status),
                        assignedAddresses = emptyList(),
                        activeRoutes = emptyList(),
                    )
                }
            }
            0
        } catch (exception: Exception) {
            if (!setConfigForCore(session, handle, null)) return 0
            closeTunInterface(session, handle)
            publishForCore(session, handle, "Configuration error", exception.message ?: "Invalid network configuration") {
                it.copy(
                    assignedAddresses = emptyList(),
                    activeRoutes = emptyList(),
                    networkState = VirtualNetworkState.CONFIG_ERROR,
                )
            }
            22
        }
    }

    private fun networkStateFor(status: Int): VirtualNetworkState = when (status) {
        1 -> VirtualNetworkState.AUTHORIZED
        2 -> VirtualNetworkState.ACCESS_DENIED
        3 -> VirtualNetworkState.NOT_FOUND
        4 -> VirtualNetworkState.PORT_ERROR
        5 -> VirtualNetworkState.CLIENT_TOO_OLD
        6 -> VirtualNetworkState.AUTH_REQUIRED
        else -> VirtualNetworkState.PENDING
    }

    private fun effectiveRoutes(config: NetworkConfig): List<RouteConfig> =
        config.routes.ifEmpty { config.addresses.map { RouteConfig(it.address, it.prefix) } }

    private fun requiresTunUpdate(current: NetworkConfig?, updated: NetworkConfig): Boolean =
        current == null ||
                current.addresses != updated.addresses ||
                effectiveRoutes(current) != effectiveRoutes(updated) ||
                current.dnsServers != updated.dnsServers ||
                current.dnsDomain != updated.dnsDomain ||
                current.mtu != updated.mtu

    private fun applyVpnConfig(session: Long, handle: Long, config: NetworkConfig): Boolean {
        val builder = Builder()
            .setSession("Lunavane")
            .setMtu(config.mtu.coerceIn(576, 9000))
            .setUnderlyingNetworks(socketNetwork?.let { arrayOf(it) } ?: emptyArray())
            .setBlocking(true)
        for (address in config.addresses) {
            builder.addAddress(address.address, address.prefix.coerceIn(1, if (address.address.contains(':')) 128 else 32))
        }
        effectiveRoutes(config).forEach { route ->
            builder.addRoute(route.address, route.prefix.coerceIn(0, if (route.address.contains(':')) 128 else 32))
        }
        config.dnsServers.forEach(builder::addDnsServer)
        if (config.dnsDomain.isNotBlank()) builder.addSearchDomain(config.dnsDomain)
        builder.addDisallowedApplication(packageName)
        val descriptor = builder.establish() ?: error("Android did not establish the VPN interface")
        val installed = synchronized(tunOutputLock) {
            synchronized(lifecycleLock) {
                if (!isCurrentCore(session, handle)) {
                    runCatching { descriptor.close() }
                    false
                } else {
                    closeTunInterfaceLocked()
                    tun = descriptor
                    val input = FileInputStream(descriptor.fileDescriptor)
                    tunInput = input
                    tunOutput = FileOutputStream(descriptor.fileDescriptor)
                    this.config = config
                    tunReader = thread(name = "ZeroTier-tun-$session") { readTunPackets(input, session, handle) }
                    true
                }
            }
        }
        if (installed && isCurrentCore(session, handle)) {
            val underlying = socketNetwork?.let { arrayOf(it) } ?: emptyArray()
            updateUnderlyingNetworksForCore(session, handle, underlying)
        }
        return installed
    }

    private fun closeTunInterface(session: Long? = null, handle: Long? = null) {
        synchronized(tunOutputLock) {
            if (session != null && handle != null && !isCurrentCore(session, handle)) return
            closeTunInterfaceLocked()
        }
    }

    private fun closeTunInterfaceLocked() {
        val reader = tunReader
        tunReader = null
        val input = tunInput
        val output = tunOutput
        val descriptor = tun
        tunInput = null
        tunOutput = null
        tun = null
        runCatching { input?.close() }
        runCatching { output?.close() }
        runCatching { descriptor?.close() }
        reader?.interrupt()
    }

    private fun subscribeNetworkGroups(config: NetworkConfig, session: Long, handle: Long) {
        config.multicast.forEach { subscribe(it.mac, it.adi, session, handle) }
        val ipv4Addresses = config.addresses.filterNot { it.address.contains(':') }.mapNotNull {
            runCatching { InetAddress.getByName(it.address).address }.getOrNull()
        }
        ipv4Addresses.forEach { address ->
            val adi = ByteBuffer.wrap(address).order(ByteOrder.BIG_ENDIAN).int.toLong() and 0xffffffffL
            subscribe(BROADCAST_MAC, adi, session, handle)
        }
        config.addresses.filter { it.address.contains(':') }.mapNotNull { ipv6Bytes(it.address) }.forEach { address ->
            subscribe(ipv6MulticastMac(solicitedNodeAddress(address)), 0, session, handle)
        }
    }

    private fun subscribe(
        mac: Long,
        adi: Long,
        session: Long = sessionGeneration.get(),
        handle: Long = coreHandle.get(),
    ) {
        val key = (mac * 0x9e3779b97f4a7c15UL.toLong()) xor adi
        if (!multicastSubscribed.add(key)) return
        executeCoreTask {
            if (!isCurrentCore(session, handle)) {
                multicastSubscribed.remove(key)
                return@executeCoreTask
            }
            val result = runCatching {
                NativeCore.multicastSubscribe(handle, networkId, mac, adi)
            }
            if (result.getOrNull() != 0) {
                multicastSubscribed.remove(key)
                val code = result.getOrNull()?.let { " ($it)" }.orEmpty()
                DiagnosticLog.append("Multicast subscription failed$code")
            }
        }
    }

    private fun readTunPackets(input: FileInputStream, session: Long, handle: Long) {
        val buffer = ByteArray(65536)
        while (isCurrentCore(session, handle)) {
            try {
                val count = input.read(buffer)
                if (count <= 0) continue
                if (!isCurrentCore(session, handle)) break
                val packet = buffer.copyOf(count)
                when ((packet[0].toInt() ushr 4) and 0xf) {
                    4 -> processIpv4Packet(packet, session, handle)
                    6 -> processIpv6Packet(packet, session, handle)
                }
            } catch (exception: IOException) {
                if (isCurrentCore(session, handle) && tunReader === Thread.currentThread()) {
                    publishForCore(session, handle, "Tunnel error", exception.message ?: "TUN read failed")
                }
                break
            }
        }
    }

    private fun logPacketOnce(key: String, message: String) {
        if (packetDiagnosticKeys.add(key)) DiagnosticLog.append(message)
    }

    private fun ipv4Text(value: Long): String =
        runCatching { InetAddress.getByAddress(u32Bytes(value)).hostAddress }.getOrDefault("unknown")

    private fun queuePendingIpv4(nextHop: Long, packet: ByteArray, session: Long, handle: Long) {
        synchronized(pendingIpv4) {
            if (!isCurrentCore(session, handle) ||
                pendingIpv4Count >= MAX_PENDING_PACKETS ||
                pendingIpv4Bytes + packet.size > MAX_PENDING_BYTES
            ) {
                return
            }
            pendingIpv4.getOrPut(nextHop) { ArrayDeque() }.addLast(packet)
            pendingIpv4Count++
            pendingIpv4Bytes += packet.size
        }
    }

    private fun takePendingIpv4(nextHop: Long): List<ByteArray> = synchronized(pendingIpv4) {
        val packets = pendingIpv4.remove(nextHop) ?: return@synchronized emptyList()
        pendingIpv4Count -= packets.size
        pendingIpv4Bytes -= packets.sumOf(ByteArray::size)
        packets.toList()
    }

    private fun queuePendingIpv6(nextHop: String, packet: ByteArray, session: Long, handle: Long) {
        synchronized(pendingIpv6) {
            if (!isCurrentCore(session, handle) ||
                pendingIpv6Count >= MAX_PENDING_PACKETS ||
                pendingIpv6Bytes + packet.size > MAX_PENDING_BYTES
            ) {
                return
            }
            pendingIpv6.getOrPut(nextHop) { ArrayDeque() }.addLast(packet)
            pendingIpv6Count++
            pendingIpv6Bytes += packet.size
        }
    }

    private fun takePendingIpv6(nextHop: String): List<ByteArray> = synchronized(pendingIpv6) {
        val packets = pendingIpv6.remove(nextHop) ?: return@synchronized emptyList()
        pendingIpv6Count -= packets.size
        pendingIpv6Bytes -= packets.sumOf(ByteArray::size)
        packets.toList()
    }

    private fun processIpv4Packet(packet: ByteArray, session: Long, handle: Long) {
        if (!isCurrentCore(session, handle)) return
        val current = config ?: return
        if (packet.size < 20) return
        val destination = u32(packet, 16)
        val multicast = packet[16].toInt() and 0xf0 == 0xe0
        val route = current.routes
            .asSequence()
            .filter { route -> route.address.indexOf(':') < 0 && ipv4RouteContains(route, destination) }
            .maxByOrNull(RouteConfig::prefix)
        val nextHop = route?.gateway?.let(::ipv4Value) ?: destination
        val routeLabel = route?.let { "${it.address}/${it.prefix} via ${it.gateway ?: "direct"}" }
        if (routeLabel != null) {
            logPacketOnce("ipv4-route:$routeLabel", "IPv4 route selected: $routeLabel; next hop ${ipv4Text(nextHop)}")
        } else {
            logPacketOnce("ipv4-route-direct", "No matching managed IPv4 route; using destination ${ipv4Text(nextHop)} as next hop")
        }
        val destinationMac = when {
            destination == 0xffffffffL -> BROADCAST_MAC
            multicast -> ipv4MulticastMac(packet, 16)
            else -> arpTable[nextHop]
        }
        if (destinationMac != null) {
            if (multicast) subscribe(destinationMac, 0, session, handle)
            submitFrame(current.mac, destinationMac, IPV4_ETHER_TYPE, packet, session, handle)
            return
        }

        val sourceAddress = current.addresses.firstOrNull { !it.address.contains(':') } ?: return
        val source = runCatching { InetAddress.getByName(sourceAddress.address).address }.getOrNull() ?: return
        val routeKey = route?.let { "${it.address}/${it.prefix}:${it.gateway}" } ?: "direct"
        logPacketOnce("ipv4-arp:$routeKey", "Sending ARP request for IPv4 next hop ${ipv4Text(nextHop)}")
        queuePendingIpv4(nextHop, packet, session, handle)
        val arp = buildArpRequest(current.mac, source, u32Bytes(nextHop))
        submitFrame(current.mac, BROADCAST_MAC, ARP_ETHER_TYPE, arp, session, handle)
    }

    private fun ipv4RouteContains(route: RouteConfig, address: Long): Boolean {
        val network = ipv4Value(route.address) ?: return false
        val bits = route.prefix.coerceIn(0, 32)
        val mask = if (bits == 0) 0L else (0xffffffffL shl (32 - bits)) and 0xffffffffL
        return (network and mask) == (address and mask)
    }

    private fun ipv4Value(address: String): Long? = runCatching {
        val bytes = InetAddress.getByName(address).address
        if (bytes.size != 4) return null
        u32(bytes, 0)
    }.getOrNull()

    private fun processIpv6Packet(packet: ByteArray, session: Long, handle: Long) {
        if (!isCurrentCore(session, handle)) return
        val current = config ?: return
        if (packet.size < 40 || packet[0].toInt() ushr 4 != 6) return
        val destination = packet.copyOfRange(24, 40)
        val multicast = destination[0].toInt() and 0xff == 0xff
        if (multicast) {
            val destinationMac = ipv6MulticastMac(destination)
            subscribe(destinationMac, 0, session, handle)
            submitFrame(current.mac, destinationMac, IPV6_ETHER_TYPE, packet, session, handle)
            return
        }

        val route = current.routes
            .asSequence()
            .filter { route -> route.address.contains(':') && ipv6RouteContains(route, destination) }
            .maxByOrNull(RouteConfig::prefix)
        val nextHop = route?.gateway?.let(::ipv6Bytes) ?: destination
        val key = ipv6Key(nextHop)
        val destinationMac = neighborTable[key]
        if (destinationMac != null) {
            submitFrame(current.mac, destinationMac, IPV6_ETHER_TYPE, packet, session, handle)
            return
        }

        queuePendingIpv6(key, packet, session, handle)
        val now = System.currentTimeMillis()
        synchronized(lastNeighborSolicitation) {
            if (!isCurrentCore(session, handle)) return
            val previous = lastNeighborSolicitation[key]
            if (previous == null || now - previous >= 3000) {
                val source = packet.copyOfRange(8, 24)
                val solicitation = buildNeighborSolicitation(source, nextHop, current.mac)
                val solicitationDestination = solicitedNodeAddress(nextHop)
                val multicastMac = ipv6MulticastMac(solicitationDestination)
                subscribe(multicastMac, 0, session, handle)
                submitFrame(current.mac, multicastMac, IPV6_ETHER_TYPE, solicitation, session, handle)
                lastNeighborSolicitation[key] = now
            }
        }
    }

    private fun ipv6RouteContains(route: RouteConfig, address: ByteArray): Boolean {
        val network = ipv6Bytes(route.address) ?: return false
        val bits = route.prefix.coerceIn(0, 128)
        val fullBytes = bits / 8
        val remainingBits = bits % 8
        for (index in 0 until fullBytes) {
            if (network[index] != address[index]) return false
        }
        if (remainingBits == 0) return true
        val mask = (0xff shl (8 - remainingBits)) and 0xff
        return (network[fullBytes].toInt() and mask) == (address[fullBytes].toInt() and mask)
    }

    private fun ipv6Bytes(address: String): ByteArray? = runCatching {
        InetAddress.getByName(address).address.takeIf { it.size == 16 }
    }.getOrNull()

    private fun ipv6Key(address: ByteArray): String =
        address.joinToString("") { "%02x".format(it.toInt() and 0xff) }

    private fun solicitedNodeAddress(target: ByteArray): ByteArray = ByteArray(16).apply {
        this[0] = 0xff.toByte()
        this[1] = 0x02
        this[11] = 0x01
        this[12] = 0xff.toByte()
        target.copyInto(this, 13, 13, 16)
    }

    private fun buildNeighborSolicitation(source: ByteArray, target: ByteArray, sourceMac: Long): ByteArray {
        val destination = solicitedNodeAddress(target)
        val unspecifiedSource = source.all { it == 0.toByte() }
        val icmp = ByteBuffer.allocate(if (unspecifiedSource) 24 else 32).order(ByteOrder.BIG_ENDIAN).apply {
            put(135.toByte())
            put(0)
            putShort(0)
            putInt(0)
            put(target)
            if (!unspecifiedSource) {
                put(1)
                put(1)
                put(macBytes(sourceMac))
            }
        }.array()
        setIcmpv6Checksum(icmp, source, destination)
        return buildIpv6Packet(source, destination, icmp)
    }

    private fun buildNeighborAdvertisement(
        source: ByteArray,
        destination: ByteArray,
        target: ByteArray,
        sourceMac: Long,
        solicited: Boolean,
    ): ByteArray {
        val icmp = ByteBuffer.allocate(32).order(ByteOrder.BIG_ENDIAN).apply {
            put(136.toByte())
            put(0)
            putShort(0)
            putInt((if (solicited) 0x40000000 else 0) or 0x20000000)
            put(target)
            put(2)
            put(1)
            put(macBytes(sourceMac))
        }.array()
        setIcmpv6Checksum(icmp, source, destination)
        return buildIpv6Packet(source, destination, icmp)
    }

    private fun buildIpv6Packet(source: ByteArray, destination: ByteArray, payload: ByteArray): ByteArray =
        ByteBuffer.allocate(40 + payload.size).order(ByteOrder.BIG_ENDIAN).apply {
            putInt(0x60000000)
            putShort(payload.size.toShort())
            put(58)
            put(255.toByte())
            put(source)
            put(destination)
            put(payload)
        }.array()

    private fun setIcmpv6Checksum(icmp: ByteArray, source: ByteArray, destination: ByteArray) {
        var sum = 0L
        fun addWords(bytes: ByteArray) {
            var index = 0
            while (index + 1 < bytes.size) {
                sum += ((bytes[index].toInt() and 0xff) shl 8) or (bytes[index + 1].toInt() and 0xff)
                index += 2
            }
            if (index < bytes.size) sum += (bytes[index].toInt() and 0xff) shl 8
        }
        addWords(source)
        addWords(destination)
        sum += icmp.size.toLong()
        sum += 58
        addWords(icmp)
        while (sum ushr 16 != 0L) sum = (sum and 0xffff) + (sum ushr 16)
        val checksum = sum.inv().toInt() and 0xffff
        icmp[2] = (checksum ushr 8).toByte()
        icmp[3] = checksum.toByte()
    }

    private fun isNeighborDiscovery(packet: ByteArray): Boolean =
        packet.size >= 64 && packet[6].toInt() and 0xff == 58 && packet[40].toInt() and 0xff in 133..136

    private fun submitFrame(
        sourceMac: Long,
        destinationMac: Long,
        etherType: Int,
        packet: ByteArray,
        session: Long,
        handle: Long,
    ) {
        enqueuePacketWork(session, handle, packet.size) {
            if (!isCurrentCore(session, handle)) return@enqueuePacketWork
            val result = NativeCore.processVirtualFrame(handle, networkId, sourceMac, destinationMac, etherType, 0, packet)
            if (result != 0) {
                val frameType = when (etherType) {
                    ARP_ETHER_TYPE -> "ARP"
                    IPV4_ETHER_TYPE -> "IPv4"
                    IPV6_ETHER_TYPE -> "IPv6"
                    else -> "Ethernet"
                }
                logPacketOnce("core-frame:$frameType:$result", "ZeroTier rejected an outgoing $frameType frame ($result)")
            }
        }
    }

    private fun enqueuePacketWork(session: Long, handle: Long, bytes: Int, action: () -> Unit): Boolean {
        if (!isCurrentCore(session, handle)) return false
        val lease = packetWorkBudget.tryAcquire(bytes) ?: return false
        val scheduled = executeCoreTask {
            try {
                action()
            } finally {
                lease.release()
            }
        }
        if (!scheduled) lease.release()
        return scheduled
    }

    private fun handleVirtualFrame(
        session: Long,
        handle: Long,
        networkId: Long,
        sourceMac: Long,
        destinationMac: Long,
        etherType: Int,
        vlanId: Int,
        payload: ByteArray,
    ) {
        if (networkId != this.networkId || !isCurrentCore(session, handle)) return
        when (etherType) {
            ARP_ETHER_TYPE -> handleArp(payload, session, handle)
            IPV4_ETHER_TYPE -> {
                if (payload.size >= 20 && (payload[0].toInt() ushr 4) == 4) {
                    arpTable[u32(payload, 12)] = sourceMac
                    logPacketOnce("ipv4-core-inbound", "Received an IPv4 frame from ZeroTier")
                }
                writeTun(payload, session, handle)
            }
            IPV6_ETHER_TYPE -> handleIpv6Frame(sourceMac, payload, session, handle)
        }
    }

    private fun handleIpv6Frame(sourceMac: Long, packet: ByteArray, session: Long, handle: Long) {
        if (packet.size < 40 || packet[0].toInt() ushr 4 != 6) return
        val source = packet.copyOfRange(8, 24)
        if (isNeighborDiscovery(packet)) {
            when (packet[40].toInt() and 0xff) {
                135 -> answerNeighborSolicitation(sourceMac, packet, session, handle)
                136 -> learnNeighborAdvertisement(sourceMac, packet, session, handle)
                else -> writeTun(packet, session, handle)
            }
            return
        }
        if (source.any { it != 0.toByte() }) {
            val key = ipv6Key(source)
            neighborTable[key] = sourceMac
            lastNeighborSolicitation.remove(key)
            val current = config
            if (current != null) {
                takePendingIpv6(key).forEach { pending ->
                    submitFrame(current.mac, sourceMac, IPV6_ETHER_TYPE, pending, session, handle)
                }
            }
        }
        writeTun(packet, session, handle)
    }

    private fun answerNeighborSolicitation(sourceMac: Long, packet: ByteArray, session: Long, handle: Long) {
        if (packet.size < 64) return
        val current = config ?: return
        val target = packet.copyOfRange(48, 64)
        val ownAddress = current.addresses
            .asSequence()
            .filter { it.address.contains(':') }
            .mapNotNull { ipv6Bytes(it.address) }
            .firstOrNull { it.contentEquals(target) } ?: return
        val source = packet.copyOfRange(8, 24)
        val dad = source.all { it == 0.toByte() }
        val destination = if (dad) ByteArray(16).apply {
            this[0] = 0xff.toByte()
            this[1] = 0x02
            this[15] = 1
        } else source
        val destinationMac = if (dad) 0x333300000001L else sourceMac
        val advertisement = buildNeighborAdvertisement(ownAddress, destination, ownAddress, current.mac, !dad)
        submitFrame(current.mac, destinationMac, IPV6_ETHER_TYPE, advertisement, session, handle)
    }

    private fun learnNeighborAdvertisement(sourceMac: Long, packet: ByteArray, session: Long, handle: Long) {
        if (packet.size < 64) return
        val target = packet.copyOfRange(48, 64)
        var targetMac = sourceMac
        var offset = 64
        while (offset + 2 <= packet.size) {
            val type = packet[offset].toInt() and 0xff
            val optionLength = (packet[offset + 1].toInt() and 0xff) * 8
            if (optionLength == 0 || offset + optionLength > packet.size) break
            if (type == 2 && optionLength >= 8) {
                targetMac = u48(packet, offset + 2)
                break
            }
            offset += optionLength
        }
        val key = ipv6Key(target)
        neighborTable[key] = targetMac
        lastNeighborSolicitation.remove(key)
        val current = config ?: return
        takePendingIpv6(key).forEach { pending ->
            submitFrame(current.mac, targetMac, IPV6_ETHER_TYPE, pending, session, handle)
        }
    }

    private fun handleArp(packet: ByteArray, session: Long, handle: Long) {
        if (packet.size < 28 || packet[4].toInt() != 6 || packet[5].toInt() != 4) return
        val current = config ?: return
        val senderMac = u48(packet, 8)
        val senderIp = u32(packet, 14)
        val operation = ((packet[6].toInt() and 0xff) shl 8) or (packet[7].toInt() and 0xff)
        if (operation == 2) {
            logPacketOnce("ipv4-arp-reply", "Received an ARP reply from ${ipv4Text(senderIp)} over ZeroTier")
        }
        arpTable[senderIp] = senderMac
        val pendingPackets = takePendingIpv4(senderIp)
        if (pendingPackets.isNotEmpty()) {
            logPacketOnce(
                "ipv4-arp-resolved",
                "ARP resolved IPv4 next hop ${ipv4Text(senderIp)}; forwarding ${pendingPackets.size} queued packet(s)",
            )
            pendingPackets.forEach { pending ->
                submitFrame(current.mac, senderMac, IPV4_ETHER_TYPE, pending, session, handle)
            }
        }
        val targetIp = u32(packet, 24)
        val own = current.addresses.firstOrNull { address ->
            !address.address.contains(':') &&
                    runCatching { u32(InetAddress.getByName(address.address).address, 0) == targetIp }.getOrDefault(false)
        } ?: return
        if (operation != 1) return
        val targetBytes = runCatching { InetAddress.getByName(own.address).address }.getOrNull() ?: return
        val senderBytes = packet.copyOfRange(14, 18)
        val reply = buildArpReply(current.mac, targetBytes, senderMac, senderBytes)
        submitFrame(current.mac, senderMac, ARP_ETHER_TYPE, reply, session, handle)
    }

    private fun buildArpRequest(sourceMac: Long, sourceIp: ByteArray, targetIp: ByteArray): ByteArray =
        ByteBuffer.allocate(28).order(ByteOrder.BIG_ENDIAN).apply {
            putShort(1)
            putShort(IPV4_ETHER_TYPE.toShort())
            put(6)
            put(4)
            putShort(1)
            put(macBytes(sourceMac))
            put(sourceIp)
            put(ByteArray(6))
            put(targetIp)
        }.array()

    private fun buildArpReply(sourceMac: Long, sourceIp: ByteArray, destinationMac: Long, destinationIp: ByteArray): ByteArray =
        ByteBuffer.allocate(28).order(ByteOrder.BIG_ENDIAN).apply {
            putShort(1)
            putShort(IPV4_ETHER_TYPE.toShort())
            put(6)
            put(4)
            putShort(2)
            put(macBytes(sourceMac))
            put(sourceIp)
            put(macBytes(destinationMac))
            put(destinationIp)
        }.array()

    private fun writeTun(packet: ByteArray, session: Long, handle: Long) {
        if (!isCurrentCore(session, handle)) return
        val lease = packetWorkBudget.tryAcquire(packet.size) ?: run {
            logPacketOnce("packet-work-budget", "Dropped packet work because the bounded packet queue is full")
            return
        }
        val scheduled = executePacketTask {
            try {
                if (!isCurrentCore(session, handle)) return@executePacketTask
                val ipv4 = packet.isNotEmpty() && (packet[0].toInt() ushr 4) == 4
                val output = synchronized(tunOutputLock) {
                    if (isCurrentCore(session, handle)) tunOutput else null
                }
                if (output == null) {
                    if (ipv4) logPacketOnce("ipv4-tun-output-unavailable", "Dropped an inbound IPv4 packet because the TUN output is unavailable")
                    return@executePacketTask
                }
                try {
                    output.write(packet)
                    if (ipv4) logPacketOnce("ipv4-tun-write", "Wrote an inbound IPv4 packet to the Android TUN")
                } catch (exception: IOException) {
                    if (isCurrentCore(session, handle) && tunOutput === output) {
                        publishForCore(session, handle, "Tunnel error", exception.message ?: "TUN write failed")
                    }
                }
            } finally {
                lease.release()
            }
        }
        if (!scheduled) lease.release()
    }

    private fun installDefaultRouteSocket(
        session: Long,
        handle: Long,
        failedChannel: DatagramChannel?,
        expectedNetwork: Network?,
    ): DefaultRouteFallbackOutcome {
        val stillRelevant = synchronized(lifecycleLock) {
            if (!isCurrentCore(session, handle) || stopping.get()) {
                false
            } else {
                synchronized(udpSocketLock) {
                    failedChannel != null && udpChannel === failedChannel &&
                        socketNetwork == expectedNetwork && socketBoundToNetwork
                }
            }
        }
        if (!stillRelevant) return DefaultRouteFallbackOutcome.OBSOLETE
        val network = expectedNetwork ?: return DefaultRouteFallbackOutcome.OBSOLETE

        val prepared = try {
            prepareUdpSocket(null)
        } catch (exception: Exception) {
            DiagnosticLog.append("Could not create a default-route UDP socket: ${exception.message}")
            return DefaultRouteFallbackOutcome.FAILED
        }
        var installedPrepared = false
        var installFailed = false
        val installed = synchronized(lifecycleLock) {
            if (!isCurrentCore(session, handle) || stopping.get()) {
                false
            } else {
                synchronized(udpSocketLock) {
                    if (udpChannel !== failedChannel || socketNetwork != network || !socketBoundToNetwork) {
                        false
                    } else {
                        closeUdpSocketLocked()
                        try {
                            bindPreparedUdpSocket(prepared)
                            installPreparedUdpSocketLocked(prepared, network, session, handle)
                            installedPrepared = true
                            true
                        } catch (exception: Exception) {
                            installFailed = true
                            DiagnosticLog.append("Could not install a default-route UDP socket: ${exception.message}")
                            false
                        }
                    }
                }
            }
        }
        if (!installedPrepared) closePreparedUdpSocket(prepared)
        return when {
            installed || installedPrepared -> DefaultRouteFallbackOutcome.INSTALLED
            installFailed -> DefaultRouteFallbackOutcome.FAILED
            else -> DefaultRouteFallbackOutcome.OBSOLETE
        }
    }

    private fun scheduleDefaultRouteFallback(
        session: Long,
        handle: Long,
        failedChannel: DatagramChannel?,
        expectedNetwork: Network?,
    ) {
        if (!isCurrentCore(session, handle) || !defaultRouteFallbackPending.compareAndSet(false, true)) return
        val scheduled = try {
            networkAttemptExecutor.execute {
                try {
                    when (installDefaultRouteSocket(session, handle, failedChannel, expectedNetwork)) {
                        DefaultRouteFallbackOutcome.INSTALLED ->
                            DiagnosticLog.append("UDP socket cannot reach its bound network; using the protected default route")
                        DefaultRouteFallbackOutcome.FAILED -> {
                            logPacketOnce(
                                "default-route-fallback",
                                "Could not switch the ZeroTier UDP socket to the default route",
                            )
                            invalidateUdpSocket(session, handle, failedChannel)
                            notifyTransportFailure(session, handle)
                        }
                        DefaultRouteFallbackOutcome.OBSOLETE -> Unit
                    }
                } finally {
                    defaultRouteFallbackPending.set(false)
                }
            }
            true
        } catch (_: RejectedExecutionException) {
            false
        }
        if (!scheduled) defaultRouteFallbackPending.set(false)
    }

    private fun sendWirePacket(
        session: Long,
        handle: Long,
        address: ByteArray,
        port: Int,
        packet: ByteArray,
    ): Boolean {
        val socket = udp
        val channel = udpChannel
        val result = attemptWirePacketSend(
            sessionIsCurrent = { isCurrentCore(session, handle) },
            stopping = stopping::get,
            socketState = {
                when {
                    socket == null || channel == null -> WirePacketSocketState.MISSING
                    udp !== socket || udpChannel !== channel -> WirePacketSocketState.REPLACED
                    socket.isClosed || !channel.isOpen -> WirePacketSocketState.CLOSED
                    else -> WirePacketSocketState.READY
                }
            },
            send = {
                channel!!.send(
                    ByteBuffer.wrap(packet),
                    InetSocketAddress(InetAddress.getByAddress(address), port),
                ) == packet.size
            },
        )
        return when (result) {
            WirePacketSendResult.Sent -> {
                clearWireSendFailure(session, handle)
                true
            }
            WirePacketSendResult.StaleSession,
            WirePacketSendResult.Stopping -> false
            WirePacketSendResult.SocketUnavailable -> {
                reportWireSendFailure(session, handle, "socket-unavailable", "No UDP socket is available")
                notifyTransportFailure(session, handle)
                false
            }
            WirePacketSendResult.SocketClosed -> {
                reportWireSendFailure(session, handle, "socket-closed", "UDP socket is closed")
                if (isCurrentCore(session, handle)) {
                    invalidateUdpSocket(session, handle, channel)
                    notifyTransportFailure(session, handle)
                }
                false
            }
            WirePacketSendResult.SocketReplaced -> {
                reportWireSendFailure(session, handle, "socket-replaced", "UDP socket changed during send")
                false
            }
            is WirePacketSendResult.Failed -> {
                if (socket != null && channel != null && udp === socket && udpChannel === channel &&
                    shouldFallbackToDefaultRoute(result.category, socketBoundToNetwork)
                ) {
                    scheduleDefaultRouteFallback(session, handle, channel, socketNetwork)
                    return false
                }
                val category = result.category.name.lowercase().replace('_', '-')
                reportWireSendFailure(session, handle, category, result.message)
                if (result.category in RECOVERABLE_WIRE_FAILURES && isCurrentCore(session, handle)) {
                    invalidateUdpSocket(session, handle, channel)
                    notifyTransportFailure(session, handle)
                }
                false
            }
        }
    }

    private fun notifyTransportFailure(session: Long, handle: Long) {
        val recovery = synchronized(lifecycleLock) {
            if (isCurrentCore(session, handle)) networkRecovery else null
        }
        recovery?.onTransportFailure()
    }

    private fun notifyTransportSuccess(session: Long, handle: Long) {
        val recovery = synchronized(lifecycleLock) {
            if (isCurrentCore(session, handle)) networkRecovery else null
        }
        recovery?.onTransportSuccess()
    }

    private fun reportWireSendFailure(session: Long, handle: Long, category: String, detail: String): Boolean {
        val shouldReport = synchronized(lifecycleLock) {
            if (!isCurrentCore(session, handle) || lastWireFailureCategory == category) {
                false
            } else {
                lastWireFailureCategory = category
                true
            }
        }
        if (!shouldReport) return false
        executePacketTask {
            publishForCore(session, handle, "Degraded", "UDP send failed ($category): $detail")
        }
        return false
    }

    private fun clearWireSendFailure(session: Long, handle: Long) {
        val previousCategory = synchronized(lifecycleLock) {
            if (!isCurrentCore(session, handle)) return
            lastWireFailureCategory.also { lastWireFailureCategory = null }
        } ?: return
        executePacketTask {
            DiagnosticLog.append("UDP send recovered after $previousCategory")
            publishForCoreIf(
                session,
                handle,
                "Connected",
                "UDP send recovered after $previousCategory",
                shouldPublish = {
                    it.status == "Degraded" && it.networkState == VirtualNetworkState.AUTHORIZED
                },
            )
        }
        notifyTransportSuccess(session, handle)
    }

    private fun handleNodeEvent(session: Long, handle: Long, eventType: Int, message: String) {
        if (!isCurrentCore(session, handle)) return
        when (eventType) {
            0 -> publishForCore(session, handle, "Starting", "ZeroTier node initialized")
            1 -> publishForCore(session, handle, "Offline", "No upstream route currently available")
            2 -> publishForCore(session, handle, "Online", "ZeroTier upstream reachable")
            else -> if (message.isNotBlank()) publishForCore(session, handle, "Node event", message)
        }
        if (message.matches(Regex("[0-9a-f]{10}"))) {
            updateStateForCore(session, handle) { it.copy(nodeAddress = message) }
        }
    }

    private fun statusText(status: Int) = when (status) {
        0 -> "Requesting configuration"
        2 -> "Access denied"
        3 -> "Network not found"
        4 -> "Port error"
        5 -> "Client too old"
        6 -> "Authentication required"
        else -> "Network pending"
    }

    private fun publishRecoveryStatus(
        session: Long,
        handle: Long,
        sequence: Long,
        status: String,
        detail: String,
        shouldPublish: (TunnelUiState) -> Boolean,
    ) {
        synchronized(lifecycleLock) {
            if (!isCurrentCore(session, handle)) return
            var applied = false
            TunnelState.update { current ->
                if (isCurrentCore(session, handle) &&
                    current.recoveryPhaseSequence == sequence &&
                    shouldPublish(current)
                ) {
                    applied = true
                    current.copy(status = status, detail = detail, networkId = networkIdText)
                } else {
                    applied = false
                    current
                }
            }
            if (applied) {
                DiagnosticLog.append("$status: $detail")
                scheduleNotificationUpdate(session)
            }
        }
    }

    private fun publishForCore(
        session: Long,
        handle: Long,
        status: String,
        detail: String,
        transform: (TunnelUiState) -> TunnelUiState = { it },
    ): Boolean = publishForSession(session, handle, status, detail, transform)

    private fun publishForCoreIf(
        session: Long,
        handle: Long,
        status: String,
        detail: String,
        shouldPublish: (TunnelUiState) -> Boolean,
        transform: (TunnelUiState) -> TunnelUiState = { it },
    ): Boolean = synchronized(lifecycleLock) {
        if (!isCurrentCore(session, handle)) return@synchronized false
        var applied = false
        TunnelState.update { state ->
            if (isCurrentCore(session, handle) && shouldPublish(state)) {
                applied = true
                transform(state.copy(status = status, detail = detail, networkId = networkIdText))
            } else {
                applied = false
                state
            }
        }
        if (applied) {
            DiagnosticLog.append("$status: $detail")
            scheduleNotificationUpdate(session)
        }
        applied
    }

    private fun publishForSession(
        session: Long,
        handle: Long?,
        status: String,
        detail: String,
        transform: (TunnelUiState) -> TunnelUiState = { it },
    ): Boolean = synchronized(lifecycleLock) {
        val current = if (handle == null) isCurrentSession(session) else isCurrentCore(session, handle)
        if (!current) return@synchronized false
        TunnelState.update { state ->
            val stillCurrent = if (handle == null) isCurrentSession(session) else isCurrentCore(session, handle)
            if (stillCurrent) transform(state.copy(status = status, detail = detail, networkId = networkIdText)) else state
        }
        DiagnosticLog.append("$status: $detail")
        scheduleNotificationUpdate(session)
        true
    }

    private fun updateStateForCore(
        session: Long,
        handle: Long,
        transform: (TunnelUiState) -> TunnelUiState,
    ): Boolean = synchronized(lifecycleLock) {
        if (!isCurrentCore(session, handle)) return@synchronized false
        var applied = false
        TunnelState.update { state ->
            if (isCurrentCore(session, handle)) {
                applied = true
                transform(state)
            } else {
                applied = false
                state
            }
        }
        applied
    }

    private fun setConfigForCore(session: Long, handle: Long, value: NetworkConfig?): Boolean =
        synchronized(lifecycleLock) {
            if (!isCurrentCore(session, handle)) return@synchronized false
            config = value
            true
        }

    private fun publish(status: String, detail: String, transform: (TunnelUiState) -> TunnelUiState = { it }) {
        synchronized(lifecycleLock) {
            TunnelState.update { transform(it.copy(status = status, detail = detail, networkId = networkIdText)) }
            DiagnosticLog.append("$status: $detail")
            scheduleNotificationUpdate(sessionGeneration.get())
        }
    }

    private fun scheduleNotificationUpdate(session: Long) {
        mainHandler.post {
            if (sessionGeneration.get() != session || stopping.get()) return@post
            updateNotification(TunnelState.state.value.status)
        }
    }

    private fun updateNotification(text: String) {
        val manager = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= 26) createChannel(manager)
        manager.notify(NOTIFICATION_ID, notification(text))
    }

    private fun notification(text: String): Notification {
        val launch = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_warning)
            .setContentTitle("Lunavane")
            .setContentText(text)
            .setContentIntent(launch)
            .setOngoing(!stopping.get())
            .build()
    }

    private fun createChannel(manager: NotificationManager) {
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "ZeroTier tunnel", NotificationManager.IMPORTANCE_LOW),
        )
    }

    @Synchronized
    private fun stopTunnel(keepForeground: Boolean = false, expectedSession: Long? = null) {
        cancelPendingNetworkSwitchReconnect()
        networkSwitchDebouncePending.set(false)
        val handleToStop = synchronized(lifecycleLock) {
            if (expectedSession != null && sessionGeneration.get() != expectedSession) return
            sessionGeneration.incrementAndGet()
            selectedNetwork = null
            lastBoundNetwork = null
            activeUnderlayWasLost = false
            networkSwitchRestartPending.set(false)
            if (!stopping.compareAndSet(false, true)) null else coreHandle.take()
        }
        synchronized(networkSwitchStateLock) {
            hasObservedCandidateKey = false
            lastObservedCandidateKey = null
        }
        if (handleToStop == null) {
            if (!keepForeground) stopSelf()
            return
        }
        clearUnderlyingNetworksAfterStop()
        networkRecovery?.stop()
        networkRecovery = null
        val manager = getSystemService(ConnectivityManager::class.java)
        val callback = synchronized(networkCallbackLock) {
            val current = networkCallback
            networkCallback = null
            current
        }
        runCatching { callback?.let(manager::unregisterNetworkCallback) }
        synchronized(networkCandidateLock) {
            networkCandidates.clear()
            networkObservations.clear()
        }
        synchronized(udpSocketLock) {
            selectedNetwork = null
            lastBindFallbackNanos = 0L
            sessionBoundNetworks.clear()
            closeUdpSocketLocked()
        }
        closeTunInterface()
        config = null
        arpTable.clear()
        synchronized(pendingIpv4) {
            pendingIpv4.clear()
            pendingIpv4Count = 0
            pendingIpv4Bytes = 0
        }
        neighborTable.clear()
        synchronized(pendingIpv6) {
            pendingIpv6.clear()
            pendingIpv6Count = 0
            pendingIpv6Bytes = 0
        }
        synchronized(lastNeighborSolicitation) {
            lastNeighborSolicitation.clear()
        }
        multicastSubscribed.clear()
        packetDiagnosticKeys.clear()
        lastWireFailureCategory = null
        val networkToLeave = networkIdText
        executeCoreTask {
            if (handleToStop != 0L) {
                if (networkToLeave.length == 16) NativeCore.leave(handleToStop, networkToLeave)
                NativeCore.destroy(handleToStop)
                coreHandle.clearIf(handleToStop)
            }
        }
        synchronized(lifecycleLock) { TunnelState.reset() }
        DiagnosticLog.append("Tunnel stopped")
        if (!keepForeground) {
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
    }

    override fun onRevoke() {
        stopTunnel()
        super.onRevoke()
    }

    override fun onDestroy() {
        stopTunnel()
        networkConfigExecutor.shutdown()
        networkAttemptExecutor.shutdownNow()
        coreExecutor.shutdown()
        packetExecutor.shutdown()
        recoveryExecutor.shutdownNow()
        super.onDestroy()
    }

    private fun u32(bytes: ByteArray, offset: Int): Long =
        ByteBuffer.wrap(bytes, offset, 4).order(ByteOrder.BIG_ENDIAN).int.toLong() and 0xffffffffL

    private fun u32Bytes(value: Long): ByteArray = ByteBuffer.allocate(4).order(ByteOrder.BIG_ENDIAN).putInt(value.toInt()).array()

    private fun u48(bytes: ByteArray, offset: Int): Long =
        (0 until 6).fold(0L) { value, index -> (value shl 8) or (bytes[offset + index].toLong() and 0xff) }

    private fun macBytes(mac: Long): ByteArray = ByteArray(6) { index -> (mac ushr ((5 - index) * 8)).toByte() }

    private fun ipv4MulticastMac(packet: ByteArray, offset: Int): Long =
        IPV4_MULTICAST_BASE or (((packet[offset + 1].toLong() and 0x7f) shl 16) or
                ((packet[offset + 2].toLong() and 0xff) shl 8) or (packet[offset + 3].toLong() and 0xff))

    private fun ipv6MulticastMac(address: ByteArray): Long =
        IPV6_MULTICAST_BASE or ((address[12].toLong() and 0xff) shl 24) or
                ((address[13].toLong() and 0xff) shl 16) or
                ((address[14].toLong() and 0xff) shl 8) or (address[15].toLong() and 0xff)
}
