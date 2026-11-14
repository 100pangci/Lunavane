package nova.lunavane

import nova.lunavane.vpn.NetworkRecoveryPhase

private val connectionFailureStatuses = setOf("Failed", "Tunnel error", "Configuration error")

internal val fatalVirtualNetworkStates = setOf(
    VirtualNetworkState.ACCESS_DENIED,
    VirtualNetworkState.NOT_FOUND,
    VirtualNetworkState.PORT_ERROR,
    VirtualNetworkState.CLIENT_TOO_OLD,
    VirtualNetworkState.AUTH_REQUIRED,
    VirtualNetworkState.JOIN_FAILED,
    VirtualNetworkState.CONFIG_ERROR,
)

internal fun TunnelUiState.hasConnectionError(): Boolean =
    lastError != null || status in connectionFailureStatuses ||
        (tunnelActive && (nodeState == NodeState.FAILED || networkState in fatalVirtualNetworkStates))

internal fun TunnelUiState.needsConnectionRetry(): Boolean =
    hasConnectionError() || (tunnelActive && (
        recoveryPhase == NetworkRecoveryPhase.EXHAUSTED || status == "Degraded" || status == "Offline"
        ))
