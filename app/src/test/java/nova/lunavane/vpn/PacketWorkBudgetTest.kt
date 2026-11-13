package nova.lunavane.vpn

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PacketWorkBudgetTest {
    @Test
    fun itemAndByteCapsRejectWithoutBlocking() {
        val budget = PacketWorkBudget(maxItems = 2, maxBytes = 10)
        val first = budget.tryAcquire(4)!!
        val second = budget.tryAcquire(6)!!

        assertNull(budget.tryAcquire(1))
        assertEquals(2, budget.inFlightItems)
        assertEquals(10L, budget.inFlightBytes)

        first.release()
        assertEquals(1, budget.inFlightItems)
        assertEquals(6L, budget.inFlightBytes)
        second.release()
    }

    @Test
    fun leaseReleaseIsIdempotent() {
        val budget = PacketWorkBudget(maxItems = 1, maxBytes = 4)
        val lease = budget.tryAcquire(4)!!

        lease.release()
        lease.release()

        assertEquals(0, budget.inFlightItems)
        assertEquals(0L, budget.inFlightBytes)
        assertEquals(true, budget.tryAcquire(4) != null)
    }
}
