package nova.lunavane

import android.content.Context

internal data class MoonSeed(val worldId: String, val seed: String)

internal data class MoonStoreResult(val entries: List<MoonSeed>, val persisted: Boolean)

internal fun mergeMoonSeed(entries: List<MoonSeed>, entry: MoonSeed): List<MoonSeed> =
    (entries.filterNot { it == entry } + entry)
        .sortedWith(compareBy(MoonSeed::worldId, MoonSeed::seed))

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
        .sortedWith(compareBy(MoonSeed::worldId, MoonSeed::seed))

    fun save(context: Context, entries: List<MoonSeed>): Boolean {
        val values = entries.map { "${it.worldId}:${it.seed}" }.toSet()
        return context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
            .edit()
            .putStringSet(KEY_MOON_SEEDS, values)
            .commit()
    }

    fun upsert(context: Context, entry: MoonSeed): MoonStoreResult {
        val entries = mergeMoonSeed(read(context), entry)
        return MoonStoreResult(entries, save(context, entries))
    }

    fun remove(context: Context, worldId: String): MoonStoreResult {
        val entries = read(context).filterNot { it.worldId == worldId }
        return MoonStoreResult(entries, save(context, entries))
    }
}
