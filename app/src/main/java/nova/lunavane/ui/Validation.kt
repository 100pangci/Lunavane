package nova.lunavane.ui

import nova.lunavane.isValidNetworkId as isValidNetworkProfileId

private fun isHex(value: String): Boolean = value.all { it.digitToIntOrNull(16) != null }

internal fun sanitizeHexInput(value: String, maxLength: Int): String =
    value.filter { it.digitToIntOrNull(16) != null }.take(maxLength).lowercase()

internal fun isValidHexId(value: String, length: Int, requireNonZero: Boolean = false): Boolean =
    value.length == length && isHex(value) && (!requireNonZero || value.toULongOrNull(16) != 0uL)

internal fun isValidNetworkId(value: String): Boolean = isValidNetworkProfileId(value)

internal fun isValidMoonWorldId(value: String): Boolean = isValidHexId(value, 16, requireNonZero = true)

internal fun isValidMoonSeed(value: String): Boolean = isValidHexId(value, 10, requireNonZero = true)
