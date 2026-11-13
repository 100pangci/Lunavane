package dev.nova.zerotier.vpn

import java.util.concurrent.Executor
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

internal fun interface ScheduledRecoveryTask {
    fun cancel()
}

internal interface RecoveryScheduler {
    fun schedule(delayMillis: Long, action: () -> Unit): ScheduledRecoveryTask?
}

internal class ExecutorRecoveryScheduler(
    private val executor: ScheduledExecutorService,
) : RecoveryScheduler {
    override fun schedule(delayMillis: Long, action: () -> Unit): ScheduledRecoveryTask? = try {
        val future = executor.schedule({ action() }, delayMillis, TimeUnit.MILLISECONDS)
        ScheduledRecoveryTask { future.cancel(false) }
    } catch (_: RejectedExecutionException) {
        null
    }
}

internal class NetworkRecoveryManager<N>(
    private val scheduler: RecoveryScheduler,
    private val sessionIsCurrent: () -> Boolean,
    private val onNetworkAvailable: (N, Long) -> Boolean,
    private val onNoNetwork: () -> Unit,
    private val onPhaseChanged: (NetworkRecoveryPhase, Long) -> Unit,
    private val attemptExecutor: Executor = Executor { it.run() },
    private val debounceMillis: Long = 300,
) {
    private val lock = Any()
    private val policy = NetworkRecoveryPolicy()
    private var candidate: N? = null
    private var pendingTask: PendingTask? = null
    private var phaseSequence = 0L

    private class PendingTask {
        @Volatile var task: ScheduledRecoveryTask? = null
        val started = AtomicBoolean(false)
    }

    @Volatile
    var phase: NetworkRecoveryPhase = NetworkRecoveryPhase.WAITING_FOR_NETWORK
        private set

    fun updateCandidate(key: String?, value: N?) {
        val decision = synchronized(lock) {
            val next = policy.onCandidateChanged(key)
            if (next is NetworkRecoveryDecision.Debounce) {
                candidate = value
                cancelPendingLocked()
            }
            next
        }
        if (decision is NetworkRecoveryDecision.Debounce) setPhase(NetworkRecoveryPhase.SWITCHING, decision.revision)
        dispatch(decision)
    }

    fun onTransportFailure() {
        dispatch(synchronized(lock) { policy.onTransportFailure() })
    }

    fun runIfCurrentRevision(revision: Long, action: () -> Boolean): Boolean = synchronized(lock) {
        policy.isCurrentRevision(revision) && action()
    }

    fun onTransportSuccess() {
        val decision = synchronized(lock) {
            val next = policy.onTransportSuccess()
            if (next is NetworkRecoveryDecision.Ready) cancelPendingLocked()
            next
        }
        dispatch(decision)
    }

    fun stop() {
        val sequence = synchronized(lock) {
            policy.stop()
            cancelPendingLocked()
            if (phase == NetworkRecoveryPhase.STOPPED) return
            phase = NetworkRecoveryPhase.STOPPED
            phaseSequence++
            phaseSequence
        }
        onPhaseChanged(NetworkRecoveryPhase.STOPPED, sequence)
    }

    private fun dispatch(decision: NetworkRecoveryDecision) {
        when (decision) {
            is NetworkRecoveryDecision.Debounce -> schedule(decision.revision, debounceMillis) {
                dispatch(synchronized(lock) { policy.onDebounceElapsed(decision.revision) })
            }
            is NetworkRecoveryDecision.Attempt -> schedule(decision.revision, 0) {
                attempt(decision)
            }
            is NetworkRecoveryDecision.Retry -> {
                setPhase(NetworkRecoveryPhase.RETRY_WAIT, decision.revision)
                schedule(decision.revision, decision.delayMillis) {
                    dispatch(synchronized(lock) { policy.onRetryElapsed(decision.revision) })
                }
            }
            is NetworkRecoveryDecision.NoNetwork -> schedule(decision.revision, 0) {
                if (!isCurrentRevision(decision.revision)) return@schedule
                if (sessionIsCurrent()) onNoNetwork()
                setPhase(NetworkRecoveryPhase.WAITING_FOR_NETWORK, decision.revision)
            }
            is NetworkRecoveryDecision.Ready -> setPhase(NetworkRecoveryPhase.READY, decision.revision)
            is NetworkRecoveryDecision.Exhausted -> setPhase(NetworkRecoveryPhase.EXHAUSTED, decision.revision)
            NetworkRecoveryDecision.Ignored,
            is NetworkRecoveryDecision.Stopped -> Unit
        }
    }

    private fun attempt(decision: NetworkRecoveryDecision.Attempt) {
        if (!sessionIsCurrent()) return
        val selected = synchronized(lock) {
            if (policy.isCurrentRevision(decision.revision)) candidate else null
        }
        if (selected == null) {
            dispatch(synchronized(lock) { policy.onAttemptFinished(decision.revision, succeeded = false) })
            return
        }

        setPhase(NetworkRecoveryPhase.RECOVERING, decision.revision)
        // Socket binding can block; keep recovery scheduling responsive while it runs.
        val task = Runnable {
            if (!sessionIsCurrent() || !isCurrentRevision(decision.revision)) return@Runnable
            val succeeded = runCatching { onNetworkAvailable(selected, decision.revision) }.getOrDefault(false)
            dispatch(synchronized(lock) {
                policy.onAttemptFinished(decision.revision, succeeded)
            })
        }
        try {
            attemptExecutor.execute(task)
        } catch (_: RejectedExecutionException) {
            dispatch(synchronized(lock) {
                policy.onAttemptFinished(decision.revision, succeeded = false)
            })
        }
    }

    private fun schedule(revision: Long, delayMillis: Long, action: () -> Unit) {
        if (!sessionIsCurrent() || !isCurrentRevision(revision)) return
        val pending = PendingTask()
        val future = scheduler.schedule(delayMillis) {
            pending.started.set(true)
            synchronized(lock) {
                if (pendingTask === pending) pendingTask = null
            }
            action()
        }
        if (future == null) {
            setPhase(NetworkRecoveryPhase.EXHAUSTED, revision)
            return
        }
        pending.task = future
        synchronized(lock) {
            when {
                !policy.isCurrentRevision(revision) -> future.cancel()
                pending.started.get() -> Unit
                else -> {
                    val previous = pendingTask
                    pendingTask = pending
                    previous?.task?.cancel()
                }
            }
        }
    }

    private fun setPhase(next: NetworkRecoveryPhase, revision: Long) {
        val sequence = synchronized(lock) {
            if (!policy.isCurrentRevision(revision) || phase == next) return
            phase = next
            phaseSequence++
            phaseSequence
        }
        if (sessionIsCurrent()) onPhaseChanged(next, sequence)
    }

    private fun isCurrentRevision(revision: Long): Boolean =
        synchronized(lock) { policy.isCurrentRevision(revision) }

    private fun cancelPendingLocked() {
        pendingTask?.task?.cancel()
        pendingTask = null
    }
}
