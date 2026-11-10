package dev.nova.zerotier.vpn

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.net.ConnectivityManager
import android.net.Network
import android.net.VpnService
import android.os.Build
import android.os.ParcelFileDescriptor
import androidx.core.app.NotificationCompat
import dev.nova.zerotier.DiagnosticLog
import dev.nova.zerotier.MainActivity
import dev.nova.zerotier.MoonSeed
import dev.nova.zerotier.MoonStore
import dev.nova.zerotier.TunnelState
import dev.nova.zerotier.TunnelUiState
import dev.nova.zerotier.core.NativeCallbacks
import dev.nova.zerotier.core.NativeCore
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.util.ArrayDeque
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlin.concurrent.thread

class ZeroTierVpnService : VpnService(), NativeCallbacks {
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
    }

    private val stopping = AtomicBoolean(false)
    private val sessionGeneration = AtomicLong(0)
    private val tunOutputLock = Any()
    private val udpSocketLock = Any()
    private val coreExecutor = Executors.newSingleThreadExecutor()
    private val packetExecutor = Executors.newSingleThreadExecutor()
    private val arpTable = ConcurrentHashMap<Long, Long>()
    private val pendingIpv4 = mutableMapOf<Long, ArrayDeque<ByteArray>>()
    private var pendingIpv4Count = 0
    private val neighborTable = ConcurrentHashMap<String, Long>()
    private val pendingIpv6 = mutableMapOf<String, ArrayDeque<ByteArray>>()
    private var pendingIpv6Count = 0
    private val lastNeighborSolicitation = ConcurrentHashMap<String, Long>()
    private val multicastSubscribed = ConcurrentHashMap.newKeySet<Long>()
    private val packetDiagnosticKeys = ConcurrentHashMap.newKeySet<String>()
    @Volatile private var networkIdText = ""
    @Volatile private var networkId = 0L
    @Volatile private var coreHandle = 0L
    @Volatile private var tun: ParcelFileDescriptor? = null
    @Volatile private var tunInput: FileInputStream? = null
    @Volatile private var tunOutput: FileOutputStream? = null
    @Volatile private var udp: DatagramSocket? = null
    @Volatile private var selectedNetwork: Network? = null
    @Volatile private var socketNetwork: Network? = null
    @Volatile private var config: NetworkConfig? = null
    @Volatile private var tunReader: Thread? = null
    @Volatile private var udpReader: Thread? = null
    @Volatile private var networkCallback: ConnectivityManager.NetworkCallback? = null

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
                stopping.set(false)
                publish("Starting", "Initializing ZeroTier node") { it.copy(tunnelActive = true) }
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
        val session = sessionGeneration.incrementAndGet()
        coreExecutor.execute {
            try {
                val handle = NativeCore.create(SessionCallbacks(session), filesDir.resolve("zerotier").absolutePath)
                if (handle == 0L) error("ZeroTier core initialization failed")
                if (!isCurrentSession(session)) {
                    NativeCore.destroy(handle)
                    return@execute
                }
                coreHandle = handle
                TunnelState.update { current ->
                    if (isCurrentSession(session)) current.copy(nodeReady = true) else current
                }
                restoreMoonSeeds(handle, session)
                observeNetworks(session)
                selectedNetwork?.let(::openUdpSocket)
                if (!isCurrentSession(session)) return@execute
                NativeCore.join(coreHandle, networkIdText)
                if (isCurrentSession(session) && TunnelState.state.value.status == "Starting") {
                    publish("Requesting configuration", "Joining ZeroTier network")
                }
            } catch (exception: Exception) {
                if (isCurrentSession(session)) {
                    publish("Failed", exception.cause?.message ?: exception.message ?: "Unable to start the tunnel")
                    stopTunnel()
                }
            }
        }
    }

    private fun isCurrentSession(session: Long): Boolean =
        !stopping.get() && sessionGeneration.get() == session

    private fun restoreMoonSeeds(handle: Long, session: Long) {
        if (!isCurrentSession(session)) return
        val entries = MoonStore.read(this)
        TunnelState.update { it.copy(moonSeeds = entries) }
        if (entries.isEmpty()) return
        var failed = 0
        for (entry in entries) {
            if (!isCurrentSession(session)) return
            if (NativeCore.orbit(handle, entry.worldId, entry.seed) != 0) failed++
        }
        if (!isCurrentSession(session)) return
        val message = if (failed == 0) {
            "Restored ${entries.size} saved Moon orbit request(s)."
        } else {
            "Restored ${entries.size - failed} Moon request(s); $failed could not be submitted."
        }
        publishMoonStatus(message, entries)
    }

    private fun requestOrbit(moonWorldId: String, moonSeed: String, startId: Int) {
        val handle = coreHandle
        if (stopping.get() || handle == 0L) {
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
        coreExecutor.execute {
            if (stopping.get() || coreHandle != handle) {
                publishMoonStatus("Moon request canceled because the node stopped.")
                return@execute
            }
            val result = NativeCore.orbit(handle, entry.worldId, entry.seed)
            if (result == 0) {
                val entries = MoonStore.upsert(this, entry)
                publishMoonStatus("Orbit request saved. Core will query the seed for the Moon definition.", entries)
            } else {
                publishMoonStatus("Core could not submit the Moon orbit request (code $result).")
            }
        }
    }

    private fun requestDeorbit(moonWorldId: String, startId: Int) {
        val handle = coreHandle
        if (stopping.get() || handle == 0L) {
            publishMoonStatus("Connect the ZeroTier node before deorbiting a Moon.")
            if (handle == 0L) stopSelf(startId)
            return
        }
        if (!isValidHex(moonWorldId, 16) || moonWorldId.toULong(16) == 0uL) {
            publishMoonStatus("Enter a valid 16-digit Moon ID.")
            return
        }
        val worldId = moonWorldId.lowercase()
        coreExecutor.execute {
            if (stopping.get() || coreHandle != handle) {
                publishMoonStatus("Moon request canceled because the node stopped.")
                return@execute
            }
            val result = NativeCore.deorbit(handle, worldId)
            if (result == 0) {
                val entries = MoonStore.remove(this, worldId)
                publishMoonStatus("Deorbit request submitted and saved Moon data removed.", entries)
            } else {
                publishMoonStatus("Core could not submit the Moon deorbit request (code $result).")
            }
        }
    }

    private fun isValidHex(value: String, length: Int): Boolean =
        value.length == length && value.all { it.digitToIntOrNull(16) != null }

    private fun publishMoonStatus(message: String, entries: List<MoonSeed> = MoonStore.read(this)) {
        TunnelState.update { it.copy(moonSeeds = entries, moonMessage = message) }
        DiagnosticLog.append("Moon: $message")
    }

    private inner class SessionCallbacks(private val session: Long) : NativeCallbacks {
        override fun onWirePacket(address: ByteArray, port: Int, packet: ByteArray) {
            if (isCurrentSession(session)) this@ZeroTierVpnService.onWirePacket(address, port, packet)
        }

        override fun onVirtualFrame(
            networkId: Long,
            sourceMac: Long,
            destinationMac: Long,
            etherType: Int,
            vlanId: Int,
            payload: ByteArray,
        ) {
            if (isCurrentSession(session)) {
                this@ZeroTierVpnService.onVirtualFrame(networkId, sourceMac, destinationMac, etherType, vlanId, payload)
            }
        }

        override fun onNetworkConfig(networkId: Long, operation: Int, status: Int, configJson: String): Int =
            if (isCurrentSession(session)) {
                this@ZeroTierVpnService.onNetworkConfig(networkId, operation, status, configJson)
            } else {
                0
            }

        override fun onNodeEvent(eventType: Int, message: String) {
            if (isCurrentSession(session)) this@ZeroTierVpnService.onNodeEvent(eventType, message)
        }
    }

    private fun openUdpSocket(network: Network?) {
        synchronized(udpSocketLock) {
            if (stopping.get() || (network != null && network != selectedNetwork)) return
            val oldSocket = udp
            if (oldSocket != null && socketNetwork == network && !oldSocket.isClosed) return
            udp = null
            socketNetwork = null
            udpReader?.interrupt()
            udpReader = null
            oldSocket?.close()

            var newSocket: DatagramSocket? = null
            try {
                val socket = DatagramSocket(null)
                newSocket = socket
                socket.reuseAddress = true
                check(protect(socket)) { "Could not protect the ZeroTier UDP socket" }
                if (network != null) {
                    try {
                        network.bindSocket(socket)
                    } catch (exception: IOException) {
                        if (network != selectedNetwork) throw exception
                        DiagnosticLog.append("Could not bind UDP socket to the default network; using its protected default route")
                    }
                }
                socket.bind(InetSocketAddress(PORT))
                udp = socket
                socketNetwork = network
                DiagnosticLog.append("ZeroTier UDP socket ready")
                udpReader = thread(name = "ZeroTier-udp") {
                    val bytes = ByteArray(MAX_DATAGRAM)
                    while (!stopping.get() && !socket.isClosed) {
                        try {
                            val packet = DatagramPacket(bytes, bytes.size)
                            socket.receive(packet)
                            val address = packet.address.address.copyOf()
                            val payload = packet.data.copyOfRange(packet.offset, packet.offset + packet.length)
                            val port = packet.port
                            coreExecutor.execute {
                                val handle = coreHandle
                                if (!stopping.get() && handle != 0L) {
                                    NativeCore.processWirePacket(handle, address, port, payload)
                                }
                            }
                        } catch (exception: IOException) {
                            if (!stopping.get() && udp === socket) {
                                publish("Degraded", "UDP receive failed: ${exception.message}")
                            }
                            break
                        }
                    }
                }
            } catch (exception: Exception) {
                newSocket?.close()
                if (!stopping.get()) publish("Degraded", "Could not open ZeroTier UDP socket: ${exception.message}")
            }
        }
    }

    private fun observeNetworks(session: Long) {
        if (!isCurrentSession(session)) return
        val manager = getSystemService(ConnectivityManager::class.java)
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                if (!isCurrentSession(session)) return
                selectedNetwork = network
                openUdpSocket(network)
            }

            override fun onLost(network: Network) {
                if (!isCurrentSession(session)) return
                val lostDefault = synchronized(udpSocketLock) {
                    if (selectedNetwork != network) return@synchronized false
                    selectedNetwork = null
                    if (socketNetwork == network) {
                        val socket = udp
                        udp = null
                        socketNetwork = null
                        udpReader?.interrupt()
                        udpReader = null
                        socket?.close()
                    }
                    true
                }
                if (lostDefault) publish("Reconnecting", "Underlying network changed")
            }
        }
        networkCallback = callback
        manager.registerDefaultNetworkCallback(callback)
    }

    override fun onNetworkConfig(networkId: Long, operation: Int, status: Int, configJson: String): Int {
        if (stopping.get() || networkId != this.networkId) return 0
        if (operation == 3 || operation == 4) {
            config = null
            closeTunInterface()
            TunnelState.update { it.copy(assignedAddresses = emptyList(), activeRoutes = emptyList()) }
            if (operation == 4) {
                publish("Disconnected", "Left ZeroTier network") {
                    it.copy(networkName = "", assignedAddresses = emptyList(), activeRoutes = emptyList())
                }
            }
            return 0
        }
        return try {
            val updated = NetworkConfig.parse(configJson)
            if (updated.status == 1) {
                val activeRoutes = effectiveRoutes(updated)
                if (requiresTunUpdate(config, updated)) {
                    applyVpnConfig(updated)
                } else {
                    config = updated
                }
                subscribeNetworkGroups(updated)
                publish("Connected", "Authorized virtual network") {
                    it.copy(
                        networkId = networkIdText,
                        networkName = updated.name,
                        assignedAddresses = updated.addresses.map { address -> "${address.address}/${address.prefix}" },
                        activeRoutes = activeRoutes.map { route ->
                            "${route.address}/${route.prefix} via ${route.gateway ?: "direct"}"
                        },
                    )
                }
            } else {
                config = null
                closeTunInterface()
                publish(statusText(updated.status), "Waiting for network authorization") {
                    it.copy(
                        networkId = networkIdText,
                        networkName = updated.name,
                        assignedAddresses = emptyList(),
                        activeRoutes = emptyList(),
                    )
                }
            }
            0
        } catch (exception: Exception) {
            config = null
            closeTunInterface()
            publish("Configuration error", exception.message ?: "Invalid network configuration") {
                it.copy(assignedAddresses = emptyList(), activeRoutes = emptyList())
            }
            22
        }
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

    private fun applyVpnConfig(config: NetworkConfig) {
        val builder = Builder()
            .setSession("Lunavane")
            .setMtu(config.mtu.coerceIn(576, 9000))
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
        synchronized(tunOutputLock) {
            closeTunInterface()
            tun = descriptor
            val input = FileInputStream(descriptor.fileDescriptor)
            tunInput = input
            tunOutput = FileOutputStream(descriptor.fileDescriptor)
            this.config = config
            tunReader = thread(name = "ZeroTier-tun") { readTunPackets(input) }
        }
        updateNotification("Connected to ${config.name.ifBlank { networkIdText }}")
    }

    private fun closeTunInterface() {
        synchronized(tunOutputLock) {
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
    }

    private fun subscribeNetworkGroups(config: NetworkConfig) {
        config.multicast.forEach { subscribe(it.mac, it.adi) }
        val ipv4Addresses = config.addresses.filterNot { it.address.contains(':') }.mapNotNull {
            runCatching { InetAddress.getByName(it.address).address }.getOrNull()
        }
        ipv4Addresses.forEach { address ->
            val adi = ByteBuffer.wrap(address).order(ByteOrder.BIG_ENDIAN).int.toLong() and 0xffffffffL
            subscribe(BROADCAST_MAC, adi)
        }
        config.addresses.filter { it.address.contains(':') }.mapNotNull { ipv6Bytes(it.address) }.forEach { address ->
            subscribe(ipv6MulticastMac(solicitedNodeAddress(address)), 0)
        }
    }

    private fun subscribe(mac: Long, adi: Long) {
        val key = (mac * 0x9e3779b97f4a7c15UL.toLong()) xor adi
        if (!multicastSubscribed.add(key)) return
        coreExecutor.execute {
            if (stopping.get()) {
                multicastSubscribed.remove(key)
                return@execute
            }
            val result = runCatching {
                NativeCore.multicastSubscribe(coreHandle, networkId, mac, adi)
            }
            if (result.getOrNull() != 0) {
                multicastSubscribed.remove(key)
                val code = result.getOrNull()?.let { " ($it)" }.orEmpty()
                DiagnosticLog.append("Multicast subscription failed$code")
            }
        }
    }

    private fun readTunPackets(input: FileInputStream) {
        val buffer = ByteArray(65536)
        while (!stopping.get()) {
            try {
                val count = input.read(buffer)
                if (count <= 0) continue
                val packet = buffer.copyOf(count)
                when ((packet[0].toInt() ushr 4) and 0xf) {
                    4 -> processIpv4Packet(packet)
                    6 -> processIpv6Packet(packet)
                }
            } catch (exception: IOException) {
                if (!stopping.get() && tunReader === Thread.currentThread()) {
                    publish("Tunnel error", exception.message ?: "TUN read failed")
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

    private fun queuePendingIpv4(nextHop: Long, packet: ByteArray) {
        synchronized(pendingIpv4) {
            if (stopping.get() || pendingIpv4Count >= MAX_PENDING_PACKETS) return
            pendingIpv4.getOrPut(nextHop) { ArrayDeque() }.addLast(packet)
            pendingIpv4Count++
        }
    }

    private fun takePendingIpv4(nextHop: Long): List<ByteArray> = synchronized(pendingIpv4) {
        val packets = pendingIpv4.remove(nextHop) ?: return@synchronized emptyList()
        pendingIpv4Count -= packets.size
        packets.toList()
    }

    private fun queuePendingIpv6(nextHop: String, packet: ByteArray) {
        synchronized(pendingIpv6) {
            if (stopping.get() || pendingIpv6Count >= MAX_PENDING_PACKETS) return
            pendingIpv6.getOrPut(nextHop) { ArrayDeque() }.addLast(packet)
            pendingIpv6Count++
        }
    }

    private fun takePendingIpv6(nextHop: String): List<ByteArray> = synchronized(pendingIpv6) {
        val packets = pendingIpv6.remove(nextHop) ?: return@synchronized emptyList()
        pendingIpv6Count -= packets.size
        packets.toList()
    }

    private fun processIpv4Packet(packet: ByteArray) {
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
            if (multicast) subscribe(destinationMac, 0)
            submitFrame(current.mac, destinationMac, IPV4_ETHER_TYPE, packet)
            return
        }

        val sourceAddress = current.addresses.firstOrNull { !it.address.contains(':') } ?: return
        val source = runCatching { InetAddress.getByName(sourceAddress.address).address }.getOrNull() ?: return
        val routeKey = route?.let { "${it.address}/${it.prefix}:${it.gateway}" } ?: "direct"
        logPacketOnce("ipv4-arp:$routeKey", "Sending ARP request for IPv4 next hop ${ipv4Text(nextHop)}")
        queuePendingIpv4(nextHop, packet)
        val arp = buildArpRequest(current.mac, source, u32Bytes(nextHop))
        submitFrame(current.mac, BROADCAST_MAC, ARP_ETHER_TYPE, arp)
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

    private fun processIpv6Packet(packet: ByteArray) {
        val current = config ?: return
        if (packet.size < 40 || packet[0].toInt() ushr 4 != 6) return
        val destination = packet.copyOfRange(24, 40)
        val multicast = destination[0].toInt() and 0xff == 0xff
        if (multicast) {
            val destinationMac = ipv6MulticastMac(destination)
            subscribe(destinationMac, 0)
            submitFrame(current.mac, destinationMac, IPV6_ETHER_TYPE, packet)
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
            submitFrame(current.mac, destinationMac, IPV6_ETHER_TYPE, packet)
            return
        }

        queuePendingIpv6(key, packet)
        val now = System.currentTimeMillis()
        synchronized(lastNeighborSolicitation) {
            if (stopping.get()) return
            val previous = lastNeighborSolicitation[key]
            if (previous == null || now - previous >= 3000) {
                val source = packet.copyOfRange(8, 24)
                val solicitation = buildNeighborSolicitation(source, nextHop, current.mac)
                val solicitationDestination = solicitedNodeAddress(nextHop)
                val multicastMac = ipv6MulticastMac(solicitationDestination)
                subscribe(multicastMac, 0)
                submitFrame(current.mac, multicastMac, IPV6_ETHER_TYPE, solicitation)
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

    private fun submitFrame(sourceMac: Long, destinationMac: Long, etherType: Int, packet: ByteArray) {
        coreExecutor.execute {
            if (!stopping.get() && coreHandle != 0L) {
                val result = NativeCore.processVirtualFrame(coreHandle, networkId, sourceMac, destinationMac, etherType, 0, packet)
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
    }

    override fun onVirtualFrame(
        networkId: Long,
        sourceMac: Long,
        destinationMac: Long,
        etherType: Int,
        vlanId: Int,
        payload: ByteArray,
    ) {
        if (networkId != this.networkId || stopping.get()) return
        when (etherType) {
            ARP_ETHER_TYPE -> handleArp(payload)
            IPV4_ETHER_TYPE -> {
                if (payload.size >= 20 && (payload[0].toInt() ushr 4) == 4) {
                    arpTable[u32(payload, 12)] = sourceMac
                    logPacketOnce("ipv4-core-inbound", "Received an IPv4 frame from ZeroTier")
                }
                writeTun(payload)
            }
            IPV6_ETHER_TYPE -> handleIpv6Frame(sourceMac, payload)
        }
    }

    private fun handleIpv6Frame(sourceMac: Long, packet: ByteArray) {
        if (packet.size < 40 || packet[0].toInt() ushr 4 != 6) return
        val source = packet.copyOfRange(8, 24)
        if (isNeighborDiscovery(packet)) {
            when (packet[40].toInt() and 0xff) {
                135 -> answerNeighborSolicitation(sourceMac, packet)
                136 -> learnNeighborAdvertisement(sourceMac, packet)
                else -> writeTun(packet)
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
                    submitFrame(current.mac, sourceMac, IPV6_ETHER_TYPE, pending)
                }
            }
        }
        writeTun(packet)
    }

    private fun answerNeighborSolicitation(sourceMac: Long, packet: ByteArray) {
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
        submitFrame(current.mac, destinationMac, IPV6_ETHER_TYPE, advertisement)
    }

    private fun learnNeighborAdvertisement(sourceMac: Long, packet: ByteArray) {
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
            submitFrame(current.mac, targetMac, IPV6_ETHER_TYPE, pending)
        }
    }

    private fun handleArp(packet: ByteArray) {
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
                submitFrame(current.mac, senderMac, IPV4_ETHER_TYPE, pending)
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
        submitFrame(current.mac, senderMac, ARP_ETHER_TYPE, reply)
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

    private fun writeTun(packet: ByteArray) {
        val generation = sessionGeneration.get()
        packetExecutor.execute {
            synchronized(tunOutputLock) {
                if (generation != sessionGeneration.get()) return@synchronized
                val ipv4 = packet.isNotEmpty() && (packet[0].toInt() ushr 4) == 4
                val output = tunOutput
                if (output == null) {
                    if (ipv4) logPacketOnce("ipv4-tun-output-unavailable", "Dropped an inbound IPv4 packet because the TUN output is unavailable")
                    return@synchronized
                }
                try {
                    output.write(packet)
                    if (ipv4) logPacketOnce("ipv4-tun-write", "Wrote an inbound IPv4 packet to the Android TUN")
                } catch (exception: IOException) {
                    if (!stopping.get()) publish("Tunnel error", exception.message ?: "TUN write failed")
                }
            }
        }
    }

    override fun onWirePacket(address: ByteArray, port: Int, packet: ByteArray) {
        val socket = udp ?: return
        if (stopping.get()) return
        try {
            socket.send(DatagramPacket(packet, packet.size, InetAddress.getByAddress(address), port))
        } catch (exception: IOException) {
            if (!stopping.get() && udp === socket) {
                publish("Degraded", "UDP send failed: ${exception.message}")
            }
        }
    }

    override fun onNodeEvent(eventType: Int, message: String) {
        when (eventType) {
            0 -> publish("Starting", "ZeroTier node initialized")
            1 -> publish("Offline", "No upstream route currently available")
            2 -> publish("Online", "ZeroTier upstream reachable")
            else -> if (message.isNotBlank()) publish("Node event", message)
        }
        if (message.matches(Regex("[0-9a-f]{10}"))) {
            publish(TunnelState.state.value.status, TunnelState.state.value.detail) {
                it.copy(nodeAddress = message)
            }
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

    private fun publish(status: String, detail: String, transform: (TunnelUiState) -> TunnelUiState = { it }) {
        TunnelState.update { transform(it.copy(status = status, detail = detail, networkId = networkIdText)) }
        DiagnosticLog.append("$status: $detail")
        updateNotification(status)
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

    private fun stopTunnel(keepForeground: Boolean = false) {
        sessionGeneration.incrementAndGet()
        if (!stopping.compareAndSet(false, true)) {
            if (!keepForeground) stopSelf()
            return
        }
        val manager = getSystemService(ConnectivityManager::class.java)
        runCatching { networkCallback?.let(manager::unregisterNetworkCallback) }
        networkCallback = null
        synchronized(udpSocketLock) {
            selectedNetwork = null
            socketNetwork = null
            val socket = udp
            udp = null
            udpReader?.interrupt()
            udpReader = null
            socket?.close()
        }
        closeTunInterface()
        config = null
        arpTable.clear()
        synchronized(pendingIpv4) {
            pendingIpv4.clear()
            pendingIpv4Count = 0
        }
        neighborTable.clear()
        synchronized(pendingIpv6) {
            pendingIpv6.clear()
            pendingIpv6Count = 0
        }
        synchronized(lastNeighborSolicitation) {
            lastNeighborSolicitation.clear()
        }
        multicastSubscribed.clear()
        packetDiagnosticKeys.clear()
        val networkToLeave = networkIdText
        coreExecutor.execute {
            val handle = coreHandle
            if (handle != 0L) {
                if (networkToLeave.length == 16) NativeCore.leave(handle, networkToLeave)
                NativeCore.destroy(handle)
                coreHandle = 0L
            }
        }
        TunnelState.reset()
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
        coreExecutor.shutdown()
        packetExecutor.shutdown()
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
