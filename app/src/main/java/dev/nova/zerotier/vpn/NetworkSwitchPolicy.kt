package dev.nova.zerotier.vpn

internal fun shouldRestartTunnelForNetworkSwitch(
    lastBoundNetworkKey: String?,
    activeNetworkKey: String?,
    candidateNetworkKey: String?,
): Boolean {
    val currentUnderlayKey = activeNetworkKey ?: lastBoundNetworkKey ?: return false
    return candidateNetworkKey != null && candidateNetworkKey != currentUnderlayKey
}

internal fun hasCandidateKeyChanged(
    previousKey: String?,
    candidateKey: String?,
    hasPreviousObservation: Boolean,
): Boolean = hasPreviousObservation && previousKey != candidateKey
