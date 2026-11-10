package dev.nova.zerotier

import android.content.Intent
import android.net.VpnService
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.core.content.ContextCompat
import dev.nova.zerotier.ui.HomeScreen
import dev.nova.zerotier.ui.NovaTheme
import dev.nova.zerotier.vpn.ZeroTierVpnService

class MainActivity : ComponentActivity() {
    private val preferences by lazy { getSharedPreferences("nova", MODE_PRIVATE) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        TunnelState.update { it.copy(moonSeeds = MoonStore.read(this)) }
        setContent {
            NovaTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background,
                ) {
                    NovaApp(
                        initialNetworkId = preferences.getString("networkId", "").orEmpty(),
                        onConnect = ::requestTunnel,
                        onDisconnect = ::stopTunnel,
                        onOrbit = ::requestOrbit,
                        onDeorbit = ::requestDeorbit,
                    )
                }
            }
        }
    }

    private fun requestTunnel(networkId: String) {
        preferences.edit().putString("networkId", networkId).apply()
        val permissionIntent = VpnService.prepare(this)
        if (permissionIntent != null) {
            vpnPermission.launch(permissionIntent)
        } else {
            startTunnel(networkId)
        }
    }

    private val vpnPermission = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        if (it.resultCode == RESULT_OK) {
            startTunnel(preferences.getString("networkId", "").orEmpty())
        }
    }

    private fun startTunnel(networkId: String) {
        val intent = Intent(this, ZeroTierVpnService::class.java)
            .setAction(ZeroTierVpnService.ACTION_CONNECT)
            .putExtra(ZeroTierVpnService.EXTRA_NETWORK_ID, networkId)
        ContextCompat.startForegroundService(this, intent)
    }

    private fun stopTunnel() {
        startService(Intent(this, ZeroTierVpnService::class.java).setAction(ZeroTierVpnService.ACTION_DISCONNECT))
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
}

@Composable
private fun NovaApp(
    initialNetworkId: String,
    onConnect: (String) -> Unit,
    onDisconnect: () -> Unit,
    onOrbit: (String, String) -> Unit,
    onDeorbit: (String) -> Unit,
) {
    val state by TunnelState.state.collectAsState()
    val diagnostics by DiagnosticLog.entries.collectAsState()
    HomeScreen(
        state = state,
        initialNetworkId = initialNetworkId,
        onConnect = onConnect,
        onDisconnect = onDisconnect,
        onOrbit = onOrbit,
        onDeorbit = onDeorbit,
        diagnostics = diagnostics,
        onClearDiagnostics = DiagnosticLog::clear,
    )
}
