package nova.lunavane.ui

import nova.lunavane.NodeState
import nova.lunavane.TunnelUiState
import nova.lunavane.VirtualNetworkState
import nova.lunavane.vpn.NetworkRecoveryPhase

internal enum class ConnectionStatus {
    DISCONNECTED,
    CONNECTING,
    WAITING_FOR_AUTHORIZATION,
    CONNECTED,
    SWITCHING_NETWORK,
    RECOVERING,
    RECOVERY_FAILED,
    ERROR,
}

private val failureStatuses = setOf("Failed", "Tunnel error", "Configuration error")

private val fatalNetworkStates = setOf(
    VirtualNetworkState.ACCESS_DENIED,
    VirtualNetworkState.NOT_FOUND,
    VirtualNetworkState.PORT_ERROR,
    VirtualNetworkState.CLIENT_TOO_OLD,
    VirtualNetworkState.AUTH_REQUIRED,
    VirtualNetworkState.JOIN_FAILED,
    VirtualNetworkState.CONFIG_ERROR,
)

/**
 * Derives the user-visible connection state from the service state. Only an authorized
 * virtual network with a ready transport counts as connected; a running tunnel alone
 * is not enough.
 */
internal fun deriveConnectionStatus(state: TunnelUiState): ConnectionStatus {
    if (!state.tunnelActive) {
        return if (state.lastError != null || state.status in failureStatuses) {
            ConnectionStatus.ERROR
        } else {
            ConnectionStatus.DISCONNECTED
        }
    }
    if (state.nodeState == NodeState.FAILED) return ConnectionStatus.ERROR
    if (state.networkState in fatalNetworkStates) return ConnectionStatus.ERROR
    if (state.status in failureStatuses) return ConnectionStatus.ERROR
    if (state.recoveryPhase == NetworkRecoveryPhase.EXHAUSTED) return ConnectionStatus.RECOVERY_FAILED
    if (state.networkState == VirtualNetworkState.PENDING) {
        return ConnectionStatus.WAITING_FOR_AUTHORIZATION
    }
    if (state.networkState != VirtualNetworkState.AUTHORIZED) {
        return ConnectionStatus.CONNECTING
    }
    return when (state.recoveryPhase) {
        NetworkRecoveryPhase.SWITCHING -> ConnectionStatus.SWITCHING_NETWORK
        NetworkRecoveryPhase.RECOVERING,
        NetworkRecoveryPhase.RETRY_WAIT,
        NetworkRecoveryPhase.WAITING_FOR_NETWORK,
        -> ConnectionStatus.RECOVERING
        NetworkRecoveryPhase.EXHAUSTED -> ConnectionStatus.RECOVERY_FAILED
        NetworkRecoveryPhase.READY,
        NetworkRecoveryPhase.STOPPED,
        -> if (state.status == "Degraded" || state.status == "Offline") {
            ConnectionStatus.RECOVERING
        } else {
            ConnectionStatus.CONNECTED
        }
    }
}

internal val ConnectionStatus.isBusy: Boolean
    get() = this == ConnectionStatus.CONNECTING ||
        this == ConnectionStatus.WAITING_FOR_AUTHORIZATION ||
        this == ConnectionStatus.SWITCHING_NETWORK ||
        this == ConnectionStatus.RECOVERING

internal val ConnectionStatus.isFailure: Boolean
    get() = this == ConnectionStatus.ERROR || this == ConnectionStatus.RECOVERY_FAILED
