package nova.lunavane.vpn

internal enum class NetworkRecoveryPhase {
    WAITING_FOR_NETWORK,
    SWITCHING,
    RECOVERING,
    RETRY_WAIT,
    READY,
    EXHAUSTED,
    STOPPED,
}

internal sealed interface NetworkRecoveryDecision {
    data class Debounce(val revision: Long) : NetworkRecoveryDecision
    data class Attempt(val revision: Long, val networkKey: String) : NetworkRecoveryDecision
    data class Retry(val revision: Long, val delayMillis: Long) : NetworkRecoveryDecision
    data class NoNetwork(val revision: Long) : NetworkRecoveryDecision
    data class Ready(val revision: Long) : NetworkRecoveryDecision
    data class Exhausted(val revision: Long) : NetworkRecoveryDecision
    data object Ignored : NetworkRecoveryDecision
    data class Stopped(val revision: Long) : NetworkRecoveryDecision
}

internal class NetworkRecoveryPolicy(
    private val backoffMillis: List<Long> = listOf(1_000, 2_000, 4_000, 8_000, 16_000, 30_000),
) {
    private var revision = 0L
    private var networkKey: String? = null
    private var retriesScheduled = 0
    private var stopped = false

    var phase: NetworkRecoveryPhase = NetworkRecoveryPhase.WAITING_FOR_NETWORK
        private set

    fun onCandidateChanged(key: String?): NetworkRecoveryDecision {
        if (stopped || key == networkKey) return NetworkRecoveryDecision.Ignored
        networkKey = key
        revision++
        retriesScheduled = 0
        phase = NetworkRecoveryPhase.SWITCHING
        return NetworkRecoveryDecision.Debounce(revision)
    }

    fun isCurrentRevision(expectedRevision: Long): Boolean = !stopped && expectedRevision == revision

    fun onDebounceElapsed(expectedRevision: Long): NetworkRecoveryDecision {
        if (stopped || expectedRevision != revision) return NetworkRecoveryDecision.Ignored
        val key = networkKey
        if (key == null) {
            phase = NetworkRecoveryPhase.WAITING_FOR_NETWORK
            return NetworkRecoveryDecision.NoNetwork(revision)
        }
        phase = NetworkRecoveryPhase.RECOVERING
        return NetworkRecoveryDecision.Attempt(revision, key)
    }

    fun onAttemptFinished(expectedRevision: Long, succeeded: Boolean): NetworkRecoveryDecision {
        if (stopped || expectedRevision != revision) return NetworkRecoveryDecision.Ignored
        if (succeeded) {
            retriesScheduled = 0
            phase = NetworkRecoveryPhase.READY
            return NetworkRecoveryDecision.Ready(revision)
        }
        return scheduleRetry()
    }

    fun onTransportFailure(): NetworkRecoveryDecision {
        if (stopped || networkKey == null) return NetworkRecoveryDecision.Ignored
        if (phase == NetworkRecoveryPhase.RETRY_WAIT ||
            phase == NetworkRecoveryPhase.EXHAUSTED ||
            phase == NetworkRecoveryPhase.SWITCHING ||
            phase == NetworkRecoveryPhase.RECOVERING
        ) {
            return NetworkRecoveryDecision.Ignored
        }
        phase = NetworkRecoveryPhase.RECOVERING
        return scheduleRetry()
    }

    fun onRetryElapsed(expectedRevision: Long): NetworkRecoveryDecision {
        if (stopped || expectedRevision != revision || phase != NetworkRecoveryPhase.RETRY_WAIT) {
            return NetworkRecoveryDecision.Ignored
        }
        val key = networkKey ?: return NetworkRecoveryDecision.Ignored
        phase = NetworkRecoveryPhase.RECOVERING
        return NetworkRecoveryDecision.Attempt(revision, key)
    }

    fun onTransportSuccess(): NetworkRecoveryDecision {
        if (stopped || networkKey == null) return NetworkRecoveryDecision.Ignored
        if (phase == NetworkRecoveryPhase.READY) return NetworkRecoveryDecision.Ready(revision)
        if (phase != NetworkRecoveryPhase.RETRY_WAIT && phase != NetworkRecoveryPhase.EXHAUSTED) {
            return NetworkRecoveryDecision.Ignored
        }
        revision++
        retriesScheduled = 0
        phase = NetworkRecoveryPhase.READY
        return NetworkRecoveryDecision.Ready(revision)
    }

    fun stop(): NetworkRecoveryDecision {
        if (stopped) return NetworkRecoveryDecision.Stopped(revision)
        stopped = true
        revision++
        networkKey = null
        retriesScheduled = 0
        phase = NetworkRecoveryPhase.STOPPED
        return NetworkRecoveryDecision.Stopped(revision)
    }

    private fun scheduleRetry(): NetworkRecoveryDecision {
        if (retriesScheduled >= backoffMillis.size) {
            phase = NetworkRecoveryPhase.EXHAUSTED
            return NetworkRecoveryDecision.Exhausted(revision)
        }
        val delay = backoffMillis[retriesScheduled++]
        phase = NetworkRecoveryPhase.RETRY_WAIT
        return NetworkRecoveryDecision.Retry(revision, delay)
    }
}
