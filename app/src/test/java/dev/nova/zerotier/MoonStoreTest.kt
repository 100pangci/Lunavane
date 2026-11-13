package dev.nova.zerotier

import org.junit.Assert.assertEquals
import org.junit.Test

class MoonStoreTest {
    @Test
    fun keepsDistinctSeedsForTheSameMoonAndDeduplicatesRepeatedOrbit() {
        val moonId = "0123456789abcdef"
        val firstSeed = MoonSeed(moonId, "0123456789")
        val secondSeed = MoonSeed(moonId, "abcdef0123")

        val entries = mergeMoonSeed(mergeMoonSeed(emptyList(), firstSeed), secondSeed)

        assertEquals(listOf(firstSeed, secondSeed), entries)
        assertEquals(entries, mergeMoonSeed(entries, firstSeed))
    }
}
