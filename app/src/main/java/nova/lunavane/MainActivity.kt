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
import nova.lunavane.ui.AppearanceSettings
import nova.lunavane.ui.LunavaneApp
import nova.lunavane.ui.NovaTheme
import nova.lunavane.ui.ThemeMode
import nova.lunavane.ui.isValidNetworkId
import nova.lunavane.ui.sanitizeHexInput
import nova.lunavane.vpn.ZeroTierVpnService
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update

class MainActivity : ComponentActivity() {
    private companion object {
        const val PREFERENCES = "nova"
        const val KEY_NETWORK_ID = "networkId"
        const val KEY_THEME_MODE = "appearanceMode"
        const val KEY_DYNAMIC_COLOR = "dynamicColor"
        const val ACTION_TIMEOUT_MILLIS = 10_000L
    }

    private enum class PendingAction {
        CONNECT,
        DISCONNECT,
    }

    private val preferences by lazy { getSharedPreferences(PREFERENCES, MODE_PRIVATE) }
    private val savedNetworkId = MutableStateFlow("")
    private val pendingAction = MutableStateFlow<PendingAction?>(null)
    private val permissionPending = MutableStateFlow(false)
    private val permissionDenied = MutableStateFlow(false)
    private val appearance = MutableStateFlow(AppearanceSettings())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        savedNetworkId.value = preferences.getString(KEY_NETWORK_ID, "").orEmpty()
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
                    val pending by pendingAction.collectAsState()
                    val awaitingPermission by permissionPending.collectAsState()
                    val wasPermissionDenied by permissionDenied.collectAsState()

                    LaunchedEffect(state.tunnelActive, state.lastError, pending) {
                        when (pending) {
                            PendingAction.CONNECT -> {
                                if (state.tunnelActive || state.lastError != null) {
                                    finishAction(PendingAction.CONNECT)
                                }
                            }
                            PendingAction.DISCONNECT -> {
                                if (!state.tunnelActive) {
                                    finishAction(PendingAction.DISCONNECT)
                                }
                            }
                            null -> Unit
                        }
                    }
                    LaunchedEffect(pending, awaitingPermission) {
                        val current = pending ?: return@LaunchedEffect
                        if (awaitingPermission) return@LaunchedEffect
                        delay(ACTION_TIMEOUT_MILLIS)
                        finishAction(current)
                    }

                    LunavaneApp(
                        state = state,
                        diagnostics = diagnostics,
                        savedNetworkId = savedId,
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
                        onOrbit = ::requestOrbit,
                        onDeorbit = ::requestDeorbit,
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
        pendingAction.update { current ->
            if (expected == null || current == expected) null else current
        }
    }

    private fun requestTunnel(networkId: String) {
        val normalized = sanitizeHexInput(networkId, 16)
        if (!isValidNetworkId(normalized)) return
        if (!claimAction(PendingAction.CONNECT)) return
        permissionDenied.value = false
        TunnelState.clearError()
        preferences.edit { putString(KEY_NETWORK_ID, normalized) }
        savedNetworkId.value = normalized
        requestNotificationPermissionThenVpn(normalized)
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
        requestVpnPermission(preferences.getString(KEY_NETWORK_ID, "").orEmpty())
    }

    private val vpnPermission = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        permissionPending.value = false
        if (it.resultCode == RESULT_OK) {
            startTunnel(preferences.getString(KEY_NETWORK_ID, "").orEmpty())
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
        val started = runCatching {
            ContextCompat.startForegroundService(this, intent)
        }.onFailure {
            DiagnosticLog.append("Could not start the VPN service: ${it.message}")
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
