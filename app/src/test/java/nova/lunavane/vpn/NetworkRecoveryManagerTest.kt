package nova.lunavane.vpn

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.util.concurrent.Executor

class NetworkRecoveryManagerTest {
    @Test
    fun candidateBurstDebouncesAndAttemptsLatestCandidate() {
        val scheduler = FakeRecoveryScheduler()
        val attempts = mutableListOf<String>()
        val manager = manager(scheduler, onAvailable = { value, _ ->
            attempts += value
            true
        })

        manager.updateCandidate("wifi", "wifi")
        manager.updateCandidate("cellular", "cellular")

        assertEquals(listOf(300L), scheduler.pendingDelays())
        assertEquals(NetworkRecoveryPhase.SWITCHING, manager.phase)

        assertEquals(300L, scheduler.runNext())
        assertEquals(0L, scheduler.runNext())
        assertEquals(listOf("cellular"), attempts)
        assertEquals(NetworkRecoveryPhase.READY, manager.phase)
    }

    @Test
    fun blockedNetworkAttemptDoesNotOccupyRecoveryScheduler() {
        val scheduler = FakeRecoveryScheduler()
        val attempts = mutableListOf<Runnable>()
        val manager = manager(
            scheduler,
            onAvailable = { _, _ -> true },
            attemptExecutor = Executor { attempts += it },
        )

        manager.updateCandidate("wifi", "wifi")
        scheduler.runNext()
        scheduler.runNext()
        assertEquals(NetworkRecoveryPhase.RECOVERING, manager.phase)
        assertEquals(1, attempts.size)

        manager.updateCandidate("cellular", "cellular")
        assertEquals(NetworkRecoveryPhase.SWITCHING, manager.phase)
        attempts.single().run()
        assertEquals(NetworkRecoveryPhase.SWITCHING, manager.phase)

        scheduler.runNext()
        scheduler.runNext()
        attempts.last().run()
        assertEquals(NetworkRecoveryPhase.READY, manager.phase)
    }

    @Test
    fun failedAttemptsUseFiniteBackoffAndThenExhaust() {
        val scheduler = FakeRecoveryScheduler()
        var attempts = 0
        val manager = manager(scheduler, onAvailable = { _, _ ->
            attempts++
            false
        })

        manager.updateCandidate("wifi", "wifi")
        scheduler.runNext()

        val delays = mutableListOf<Long>()
        while (true) {
            val delay = scheduler.runNext() ?: break
            if (delay > 0) delays += delay
        }

        assertEquals(listOf(1_000L, 2_000L, 4_000L, 8_000L, 16_000L, 30_000L), delays)
        assertEquals(7, attempts)
        assertEquals(NetworkRecoveryPhase.EXHAUSTED, manager.phase)
    }

    @Test
    fun newCandidateCancelsScheduledRetry() {
        val scheduler = FakeRecoveryScheduler()
        val attempts = mutableListOf<String>()
        val manager = manager(scheduler, onAvailable = { value, _ ->
            attempts += value
            value == "cellular"
        })

        manager.updateCandidate("wifi", "wifi")
        scheduler.runNext()
        scheduler.runNext()
        assertEquals(NetworkRecoveryPhase.RETRY_WAIT, manager.phase)

        manager.updateCandidate("cellular", "cellular")
        assertEquals(listOf(300L), scheduler.pendingDelays())
        assertEquals(300L, scheduler.runNext())
        assertEquals(0L, scheduler.runNext())

        assertEquals(listOf("wifi", "cellular"), attempts)
        assertEquals(NetworkRecoveryPhase.READY, manager.phase)
        assertNull(scheduler.runNext())
    }

    @Test
    fun missingNetworkAfterLossClosesTransportOnce() {
        val scheduler = FakeRecoveryScheduler()
        var noNetworkCalls = 0
        val manager = manager(scheduler, onNoNetwork = { noNetworkCalls++ })

        manager.updateCandidate("wifi", "wifi")
        scheduler.runNext()
        scheduler.runNext()
        manager.updateCandidate(null, null)
        scheduler.runNext()
        scheduler.runNext()

        assertEquals(1, noNetworkCalls)
        assertEquals(NetworkRecoveryPhase.WAITING_FOR_NETWORK, manager.phase)
        assertNull(scheduler.runNext())
    }

    @Test
    fun transportSuccessDuringRetryWaitCancelsRetry() {
        val scheduler = FakeRecoveryScheduler()
        val manager = manager(scheduler, onAvailable = { _, _ -> false })

        manager.updateCandidate("wifi", "wifi")
        scheduler.runNext()
        scheduler.runNext()
        assertEquals(NetworkRecoveryPhase.RETRY_WAIT, manager.phase)

        manager.onTransportSuccess()

        assertEquals(NetworkRecoveryPhase.READY, manager.phase)
        assertNull(scheduler.runNext())
    }

    @Test
    fun stopCancelsPendingWorkAndSuppressesCallbacks() {
        val scheduler = FakeRecoveryScheduler()
        var attempts = 0
        var noNetworkCalls = 0
        var phaseChanges = 0
        val manager = manager(
            scheduler,
            onAvailable = { _, _ ->
                attempts++
                true
            },
            onNoNetwork = { noNetworkCalls++ },
            onPhaseChanged = { _, _ -> phaseChanges++ },
        )

        manager.updateCandidate("wifi", "wifi")
        manager.stop()
        manager.updateCandidate(null, null)

        assertEquals(0, attempts)
        assertEquals(0, noNetworkCalls)
        assertEquals(2, phaseChanges)
        assertEquals(NetworkRecoveryPhase.STOPPED, manager.phase)
        assertNull(scheduler.runNext())
    }

    @Test
    fun phaseCallbacksCarryMonotonicSequenceNumbers() {
        val scheduler = FakeRecoveryScheduler()
        val sequences = mutableListOf<Long>()
        val manager = manager(
            scheduler,
            onPhaseChanged = { _, sequence -> sequences += sequence },
        )

        manager.updateCandidate("wifi", "wifi")
        scheduler.runNext()
        scheduler.runNext()
        manager.stop()

        assertEquals(listOf(1L, 2L, 3L, 4L), sequences)
    }

    @Test
    fun staleSessionDoesNotScheduleAttempts() {
        val scheduler = FakeRecoveryScheduler()
        var attempts = 0
        val manager = manager(
            scheduler,
            sessionIsCurrent = { false },
            onAvailable = { _, _ ->
                attempts++
                true
            },
        )

        manager.updateCandidate("wifi", "wifi")

        assertEquals(0, attempts)
        assertNull(scheduler.runNext())
    }

    @Test
    fun staleAttemptCannotCommitAfterCandidateChangesDuringBinding() {
        val scheduler = FakeRecoveryScheduler()
        var committed = false
        lateinit var recoveryManager: NetworkRecoveryManager<String>
        recoveryManager = manager(
            scheduler,
            onAvailable = { value, revision ->
                if (value == "wifi") {
                    recoveryManager.updateCandidate("cellular", "cellular")
                    committed = recoveryManager.runIfCurrentRevision(revision) { true }
                }
                false
            },
        )

        recoveryManager.updateCandidate("wifi", "wifi")
        scheduler.runNext()
        scheduler.runNext()

        assertEquals(false, committed)
        assertEquals(NetworkRecoveryPhase.SWITCHING, recoveryManager.phase)
    }

    @Test
    fun reentrantSchedulerDoesNotLoseRetryChain() {
        val scheduler = ImmediateRecoveryScheduler()
        var attempts = 0
        val manager = manager(
            scheduler,
            onAvailable = { _, _ ->
                attempts++
                false
            },
        )

        manager.updateCandidate("wifi", "wifi")

        assertEquals(7, attempts)
        assertEquals(NetworkRecoveryPhase.EXHAUSTED, manager.phase)
    }

    private fun manager(
        scheduler: RecoveryScheduler,
        sessionIsCurrent: () -> Boolean = { true },
        onAvailable: (String, Long) -> Boolean = { _, _ -> true },
        onNoNetwork: () -> Unit = {},
        onPhaseChanged: (NetworkRecoveryPhase, Long) -> Unit = { _, _ -> },
        attemptExecutor: Executor = Executor { it.run() },
    ) = NetworkRecoveryManager(
        scheduler = scheduler,
        sessionIsCurrent = sessionIsCurrent,
        onNetworkAvailable = onAvailable,
        onNoNetwork = onNoNetwork,
        onPhaseChanged = onPhaseChanged,
        attemptExecutor = attemptExecutor,
    )

    private class FakeRecoveryScheduler : RecoveryScheduler {
        private val tasks = mutableListOf<FakeTask>()

        override fun schedule(delayMillis: Long, action: () -> Unit): ScheduledRecoveryTask {
            val task = FakeTask(delayMillis, action)
            tasks += task
            return ScheduledRecoveryTask { task.cancelled = true }
        }

        fun pendingDelays(): List<Long> = tasks.filterNot { it.cancelled }.map { it.delayMillis }

        fun runNext(): Long? {
            val task = tasks.firstOrNull { !it.cancelled } ?: return null
            tasks.remove(task)
            task.action()
            return task.delayMillis
        }

        private class FakeTask(
            val delayMillis: Long,
            val action: () -> Unit,
            var cancelled: Boolean = false,
        )
    }

    private class ImmediateRecoveryScheduler : RecoveryScheduler {
        override fun schedule(delayMillis: Long, action: () -> Unit): ScheduledRecoveryTask {
            action()
            return ScheduledRecoveryTask { }
        }
    }
}
