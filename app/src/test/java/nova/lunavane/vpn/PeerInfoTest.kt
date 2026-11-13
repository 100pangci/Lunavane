package nova.lunavane.vpn

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PeerInfoTest {
    @Test
    fun parsesPeerWithPathsAndCapabilities() {
        val peers = parsePeerLines(
            arrayOf(
                "1234567890\tMOON\t42\t1.16.2\t0\t-1\t-1\t" +
                    "[2001:db8::1]:9993,1,1;192.0.2.1:9993,0,1",
            ),
        )

        assertEquals(1, peers.size)
        val peer = peers.single()
        assertEquals("1234567890", peer.address)
        assertEquals(PeerRole.MOON, peer.role)
        assertEquals(42, peer.latencyMillis)
        assertEquals("1.16.2", peer.version)
        assertEquals(false, peer.bonded)
        assertNull(peer.aliveLinks)
        assertNull(peer.totalLinks)
        assertEquals(
            listOf(
                PeerPathInfo("[2001:db8::1]:9993", preferred = true, active = true),
                PeerPathInfo("192.0.2.1:9993", preferred = false, active = true),
            ),
            peer.paths,
        )
    }

    @Test
    fun unknownVersionLatencyAndRoleStayUnknown() {
        val peer = parsePeerLines(arrayOf("abcdef0123\tUNKNOWN\t-1\t\t0\t-1\t-1\t")).single()

        assertEquals(PeerRole.UNKNOWN, peer.role)
        assertNull(peer.latencyMillis)
        assertNull(peer.version)
        assertTrue(peer.paths.isEmpty())
    }

    @Test
    fun bondedPeerReportsLinkCounts() {
        val peer = parsePeerLines(arrayOf("abcdef0123\tLEAF\t10\t1.16.2\t1\t2\t3\t192.0.2.1:9993,1,1")).single()

        assertEquals(true, peer.bonded)
        assertEquals(2, peer.aliveLinks)
        assertEquals(3, peer.totalLinks)
    }

    @Test
    fun malformedRowsAreIgnoredAndDuplicatesCollapse() {
        val peers = parsePeerLines(
            arrayOf(
                "not-a-peer",
                "abcdef0123\tLEAF\t10\t1.16.2\t0\t-1\t-1\t192.0.2.1:9993,1,1",
                "abcdef0123\tPLANET\t20\t1.16.2\t0\t-1\t-1\t192.0.2.2:9993,0,1",
                "short\tLEAF\t10\t1.16.2\t0\t-1\t-1\t",
            ),
        )

        assertEquals(1, peers.size)
        assertEquals(PeerRole.LEAF, peers.single().role)
    }

    @Test
    fun nullInputProducesEmptyList() {
        assertTrue(parsePeerLines(null).isEmpty())
    }
}
