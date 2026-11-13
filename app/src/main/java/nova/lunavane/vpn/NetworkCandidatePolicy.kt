package nova.lunavane.vpn

internal enum class UnderlayTransport {
    WIFI,
    CELLULAR,
    ETHERNET,
    OTHER,
    VPN,
}

internal data class NetworkCandidateDescriptor(
    val key: String,
    val notVpn: Boolean,
    val transport: UnderlayTransport,
    val hasInternet: Boolean,
    val validated: Boolean,
    val availableOrder: Long,
)

internal fun rankNetworkCandidates(
    candidates: Collection<NetworkCandidateDescriptor>,
    incumbentKey: String?,
): List<NetworkCandidateDescriptor> = candidates
    .asSequence()
    .filter { it.notVpn && it.transport in setOf(UnderlayTransport.WIFI, UnderlayTransport.CELLULAR, UnderlayTransport.ETHERNET) }
    .sortedWith { left, right -> compareCandidates(left, right, incumbentKey) }
    .toList()

private fun compareCandidates(
    left: NetworkCandidateDescriptor,
    right: NetworkCandidateDescriptor,
    incumbentKey: String?,
): Int {
    compareValues(right.validated, left.validated).takeIf { it != 0 }?.let { return it }
    compareValues(right.hasInternet, left.hasInternet).takeIf { it != 0 }?.let { return it }

    if (left.transport == right.transport) {
        compareValues(right.availableOrder, left.availableOrder).takeIf { it != 0 }?.let { return it }
    } else {
        val leftIsIncumbent = left.key == incumbentKey
        val rightIsIncumbent = right.key == incumbentKey
        if (leftIsIncumbent != rightIsIncumbent) return if (leftIsIncumbent) -1 else 1
        compareValues(right.transport.rank(), left.transport.rank()).takeIf { it != 0 }?.let { return it }
    }

    val leftIsIncumbent = left.key == incumbentKey
    val rightIsIncumbent = right.key == incumbentKey
    if (leftIsIncumbent != rightIsIncumbent) return if (leftIsIncumbent) -1 else 1
    return compareValues(left.availableOrder, right.availableOrder)
}

internal fun selectNetworkCandidate(
    candidates: Collection<NetworkCandidateDescriptor>,
    incumbentKey: String?,
): NetworkCandidateDescriptor? = rankNetworkCandidates(candidates, incumbentKey).firstOrNull()

internal fun linkPropertiesFingerprint(
    interfaceName: String?,
    linkAddresses: Collection<String>,
    routes: Collection<String>,
    mtu: Int,
): String = listOf(
    interfaceName.orEmpty(),
    mtu.toString(),
    linkAddresses.sorted().joinToString(","),
    routes.sorted().joinToString(","),
).joinToString("|")

private fun UnderlayTransport.rank(): Int = when (this) {
    UnderlayTransport.ETHERNET -> 3
    UnderlayTransport.WIFI -> 2
    UnderlayTransport.CELLULAR -> 1
    UnderlayTransport.OTHER,
    UnderlayTransport.VPN -> 0
}
