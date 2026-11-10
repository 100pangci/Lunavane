package dev.nova.zerotier

import android.content.Context

internal data class MoonSeed(val worldId: String, val seed: String)

internal object MoonStore {
    private const val PREFERENCES = "nova"
    private const val KEY_MOON_SEEDS = "moonSeeds"

    fun read(context: Context): List<MoonSeed> = context
        .getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
        .getStringSet(KEY_MOON_SEEDS, emptySet())
        .orEmpty()
        .mapNotNull { value ->
            val parts = value.split(':', limit = 2)
            if (parts.size != 2) return@mapNotNull null
            val worldId = parts[0].lowercase()
            val seed = parts[1].lowercase()
            if (worldId.length != 16 || seed.length != 10 ||
                worldId.toULongOrNull(16) == null || seed.toULongOrNull(16) == null ||
                worldId.all { it.digitToIntOrNull(16) != null }.not() ||
                seed.all { it.digitToIntOrNull(16) != null }.not()
            ) {
                return@mapNotNull null
            }
            MoonSeed(worldId, seed)
        }
        .sortedBy(MoonSeed::worldId)

    fun save(context: Context, entries: List<MoonSeed>) {
        val values = entries.map { "${it.worldId}:${it.seed}" }.toSet()
        context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
            .edit()
            .putStringSet(KEY_MOON_SEEDS, values)
            .apply()
    }

    fun upsert(context: Context, entry: MoonSeed): List<MoonSeed> =
        (read(context).filterNot { it.worldId == entry.worldId } + entry)
            .sortedBy(MoonSeed::worldId)
            .also { save(context, it) }

    fun remove(context: Context, worldId: String): List<MoonSeed> =
        read(context).filterNot { it.worldId == worldId }
            .also { save(context, it) }
}
