package nova.lunavane.ui

import nova.lunavane.TunnelUiState
import nova.lunavane.VirtualNetworkState
import nova.lunavane.hasConnectionError
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

/**
 * Derives the user-visible connection state from the service state. Only an authorized
 * virtual network with a ready transport counts as connected; a running tunnel alone
 * is not enough.
 */
internal fun deriveConnectionStatus(state: TunnelUiState): ConnectionStatus {
    if (state.hasConnectionError()) return ConnectionStatus.ERROR
    if (state.tunnelActive && state.recoveryPhase == NetworkRecoveryPhase.EXHAUSTED) {
        return ConnectionStatus.RECOVERY_FAILED
    }
    if (state.switchingNetwork) return ConnectionStatus.SWITCHING_NETWORK
    if (!state.tunnelActive) return ConnectionStatus.DISCONNECTED
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
