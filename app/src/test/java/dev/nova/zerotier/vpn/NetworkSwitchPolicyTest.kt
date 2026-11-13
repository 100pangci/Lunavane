package dev.nova.zerotier.vpn

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NetworkSwitchPolicyTest {
    @Test
    fun initialCandidateDoesNotTriggerRestart() {
        assertFalse(shouldRestartTunnelForNetworkSwitch(null, null, "network-a"))
    }

    @Test
    fun initialCandidateObservationDoesNotCountAsNetworkSwitch() {
        assertFalse(hasCandidateKeyChanged(null, "candidate-a", false))
    }

    @Test
    fun repeatedCandidateObservationDoesNotCountAsNetworkSwitch() {
        assertFalse(hasCandidateKeyChanged("candidate-a", "candidate-a", true))
    }

    @Test
    fun candidateReturningAfterGapCountsAsNetworkSwitch() {
        assertTrue(hasCandidateKeyChanged(null, "candidate-a", true))
    }

    @Test
    fun unchangedNetworkDoesNotTriggerRestart() {
        assertFalse(shouldRestartTunnelForNetworkSwitch("network-a", "network-a", "network-a"))
    }

    @Test
    fun candidateMatchingActiveSocketDoesNotRestartEvenIfLastBoundNetworkDiffers() {
        assertFalse(shouldRestartTunnelForNetworkSwitch("network-a", "network-b", "network-b"))
    }

    @Test
    fun preferredNetworkDifferentFromFallbackSocketTriggersRestart() {
        assertTrue(shouldRestartTunnelForNetworkSwitch("network-b", "network-b", "network-a"))
    }

    @Test
    fun changedCandidateRestartsWhenActiveSocketMovedAwayFromLastBoundNetwork() {
        assertTrue(shouldRestartTunnelForNetworkSwitch("network-a", "network-b", "network-a"))
    }

    @Test
    fun changedCandidateRestartsWhenOldSocketIsAlreadyGone() {
        assertTrue(shouldRestartTunnelForNetworkSwitch("network-a", null, "network-b"))
    }

    @Test
    fun missingCandidateDoesNotTriggerRestart() {
        assertFalse(shouldRestartTunnelForNetworkSwitch("network-a", "network-a", null))
    }
}
