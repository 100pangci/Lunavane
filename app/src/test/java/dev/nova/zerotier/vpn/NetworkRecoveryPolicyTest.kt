package dev.nova.zerotier.vpn

import org.junit.Assert.assertEquals
import org.junit.Test

class NetworkRecoveryPolicyTest {
    @Test
    fun callbackBurstKeepsOnlyLatestCandidate() {
        val policy = NetworkRecoveryPolicy()
        val wifi = policy.onCandidateChanged("wifi:link-a") as NetworkRecoveryDecision.Debounce
        val cellular = policy.onCandidateChanged("cellular:link-b") as NetworkRecoveryDecision.Debounce

        assertEquals(NetworkRecoveryDecision.Ignored, policy.onDebounceElapsed(wifi.revision))
        assertEquals(
            NetworkRecoveryDecision.Attempt(cellular.revision, "cellular:link-b"),
            policy.onDebounceElapsed(cellular.revision),
        )
    }

    @Test
    fun duplicateCandidateDoesNotRestartDebounce() {
        val policy = NetworkRecoveryPolicy()
        val first = policy.onCandidateChanged("wifi:link-a")

        assertEquals(NetworkRecoveryDecision.Ignored, policy.onCandidateChanged("wifi:link-a"))
        assertEquals(first, NetworkRecoveryDecision.Debounce(1))
    }

    @Test
    fun retriesUseFiniteExponentialBackoff() {
        val policy = NetworkRecoveryPolicy()
        val revision = (policy.onCandidateChanged("wifi") as NetworkRecoveryDecision.Debounce).revision
        policy.onDebounceElapsed(revision)
        val delays = mutableListOf<Long>()

        repeat(6) {
            val retry = policy.onAttemptFinished(revision, succeeded = false) as NetworkRecoveryDecision.Retry
            delays += retry.delayMillis
            assertEquals(NetworkRecoveryDecision.Attempt(revision, "wifi"), policy.onRetryElapsed(retry.revision))
        }
        val result = policy.onAttemptFinished(revision, succeeded = false)

        assertEquals(listOf(1_000L, 2_000L, 4_000L, 8_000L, 16_000L, 30_000L), delays)
        assertEquals(NetworkRecoveryDecision.Exhausted(revision), result)
        assertEquals(NetworkRecoveryPhase.EXHAUSTED, policy.phase)
        assertEquals(NetworkRecoveryDecision.Ignored, policy.onTransportFailure())
    }

    @Test
    fun missingNetworkDoesNotScheduleRetries() {
        val policy = NetworkRecoveryPolicy()
        val revision = (policy.onCandidateChanged("wifi") as NetworkRecoveryDecision.Debounce).revision
        policy.onDebounceElapsed(revision)
        val noNetworkRevision = (policy.onCandidateChanged(null) as NetworkRecoveryDecision.Debounce).revision

        assertEquals(NetworkRecoveryDecision.NoNetwork(noNetworkRevision), policy.onDebounceElapsed(noNetworkRevision))
        assertEquals(NetworkRecoveryDecision.Ignored, policy.onTransportFailure())
        assertEquals(NetworkRecoveryPhase.WAITING_FOR_NETWORK, policy.phase)
    }

    @Test
    fun transportSuccessInvalidatesScheduledRetry() {
        val policy = NetworkRecoveryPolicy()
        val revision = (policy.onCandidateChanged("wifi") as NetworkRecoveryDecision.Debounce).revision
        policy.onDebounceElapsed(revision)
        val retry = policy.onAttemptFinished(revision, succeeded = false) as NetworkRecoveryDecision.Retry

        assertEquals(NetworkRecoveryDecision.Ready(revision + 1), policy.onTransportSuccess())
        assertEquals(NetworkRecoveryDecision.Ignored, policy.onRetryElapsed(retry.revision))
        assertEquals(NetworkRecoveryPhase.READY, policy.phase)
    }

    @Test
    fun stoppingInvalidatesPendingActions() {
        val policy = NetworkRecoveryPolicy()
        val pending = policy.onCandidateChanged("wifi") as NetworkRecoveryDecision.Debounce

        assertEquals(NetworkRecoveryDecision.Stopped(pending.revision + 1), policy.stop())
        assertEquals(NetworkRecoveryDecision.Ignored, policy.onDebounceElapsed(pending.revision))
        assertEquals(NetworkRecoveryPhase.STOPPED, policy.phase)
    }
}
