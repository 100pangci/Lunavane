package nova.lunavane

import nova.lunavane.vpn.NetworkRecoveryPhase
import nova.lunavane.vpn.PeerInfo
import nova.lunavane.vpn.UnderlayTransport
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

internal enum class MoonOperationType {
    ORBIT,
    DEORBIT,
    RESTORE,
}

internal enum class MoonOperationOutcome {
    ACCEPTED,
    ACCEPTED_NOT_SAVED,
    PARTIAL,
    REJECTED,
    UNAVAILABLE,
    INVALID_INPUT,
    FILE_REMAINS,
    FILE_STATUS_UNKNOWN,
    RECORD_REMOVE_FAILED,
}

/**
 * Structured result of the most recent Moon operation. The raw [TunnelUiState.moonMessage]
 * remains available for diagnostics, while this value lets the UI localize the outcome
 * without parsing service-generated text.
 */
internal data class MoonOperation(
    val type: MoonOperationType,
    val outcome: MoonOperationOutcome,
    val worldId: String = "",
    val seed: String = "",
    val succeeded: Int = 0,
    val failed: Int = 0,
    val coreCode: Int? = null,
)

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
    val dnsServers: List<String> = emptyList(),
    val dnsDomain: String = "",
    val moonSeeds: List<MoonSeed> = emptyList(),
    val moonMessage: String = "",
    val moonOperation: MoonOperation? = null,
    val peers: List<PeerInfo> = emptyList(),
    val peersQueried: Boolean = false,
    val nodeState: NodeState = NodeState.IDLE,
    val networkState: VirtualNetworkState = VirtualNetworkState.IDLE,
    val recoveryPhase: NetworkRecoveryPhase = NetworkRecoveryPhase.WAITING_FOR_NETWORK,
    val recoveryPhaseSequence: Long = 0L,
    val underlayTransport: UnderlayTransport? = null,
    val lastError: String? = null,
)

internal object TunnelState {
    private val mutableState = MutableStateFlow(TunnelUiState())
    val state = mutableState.asStateFlow()

    fun update(transform: (TunnelUiState) -> TunnelUiState) = mutableState.update(transform)

    fun clearError() = mutableState.update { it.copy(lastError = null) }

    fun reset() {
        mutableState.update { current ->
            TunnelUiState(
                moonSeeds = current.moonSeeds,
                moonMessage = current.moonMessage,
                moonOperation = current.moonOperation,
                lastError = current.lastError,
            )
        }
    }
}
