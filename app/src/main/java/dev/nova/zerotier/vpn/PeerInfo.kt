package dev.nova.zerotier.vpn

internal enum class PeerRole {
    LEAF,
    MOON,
    PLANET,
    UNKNOWN,
}

internal data class PeerPathInfo(
    val endpoint: String,
    val preferred: Boolean,
    val active: Boolean,
)

internal data class PeerInfo(
    val address: String,
    val role: PeerRole,
    val latencyMillis: Int?,
    val version: String?,
    val bonded: Boolean,
    val aliveLinks: Int?,
    val totalLinks: Int?,
    val paths: List<PeerPathInfo>,
)

/**
 * Parses the tab-separated peer rows produced by NativeCore.peers.
 * A row is: address, role, latency, version, bonded, aliveLinks, totalLinks, paths.
 * Paths are separated by ';' and each path is endpoint,preferred,active.
 */
internal fun parsePeerLines(lines: Array<String>?): List<PeerInfo> = lines
    .orEmpty()
    .mapNotNull(::parsePeerLine)
    .distinctBy(PeerInfo::address)
    .sortedBy(PeerInfo::address)

private fun parsePeerLine(line: String): PeerInfo? {
    val fields = line.split('\t')
    if (fields.size != 8) return null
    val address = fields[0]
    if (address.length != 10 || address.any { it.digitToIntOrNull(16) == null }) return null
    val role = when (fields[1]) {
        "LEAF" -> PeerRole.LEAF
        "MOON" -> PeerRole.MOON
        "PLANET" -> PeerRole.PLANET
        else -> PeerRole.UNKNOWN
    }
    val latency = fields[2].toIntOrNull()?.takeIf { it >= 0 }
    val version = fields[3].takeIf { it.isNotBlank() }
    val bonded = fields[4] == "1"
    val aliveLinks = fields[5].toIntOrNull()?.takeIf { bonded && it >= 0 }
    val totalLinks = fields[6].toIntOrNull()?.takeIf { bonded && it >= 0 }
    val paths = fields[7]
        .split(';')
        .mapNotNull(::parsePeerPath)
    return PeerInfo(
        address = address,
        role = role,
        latencyMillis = latency,
        version = version,
        bonded = bonded,
        aliveLinks = aliveLinks,
        totalLinks = totalLinks,
        paths = paths,
    )
}

private fun parsePeerPath(text: String): PeerPathInfo? {
    if (text.isBlank()) return null
    val fields = text.split(',')
    if (fields.size != 3 || fields[0].isBlank()) return null
    return PeerPathInfo(
        endpoint = fields[0],
        preferred = fields[1] == "1",
        active = fields[2] == "1",
    )
}
