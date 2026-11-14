package nova.lunavane

internal fun isValidNetworkId(value: String): Boolean =
    value.length == 16 && value.all { it.digitToIntOrNull(16) != null }

internal fun normalizeNetworkId(value: String): String? =
    value.takeIf(::isValidNetworkId)?.lowercase()
