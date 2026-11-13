package dev.nova.zerotier.vpn

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
    fun acceptsOnlyCurrentNonStoppingHandle() {
        assertTrue(isCurrentCoreSession(7, 19, 7, 19, stopping = false))
        assertFalse(isCurrentCoreSession(6, 19, 7, 19, stopping = false))
        assertFalse(isCurrentCoreSession(7, 18, 7, 19, stopping = false))
        assertFalse(isCurrentCoreSession(7, 19, 7, 19, stopping = true))
        assertFalse(isCurrentCoreSession(7, 0, 7, 0, stopping = false))
    }
}
