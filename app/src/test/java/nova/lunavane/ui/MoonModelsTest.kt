package nova.lunavane.ui

import nova.lunavane.MoonSeed
import org.junit.Assert.assertEquals
import org.junit.Test

class MoonModelsTest {
    @Test
    fun `groups multiple seeds under the same world`() {
        val groups = groupMoonSeeds(
            listOf(
                MoonSeed("bbbbbbbbbbbbbbbb", "0000000002"),
                MoonSeed("aaaaaaaaaaaaaaaa", "0000000004"),
                MoonSeed("aaaaaaaaaaaaaaaa", "0000000001"),
                MoonSeed("aaaaaaaaaaaaaaaa", "0000000001"),
            ),
        )
        assertEquals(
            listOf(
                MoonWorldGroup("aaaaaaaaaaaaaaaa", listOf("0000000001", "0000000004")),
                MoonWorldGroup("bbbbbbbbbbbbbbbb", listOf("0000000002")),
            ),
            groups,
        )
    }

    @Test
    fun `empty input produces no groups`() {
        assertEquals(emptyList<MoonWorldGroup>(), groupMoonSeeds(emptyList()))
    }
}
