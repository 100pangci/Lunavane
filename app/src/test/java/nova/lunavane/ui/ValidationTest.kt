package nova.lunavane.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ValidationTest {
    @Test
    fun `sanitize keeps hex characters only and limits length`() {
        assertEquals("8056c2e21c000001", sanitizeHexInput("8056-C2E2-1C00-0001", 16))
        assertEquals("abcdef", sanitizeHexInput("ABCDEF", 10))
        assertEquals("abc", sanitizeHexInput("ab cz", 16))
    }

    @Test
    fun `network id requires sixteen hex characters`() {
        assertTrue(isValidNetworkId("8056c2e21c000001"))
        assertTrue(isValidNetworkId("8056C2E21C000001"))
        assertFalse(isValidNetworkId("8056c2e21c00000"))
        assertFalse(isValidNetworkId("8056c2e21c00000g"))
    }

    @Test
    fun `moon ids must be non-zero`() {
        assertTrue(isValidMoonWorldId("8056c2e21c000001"))
        assertFalse(isValidMoonWorldId("0000000000000000"))
        assertTrue(isValidMoonSeed("abcdef1234"))
        assertFalse(isValidMoonSeed("0000000000"))
    }
}
