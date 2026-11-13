package dev.nova.zerotier

import dev.nova.zerotier.vpn.NetworkRecoveryPhase
import dev.nova.zerotier.vpn.PeerInfo
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

internal enum class NodeState {
    IDLE,
    STARTING,
    READY,
    FAILED,
}

internal enum class VirtualNetworkState {
    IDLE,
    JOINING,
    AUTHORIZED,
    PENDING,
    ACCESS_DENIED,
    NOT_FOUND,
    PORT_ERROR,
    CLIENT_TOO_OLD,
    AUTH_REQUIRED,
    JOIN_FAILED,
    LEFT,
    CONFIG_ERROR,
}

internal data class TunnelUiState(
    val status: String = "Disconnected",
    val detail: String = "",
    val tunnelActive: Boolean = false,
    val nodeReady: Boolean = false,
    val nodeAddress: String = "",
    val networkId: String = "",
    val networkName: String = "",
    val assignedAddresses: List<String> = emptyList(),
    val activeRoutes: List<String> = emptyList(),
    val moonSeeds: List<MoonSeed> = emptyList(),
    val moonMessage: String = "",
    val peers: List<PeerInfo> = emptyList(),
    val peersQueried: Boolean = false,
    val nodeState: NodeState = NodeState.IDLE,
    val networkState: VirtualNetworkState = VirtualNetworkState.IDLE,
    val recoveryPhase: NetworkRecoveryPhase = NetworkRecoveryPhase.WAITING_FOR_NETWORK,
    val recoveryPhaseSequence: Long = 0L,
)

internal object TunnelState {
    private val mutableState = MutableStateFlow(TunnelUiState())
    val state = mutableState.asStateFlow()

    fun update(transform: (TunnelUiState) -> TunnelUiState) = mutableState.update(transform)
    fun reset() {
        mutableState.update { current ->
            TunnelUiState(moonSeeds = current.moonSeeds, moonMessage = current.moonMessage)
        }
    }
}
