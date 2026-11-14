package nova.lunavane

internal data class SavedNetwork(
    val networkId: String,
    val name: String = "",
    val createdAtMillis: Long,
    val lastUsedAtMillis: Long = 0L,
)

internal data class NetworkProfileState(
    val profiles: List<SavedNetwork> = emptyList(),
    val selectedNetworkId: String = "",
)

internal fun migrateLegacyNetwork(
    state: NetworkProfileState,
    legacyNetworkId: String,
    nowMillis: Long,
): NetworkProfileState {
    val legacyId = normalizeNetworkId(legacyNetworkId) ?: return state
    val profiles = if (state.profiles.any { it.networkId == legacyId }) {
        state.profiles
    } else {
        state.profiles + SavedNetwork(networkId = legacyId, createdAtMillis = nowMillis)
    }
    val selected = normalizeNetworkId(state.selectedNetworkId)
        ?.takeIf { id -> profiles.any { it.networkId == id } }
        ?: legacyId
    return state.copy(profiles = profiles, selectedNetworkId = selected)
}

internal fun addSavedNetwork(
    state: NetworkProfileState,
    networkId: String,
    name: String,
    nowMillis: Long,
): NetworkProfileState? {
    val normalized = normalizeNetworkId(networkId) ?: return null
    if (state.profiles.any { it.networkId == normalized }) return null
    return state.copy(
        profiles = state.profiles + SavedNetwork(
            networkId = normalized,
            name = name.trim(),
            createdAtMillis = nowMillis,
        ),
    )
}

internal fun renameSavedNetwork(
    state: NetworkProfileState,
    networkId: String,
    name: String,
): NetworkProfileState {
    val normalized = normalizeNetworkId(networkId) ?: return state
    return state.copy(
        profiles = state.profiles.map { profile ->
            if (profile.networkId == normalized) profile.copy(name = name.trim()) else profile
        },
    )
}

internal fun selectSavedNetwork(
    state: NetworkProfileState,
    networkId: String,
): NetworkProfileState {
    val normalized = normalizeNetworkId(networkId) ?: return state
    if (state.profiles.none { it.networkId == normalized }) return state
    return state.copy(selectedNetworkId = normalized)
}

internal fun markSavedNetworkUsed(
    state: NetworkProfileState,
    networkId: String,
    usedAtMillis: Long,
): NetworkProfileState {
    val normalized = normalizeNetworkId(networkId) ?: return state
    return state.copy(
        profiles = state.profiles.map { profile ->
            if (profile.networkId == normalized) {
                profile.copy(lastUsedAtMillis = usedAtMillis)
            } else {
                profile
            }
        },
    )
}

internal fun removeSavedNetwork(
    state: NetworkProfileState,
    networkId: String,
): NetworkProfileState {
    val normalized = normalizeNetworkId(networkId) ?: return state
    val profiles = state.profiles.filterNot { it.networkId == normalized }
    val selected = if (state.selectedNetworkId == normalized) {
        profiles.firstOrNull()?.networkId.orEmpty()
    } else {
        state.selectedNetworkId
    }
    return state.copy(profiles = profiles, selectedNetworkId = selected)
}

internal fun moveSavedNetwork(
    state: NetworkProfileState,
    networkId: String,
    offset: Int,
): NetworkProfileState {
    val index = state.profiles.indexOfFirst { it.networkId == networkId }
    val targetIndex = index + offset
    if (index < 0 || targetIndex !in state.profiles.indices) return state
    val profiles = state.profiles.toMutableList()
    val moved = profiles.removeAt(index)
    profiles.add(targetIndex, moved)
    return state.copy(profiles = profiles)
}
