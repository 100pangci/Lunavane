package nova.lunavane.vpn

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class NetworkCandidatePolicyTest {
    @Test
    fun excludesVpnAndUnsupportedTransports() {
        val vpn = candidate("vpn", UnderlayTransport.VPN, notVpn = false)
        val other = candidate("other", UnderlayTransport.OTHER)

        assertNull(selectNetworkCandidate(listOf(vpn, other), incumbentKey = null))
    }

    @Test
    fun validatedNetworkWinsAndEqualCandidatesKeepIncumbent() {
        val cellular = candidate("cell", UnderlayTransport.CELLULAR, validated = true)
        val wifi = candidate("wifi", UnderlayTransport.WIFI, validated = true)

        assertEquals(wifi, selectNetworkCandidate(listOf(cellular, wifi), incumbentKey = null))
        assertEquals(cellular, selectNetworkCandidate(listOf(cellular, wifi), incumbentKey = "cell"))
    }

    @Test
    fun newerEquivalentCellularCandidateCanReplaceStaleIncumbent() {
        val oldCarrier = candidate("cell-a", UnderlayTransport.CELLULAR, validated = true).copy(availableOrder = 1)
        val newCarrier = candidate("cell-b", UnderlayTransport.CELLULAR, validated = true).copy(availableOrder = 2)

        assertEquals(newCarrier, selectNetworkCandidate(listOf(oldCarrier, newCarrier), incumbentKey = "cell-a"))
    }

    @Test
    fun rankingRetainsFallbackCandidatesAfterPreferredNetwork() {
        val wifi = candidate("wifi", UnderlayTransport.WIFI, validated = true)
        val cellular = candidate("cell", UnderlayTransport.CELLULAR, validated = true)
        val ethernet = candidate("ethernet", UnderlayTransport.ETHERNET, validated = false)

        assertEquals(
            listOf(wifi, cellular, ethernet),
            rankNetworkCandidates(listOf(cellular, ethernet, wifi), incumbentKey = null),
        )
    }

    @Test
    fun physicalNetworkWithoutInternetIsStillEligible() {
        val localWifi = candidate("wifi-local", UnderlayTransport.WIFI, hasInternet = false)

        assertEquals(localWifi, selectNetworkCandidate(listOf(localWifi), incumbentKey = null))
    }

    @Test
    fun linkFingerprintIgnoresOrderingButTracksRouteAndMtuChanges() {
        val first = linkPropertiesFingerprint("wlan0", listOf("10.0.0.2/24", "fe80::1/64"), listOf("0.0.0.0/0"), 1500)
        val same = linkPropertiesFingerprint("wlan0", listOf("fe80::1/64", "10.0.0.2/24"), listOf("0.0.0.0/0"), 1500)
        val changed = linkPropertiesFingerprint("wlan0", listOf("10.0.0.2/24"), listOf("192.168.0.0/16"), 1400)

        assertEquals(first, same)
        assertEquals(false, first == changed)
    }

    private fun candidate(
        key: String,
        transport: UnderlayTransport,
        notVpn: Boolean = true,
        hasInternet: Boolean = true,
        validated: Boolean = false,
    ) = NetworkCandidateDescriptor(key, notVpn, transport, hasInternet, validated, availableOrder = 1)
}
