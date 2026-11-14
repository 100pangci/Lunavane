package nova.lunavane.vpn

import java.util.concurrent.atomic.AtomicLong

internal class CoreHandleSlot(initialHandle: Long = 0L) {
    private val handle = AtomicLong(initialHandle)

    fun get(): Long = handle.get()

    fun set(value: Long) {
        handle.set(value)
    }

    fun take(): Long = handle.getAndSet(0L)

    fun clearIf(expectedHandle: Long): Boolean = handle.compareAndSet(expectedHandle, 0L)
}

internal fun sessionStopCompletedAfter(
    previousGeneration: Long,
    stopGeneration: Long,
    completedGeneration: Long,
): Boolean = stopGeneration > previousGeneration && completedGeneration >= stopGeneration

// Stop requests complete in the same order as their serialized Core cleanup tasks.
internal fun stopRequestCompleted(requestId: Long, completedRequestId: Long): Boolean =
    requestId > 0L && completedRequestId >= requestId

internal fun isCurrentCoreSession(
    expectedSession: Long,
    expectedHandle: Long,
    currentSession: Long,
    currentHandle: Long,
    stopping: Boolean,
): Boolean =
    !stopping && expectedSession != 0L && expectedSession == currentSession &&
        expectedHandle != 0L && expectedHandle == currentHandle
