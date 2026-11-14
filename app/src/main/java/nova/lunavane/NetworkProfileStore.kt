package nova.lunavane

import android.content.Context
import androidx.core.content.edit

internal object NetworkProfileStore {
    private const val PREFERENCES = "nova"
    private const val KEY_NETWORK_IDS = "savedNetworkIds"
    private const val KEY_NETWORK_ORDER = "savedNetworkOrder"
    private const val KEY_SELECTED_NETWORK_ID = "selectedNetworkId"
    private const val KEY_MIGRATION_COMPLETE = "savedNetworksMigrated"
    private const val KEY_NETWORK_ID = "networkId"
    private const val PROFILE_PREFIX = "savedNetwork."

    @Synchronized
    fun read(context: Context, nowMillis: Long = System.currentTimeMillis()): NetworkProfileState {
        val preferences = context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
        val ids = preferences.getStringSet(KEY_NETWORK_IDS, emptySet()).orEmpty()
            .mapNotNull(::normalizeNetworkId)
            .distinct()
        val storedOrder = preferences.getString(KEY_NETWORK_ORDER, "").orEmpty()
            .split(',')
            .mapNotNull(::normalizeNetworkId)
        val orderedIds = (storedOrder + ids).distinct().filter { it in ids }
        val profiles = orderedIds.map { id ->
            SavedNetwork(
                networkId = id,
                name = preferences.getString(profileKey(id, "name"), "").orEmpty(),
                createdAtMillis = preferences.getLong(profileKey(id, "created"), nowMillis),
                lastUsedAtMillis = preferences.getLong(profileKey(id, "lastUsed"), 0L),
            )
        }
        val selected = preferences.getString(KEY_SELECTED_NETWORK_ID, "").orEmpty()
        var state = NetworkProfileState(
            profiles = profiles,
            selectedNetworkId = selected,
        )
        if (!preferences.getBoolean(KEY_MIGRATION_COMPLETE, false)) {
            state = migrateLegacyNetwork(
                state = state,
                legacyNetworkId = preferences.getString(KEY_NETWORK_ID, "").orEmpty(),
                nowMillis = nowMillis,
            )
            persist(preferences, state, migrationComplete = true)
        } else {
            val selectedId = normalizeNetworkId(state.selectedNetworkId)
                ?.takeIf { id -> state.profiles.any { it.networkId == id } }
                ?: state.profiles.firstOrNull()?.networkId.orEmpty()
            val legacyId = preferences.getString(KEY_NETWORK_ID, "").orEmpty()
            if (selectedId != state.selectedNetworkId || legacyId != selectedId) {
                state = state.copy(selectedNetworkId = selectedId)
                persist(preferences, state, migrationComplete = true)
            }
        }
        return state
    }

    @Synchronized
    fun add(
        context: Context,
        networkId: String,
        name: String,
        nowMillis: Long = System.currentTimeMillis(),
    ): NetworkProfileState? {
        val preferences = context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
        val current = read(context, nowMillis)
        val updated = addSavedNetwork(current, networkId, name, nowMillis) ?: return null
        val selected = if (current.selectedNetworkId.isBlank()) {
            selectSavedNetwork(updated, networkId)
        } else {
            updated
        }
        return selected.takeIf { persist(preferences, it) }
    }

    @Synchronized
    fun rename(context: Context, networkId: String, name: String): NetworkProfileState? =
        update(context) { renameSavedNetwork(it, networkId, name) }

    @Synchronized
    fun select(context: Context, networkId: String): NetworkProfileState? =
        update(context) { selectSavedNetwork(it, networkId) }

    @Synchronized
    fun markUsed(
        context: Context,
        networkId: String,
        usedAtMillis: Long = System.currentTimeMillis(),
    ): NetworkProfileState? = update(context) { markSavedNetworkUsed(it, networkId, usedAtMillis) }

    @Synchronized
    fun remove(context: Context, networkId: String): NetworkProfileState? {
        val preferences = context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
        val current = read(context)
        val normalized = normalizeNetworkId(networkId) ?: return null
        val updated = removeSavedNetwork(current, normalized)
        if (updated.profiles.size == current.profiles.size) return null
        return updated.takeIf {
            persist(preferences, it, removedIds = setOf(normalized))
        }
    }

    @Synchronized
    fun move(context: Context, networkId: String, offset: Int): NetworkProfileState? =
        update(context) { moveSavedNetwork(it, networkId, offset) }

    private fun update(
        context: Context,
        transform: (NetworkProfileState) -> NetworkProfileState,
    ): NetworkProfileState? {
        val preferences = context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
        val current = read(context)
        val updated = transform(current)
        if (updated == current) return current
        return updated.takeIf { persist(preferences, it) }
    }

    private fun persist(
        preferences: android.content.SharedPreferences,
        state: NetworkProfileState,
        migrationComplete: Boolean = true,
        removedIds: Set<String> = emptySet(),
    ): Boolean {
        val normalizedProfiles = state.profiles.mapNotNull { profile ->
            normalizeNetworkId(profile.networkId)?.let { id -> profile.copy(networkId = id) }
        }.distinctBy(SavedNetwork::networkId)
        val normalizedState = state.copy(
            profiles = normalizedProfiles,
            selectedNetworkId = normalizeNetworkId(state.selectedNetworkId)
                ?.takeIf { selected -> normalizedProfiles.any { it.networkId == selected } }
                ?: normalizedProfiles.firstOrNull()?.networkId.orEmpty(),
        )
        preferences.edit {
            putStringSet(KEY_NETWORK_IDS, normalizedState.profiles.map(SavedNetwork::networkId).toSet())
            putString(KEY_NETWORK_ORDER, normalizedState.profiles.joinToString(",", transform = SavedNetwork::networkId))
            putString(KEY_SELECTED_NETWORK_ID, normalizedState.selectedNetworkId)
            putString(KEY_NETWORK_ID, normalizedState.selectedNetworkId)
            putBoolean(KEY_MIGRATION_COMPLETE, migrationComplete)
            normalizedState.profiles.forEach { profile ->
                putString(profileKey(profile.networkId, "name"), profile.name)
                putLong(profileKey(profile.networkId, "created"), profile.createdAtMillis)
                putLong(profileKey(profile.networkId, "lastUsed"), profile.lastUsedAtMillis)
            }
            removedIds.forEach { id ->
                remove(profileKey(id, "name"))
                remove(profileKey(id, "created"))
                remove(profileKey(id, "lastUsed"))
            }
        }
        return true
    }

    private fun profileKey(networkId: String, field: String): String = "$PROFILE_PREFIX$networkId.$field"
}
