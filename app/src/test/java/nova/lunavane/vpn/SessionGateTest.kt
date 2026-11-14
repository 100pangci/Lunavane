package nova.lunavane.vpn

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SessionGateTest {
    @Test
    fun coreHandleCanBeClaimedForDestroyOnlyOnce() {
        val slot = CoreHandleSlot(19)

        assertEquals(19L, slot.take())
        assertEquals(0L, slot.take())
        slot.set(20L)
        assertFalse(slot.clearIf(19L))
        assertEquals(20L, slot.get())
        assertTrue(slot.clearIf(20L))
        assertEquals(0L, slot.get())
    }

    @Test
    fun stopCompletionMustBelongToANewerFullyCleanedSession() {
        assertFalse(sessionStopCompletedAfter(4, 4, 4))
        assertFalse(sessionStopCompletedAfter(4, 5, 4))
        assertTrue(sessionStopCompletedAfter(4, 5, 5))
        assertTrue(sessionStopCompletedAfter(4, 6, 7))
    }

    @Test
    fun stopDeletionWaitsForItsRequestToComplete() {
        assertFalse(stopRequestCompleted(5L, 4L))
        assertTrue(stopRequestCompleted(5L, 5L))
        assertTrue(stopRequestCompleted(5L, 6L))
        assertFalse(stopRequestCompleted(0L, 6L))
    }

    @Test
    fun acceptsOnlyCurrentNonStoppingHandle() {
        assertTrue(isCurrentCoreSession(7, 19, 7, 19, stopping = false))
        assertFalse(isCurrentCoreSession(6, 19, 7, 19, stopping = false))
        assertFalse(isCurrentCoreSession(7, 19, 8, 20, stopping = false))
        assertFalse(isCurrentCoreSession(7, 18, 7, 19, stopping = false))
        assertFalse(isCurrentCoreSession(7, 19, 7, 19, stopping = true))
        assertFalse(isCurrentCoreSession(7, 0, 7, 0, stopping = false))
    }
}
