package nova.lunavane

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.VpnService
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.core.content.ContextCompat
import androidx.core.content.edit
import androidx.lifecycle.lifecycleScope
import nova.lunavane.ui.AppearanceSettings
import nova.lunavane.ui.LunavaneApp
import nova.lunavane.ui.NovaTheme
import nova.lunavane.ui.ThemeMode
import nova.lunavane.ui.isValidMoonSeed
import nova.lunavane.ui.isValidMoonWorldId
import nova.lunavane.vpn.ZeroTierVpnService
import nova.lunavane.vpn.stopRequestCompleted
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update

class MainActivity : ComponentActivity() {
    private companion object {
        const val PREFERENCES = "nova"
        const val KEY_THEME_MODE = "appearanceMode"
        const val KEY_DYNAMIC_COLOR = "dynamicColor"
        const val ACTION_TIMEOUT_MILLIS = 10_000L
        val operationIds = AtomicLong()
    }

    private enum class PendingAction {
        CONNECT,
        DISCONNECT,
    }

    private val preferences by lazy { getSharedPreferences(PREFERENCES, MODE_PRIVATE) }
    private val savedNetworkId = MutableStateFlow("")
    private val savedNetworks = MutableStateFlow(NetworkProfileState())
    private val pendingAction = MutableStateFlow<PendingAction?>(null)
    private val pendingConnectRequestId = MutableStateFlow<Long?>(null)
    private val pendingConnectNetworkId = MutableStateFlow("")
    private val deletingNetworkId = MutableStateFlow("")
    private val permissionPending = MutableStateFlow(false)
    private val permissionDenied = MutableStateFlow(false)
    private val appearance = MutableStateFlow(AppearanceSettings())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val networkState = NetworkProfileStore.read(this)
        savedNetworks.value = networkState
        savedNetworkId.value = networkState.selectedNetworkId
        appearance.value = readAppearance()
        enableEdgeToEdge()
        TunnelState.update { it.copy(moonSeeds = MoonStore.read(this)) }
        setContent {
            val appearanceState by appearance.collectAsState()
            NovaTheme(
                themeMode = appearanceState.themeMode,
                dynamicColor = appearanceState.dynamicColor,
            ) {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background,
                ) {
                    val state by TunnelState.state.collectAsState()
                    val diagnostics by DiagnosticLog.entries.collectAsState()
                    val savedId by savedNetworkId.collectAsState()
                    val savedNetworkState by savedNetworks.collectAsState()
                    val pending by pendingAction.collectAsState()
                    val connectRequestId by pendingConnectRequestId.collectAsState()
                    val deletingId by deletingNetworkId.collectAsState()
                    val awaitingPermission by permissionPending.collectAsState()
                    val wasPermissionDenied by permissionDenied.collectAsState()

                    LaunchedEffect(
                        state.tunnelActive,
                        state.lastError,
                        state.status,
                        state.networkId,
                        state.nodeReady,
                        state.networkState,
                        state.recoveryPhase,
                        state.connectRequestId,
                        state.coreStopComplete,
                        savedId,
                        pending,
                        connectRequestId,
                        deletingId,
                    ) {
                        when (pending) {
                            PendingAction.CONNECT -> {
                                if (connectRequestId != null && state.connectRequestId == connectRequestId) {
                                    val failed = state.needsConnectionRetry()
                                    val targetReady = state.tunnelActive && state.nodeReady &&
                                        state.networkId == savedId && state.networkState != VirtualNetworkState.IDLE
                                    if (failed || targetReady) finishAction(PendingAction.CONNECT)
                                }
                            }
                            PendingAction.DISCONNECT -> {
                                if (deletingId.isBlank() && !state.tunnelActive && state.coreStopComplete) {
                                    finishAction(PendingAction.DISCONNECT)
                                }
                            }
                            null -> Unit
                        }
                    }
                    LaunchedEffect(
                        state.tunnelActive,
                        state.networkId,
                        state.networkState,
                    ) {
                        if (state.tunnelActive && state.networkState == VirtualNetworkState.AUTHORIZED) {
                            NetworkProfileStore.markUsed(this@MainActivity, state.networkId)?.let { updated ->
                                savedNetworks.value = updated
                            }
                        }
                    }
                    LaunchedEffect(pending, awaitingPermission, deletingId) {
                        val current = pending ?: return@LaunchedEffect
                        if (awaitingPermission || (current == PendingAction.DISCONNECT && deletingId.isNotBlank())) {
                            return@LaunchedEffect
                        }
                        delay(ACTION_TIMEOUT_MILLIS)
                        if (current != PendingAction.DISCONNECT || deletingNetworkId.value.isBlank()) {
                            finishAction(current)
                        }
                    }

                    LunavaneApp(
                        state = state,
                        diagnostics = diagnostics,
                        savedNetworkId = savedId,
                        savedNetworks = savedNetworkState.profiles,
                        actionPending = pending != null,
                        connectPending = pending == PendingAction.CONNECT,
                        permissionDenied = wasPermissionDenied,
                        themeMode = appearanceState.themeMode,
                        dynamicColor = appearanceState.dynamicColor,
                        dynamicColorSupported = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S,
                        appVersion = BuildConfig.VERSION_NAME,
                        onThemeModeChange = ::setThemeMode,
                        onDynamicColorChange = ::setDynamicColor,
                        onConnect = ::requestTunnel,
                        onDisconnect = ::stopTunnel,
                        onSelectNetwork = ::selectNetwork,
                        onAddNetwork = ::addNetwork,
                        onRenameNetwork = ::renameNetwork,
                        onDeleteNetwork = ::deleteNetwork,
                        onMoveNetwork = ::moveNetwork,
                        onOrbit = ::requestOrbit,
                        onDeorbit = ::requestDeorbit,
                        onRemoveMoonLocally = ::removeMoonLocally,
                        onClearDiagnostics = DiagnosticLog::clear,
                    )
                }
            }
        }
    }

    private fun claimAction(action: PendingAction): Boolean {
        var claimed = false
        pendingAction.update { current ->
            if (current == null) {
                claimed = true
                action
            } else {
                current
            }
        }
        return claimed
    }

    private fun finishAction(expected: PendingAction? = null) {
        var finished: PendingAction? = null
        pendingAction.update { current ->
            if (expected == null || current == expected) {
                finished = current
                null
            } else {
                current
            }
        }
        if (finished == PendingAction.CONNECT) {
            pendingConnectRequestId.value = null
            pendingConnectNetworkId.value = ""
        }
    }

    private fun requestTunnel(networkId: String) {
        val normalized = normalizeNetworkId(networkId) ?: return
        if (!claimAction(PendingAction.CONNECT)) return
        pendingConnectRequestId.value = operationIds.incrementAndGet()
        pendingConnectNetworkId.value = normalized
        var profileState = NetworkProfileStore.read(this)
        if (profileState.profiles.none { it.networkId == normalized }) {
            if (NetworkProfileStore.add(this, normalized, "") == null) {
                finishAction(PendingAction.CONNECT)
                return
            }
        }
        profileState = NetworkProfileStore.select(this, normalized) ?: run {
            finishAction(PendingAction.CONNECT)
            return
        }
        applyNetworkProfileState(profileState)
        val currentTunnel = TunnelState.state.value
        if (currentTunnel.tunnelActive && currentTunnel.networkId == normalized &&
            !currentTunnel.switchingNetwork && !currentTunnel.needsConnectionRetry()
        ) {
            finishAction(PendingAction.CONNECT)
            return
        }
        permissionDenied.value = false
        if (currentTunnel.tunnelActive) {
            startTunnel(normalized)
        } else {
            requestNotificationPermissionThenVpn(normalized)
        }
    }

    private fun selectNetwork(networkId: String) {
        val updated = NetworkProfileStore.select(this, networkId) ?: return
        applyNetworkProfileState(updated)
    }

    private fun addNetwork(networkId: String, name: String): Boolean {
        val normalized = normalizeNetworkId(networkId) ?: return false
        val updated = NetworkProfileStore.add(this, normalized, name) ?: return false
        applyNetworkProfileState(updated)
        return true
    }

    private fun renameNetwork(networkId: String, name: String): Boolean {
        val updated = NetworkProfileStore.rename(this, networkId, name) ?: return false
        applyNetworkProfileState(updated)
        return true
    }

    private fun moveNetwork(networkId: String, offset: Int) {
        NetworkProfileStore.move(this, networkId, offset)?.let(::applyNetworkProfileState)
    }

    private fun deleteNetwork(networkId: String) {
        val normalized = normalizeNetworkId(networkId) ?: return
        val current = TunnelState.state.value
        if (current.tunnelActive && current.networkId == normalized) {
            if (!claimAction(PendingAction.DISCONNECT)) return
            val stopRequestId = operationIds.incrementAndGet()
            deletingNetworkId.value = normalized
            val started = runCatching {
                startService(
                    Intent(this, ZeroTierVpnService::class.java)
                        .setAction(ZeroTierVpnService.ACTION_DISCONNECT)
                        .putExtra(ZeroTierVpnService.EXTRA_STOP_REQUEST_ID, stopRequestId),
                )
            }.onFailure {
                DiagnosticLog.append("Could not stop the active network before deleting it: ${it.message}")
            }.isSuccess
            if (!started) {
                deletingNetworkId.value = ""
                finishAction(PendingAction.DISCONNECT)
                return
            }
            permissionDenied.value = false
            TunnelState.clearError()
            lifecycleScope.launch {
                val stopped = withTimeoutOrNull(ACTION_TIMEOUT_MILLIS) {
                    TunnelState.state.first {
                        stopRequestCompleted(stopRequestId, it.sessionStopCompletedRequestId)
                    }
                } != null
                if (stopped) {
                    removeSavedNetwork(normalized)
                } else {
                    DiagnosticLog.append("Timed out stopping the active network; the saved profile was kept")
                }
                deletingNetworkId.value = ""
                finishAction(PendingAction.DISCONNECT)
            }
        } else {
            removeSavedNetwork(normalized)
        }
    }

    private fun removeSavedNetwork(networkId: String) {
        val updated = NetworkProfileStore.remove(this, networkId)
        if (updated == null) {
            DiagnosticLog.append("Could not delete saved network $networkId")
        } else {
            applyNetworkProfileState(updated)
        }
    }

    private fun applyNetworkProfileState(state: NetworkProfileState) {
        savedNetworks.value = state
        savedNetworkId.value = state.selectedNetworkId
    }

    private fun requestNotificationPermissionThenVpn(networkId: String) {
        val alreadyGranted = Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED
        if (alreadyGranted) {
            requestVpnPermission(networkId)
            return
        }
        permissionPending.value = true
        val launched = runCatching {
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }.isSuccess
        if (!launched) {
            permissionPending.value = false
            DiagnosticLog.append("Could not request notification permission; continuing without notification access")
            requestVpnPermission(networkId)
        }
    }

    private fun requestVpnPermission(networkId: String) {
        if (!isValidNetworkId(networkId)) {
            finishAction(PendingAction.CONNECT)
            return
        }
        val permissionIntent = VpnService.prepare(this)
        if (permissionIntent != null) {
            permissionPending.value = true
            val launched = runCatching {
                vpnPermission.launch(permissionIntent)
            }.isSuccess
            if (!launched) {
                permissionPending.value = false
                markPermissionDenied()
                finishAction(PendingAction.CONNECT)
            }
        } else {
            startTunnel(networkId)
        }
    }

    private val notificationPermission = registerForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        permissionPending.value = false
        DiagnosticLog.append(
            if (granted) {
                "Notification permission granted"
            } else {
                "Notification permission was not granted; continuing without notification access"
            },
        )
        requestVpnPermission(pendingConnectNetworkId.value.ifBlank { savedNetworkId.value })
    }

    private val vpnPermission = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        permissionPending.value = false
        if (it.resultCode == RESULT_OK) {
            startTunnel(pendingConnectNetworkId.value.ifBlank { savedNetworkId.value })
        } else {
            markPermissionDenied()
            finishAction(PendingAction.CONNECT)
        }
    }

    private fun markPermissionDenied() {
        permissionDenied.value = true
        DiagnosticLog.append("VPN permission was not granted")
    }

    private fun startTunnel(networkId: String) {
        if (!isValidNetworkId(networkId)) {
            finishAction(PendingAction.CONNECT)
            return
        }
        val intent = Intent(this, ZeroTierVpnService::class.java)
            .setAction(ZeroTierVpnService.ACTION_CONNECT)
            .putExtra(ZeroTierVpnService.EXTRA_NETWORK_ID, networkId)
            .putExtra(
                ZeroTierVpnService.EXTRA_CONNECT_REQUEST_ID,
                pendingConnectRequestId.value ?: 0L,
            )
        val started = runCatching {
            ContextCompat.startForegroundService(this, intent)
        }.onFailure { exception ->
            val message = exception.message ?: "Unable to start the VPN service"
            DiagnosticLog.append("Could not start the VPN service: $message")
            TunnelState.update {
                it.copy(
                    status = "Failed",
                    detail = message,
                    lastError = message,
                    connectRequestId = pendingConnectRequestId.value ?: it.connectRequestId,
                    switchingNetwork = false,
                    switchingToNetworkId = "",
                )
            }
        }.isSuccess
        if (!started) finishAction(PendingAction.CONNECT)
    }

    private fun stopTunnel() {
        if (!claimAction(PendingAction.DISCONNECT)) return
        permissionDenied.value = false
        TunnelState.clearError()
        val started = runCatching {
            startService(
                Intent(this, ZeroTierVpnService::class.java)
                    .setAction(ZeroTierVpnService.ACTION_DISCONNECT),
            )
        }.onFailure {
            DiagnosticLog.append("Could not stop the VPN service: ${it.message}")
        }.isSuccess
        if (!started) finishAction(PendingAction.DISCONNECT)
    }

    private fun requestOrbit(worldId: String, seed: String) {
        if (!isValidMoonWorldId(worldId) || !isValidMoonSeed(seed)) {
            publishLocalMoonOperation(
                getString(R.string.moon_orbit_invalid),
                MoonOperation(MoonOperationType.ORBIT, MoonOperationOutcome.INVALID_INPUT),
            )
            return
        }
        if (!TunnelState.state.value.tunnelActive || !TunnelState.state.value.nodeReady) {
            val entry = MoonSeed(worldId.lowercase(), seed.lowercase())
            val stored = MoonStore.upsert(this, entry)
            publishLocalMoonOperation(
                if (stored.persisted) {
                    getString(R.string.moon_orbit_saved_pending)
                } else {
                    getString(R.string.moon_save_failed)
                },
                MoonOperation(
                    type = MoonOperationType.ORBIT,
                    outcome = offlineMoonSaveOutcome(stored.persisted),
                    worldId = entry.worldId,
                    seed = entry.seed,
                    succeeded = if (stored.persisted) 1 else 0,
                ),
                if (stored.persisted) stored.entries else MoonStore.read(this),
            )
            return
        }
        startService(
            Intent(this, ZeroTierVpnService::class.java)
                .setAction(ZeroTierVpnService.ACTION_ORBIT)
                .putExtra(ZeroTierVpnService.EXTRA_MOON_WORLD_ID, worldId)
                .putExtra(ZeroTierVpnService.EXTRA_MOON_SEED, seed),
        )
    }

    private fun requestDeorbit(worldId: String) {
        startService(
            Intent(this, ZeroTierVpnService::class.java)
                .setAction(ZeroTierVpnService.ACTION_DEORBIT)
                .putExtra(ZeroTierVpnService.EXTRA_MOON_WORLD_ID, worldId),
        )
    }

    private fun removeMoonLocally(worldId: String) {
        if (!isValidMoonWorldId(worldId)) return
        val result = MoonStore.remove(this, worldId.lowercase())
        publishLocalMoonOperation(
            if (result.persisted) {
                getString(R.string.moon_local_remove_accepted)
            } else {
                getString(R.string.moon_local_remove_failed)
            },
            MoonOperation(
                type = MoonOperationType.LOCAL_REMOVE,
                outcome = localMoonRemovalOutcome(result.persisted),
                worldId = worldId.lowercase(),
                succeeded = if (result.persisted) 1 else 0,
            ),
            if (result.persisted) result.entries else MoonStore.read(this),
        )
    }

    private fun publishLocalMoonOperation(
        message: String,
        operation: MoonOperation,
        entries: List<MoonSeed> = TunnelState.state.value.moonSeeds,
    ) {
        TunnelState.update {
            it.copy(
                moonSeeds = entries,
                moonMessage = message,
                moonOperation = operation,
            )
        }
        DiagnosticLog.append("Moon: $message")
    }

    private fun readAppearance(): AppearanceSettings {
        val savedMode = preferences.getString(KEY_THEME_MODE, null)
        val themeMode = ThemeMode.entries.firstOrNull { it.name == savedMode } ?: ThemeMode.SYSTEM
        return AppearanceSettings(
            themeMode = themeMode,
            dynamicColor = preferences.getBoolean(KEY_DYNAMIC_COLOR, true),
        )
    }

    private fun setThemeMode(mode: ThemeMode) {
        preferences.edit { putString(KEY_THEME_MODE, mode.name) }
        appearance.update { it.copy(themeMode = mode) }
    }

    private fun setDynamicColor(enabled: Boolean) {
        preferences.edit { putBoolean(KEY_DYNAMIC_COLOR, enabled) }
        appearance.update { it.copy(dynamicColor = enabled) }
    }
}
