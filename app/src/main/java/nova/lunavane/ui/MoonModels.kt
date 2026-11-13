package nova.lunavane.ui

import nova.lunavane.MoonSeed

internal data class MoonWorldGroup(
    val worldId: String,
    val seeds: List<String>,
)

internal fun groupMoonSeeds(entries: List<MoonSeed>): List<MoonWorldGroup> = entries
    .groupBy(MoonSeed::worldId)
    .toSortedMap()
    .map { (worldId, values) ->
        MoonWorldGroup(
            worldId = worldId,
            seeds = values.map(MoonSeed::seed).distinct().sorted(),
        )
    }
