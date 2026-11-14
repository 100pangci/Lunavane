package nova.lunavane

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class NetworkProfilesTest {
    @Test
    fun migratesLegacyNetworkWithoutDuplicateAndKeepsSelection() {
        val existing = SavedNetwork("aaaaaaaaaaaaaaaa", name = "Home", createdAtMillis = 10L)
        val state = NetworkProfileState(listOf(existing), existing.networkId)

        val migrated = migrateLegacyNetwork(state, "8056C2E21C000001", nowMillis = 20L)

        assertEquals(listOf(existing, SavedNetwork("8056c2e21c000001", createdAtMillis = 20L)), migrated.profiles)
        assertEquals(existing.networkId, migrated.selectedNetworkId)
        assertEquals(migrated, migrateLegacyNetwork(migrated, "8056c2e21c000001", nowMillis = 30L))
    }

    @Test
    fun rejectsInvalidAndDuplicateNetworkIds() {
        val state = addSavedNetwork(NetworkProfileState(), "8056C2E21C000001", "Home", 10L)!!

        assertNull(addSavedNetwork(state, "8056c2e21c000001", "Duplicate", 20L))
        assertNull(addSavedNetwork(state, "not-a-network-id", "Invalid", 20L))
        assertEquals("8056c2e21c000001", state.profiles.single().networkId)
    }

    @Test
    fun renamesAndSelectsSavedNetworks() {
        val home = SavedNetwork("aaaaaaaaaaaaaaaa", "Home", createdAtMillis = 10L)
        val lab = SavedNetwork("bbbbbbbbbbbbbbbb", createdAtMillis = 20L)
        val state = NetworkProfileState(listOf(home, lab), home.networkId)

        val updated = selectSavedNetwork(renameSavedNetwork(state, lab.networkId, " Lab "), lab.networkId)

        assertEquals("Lab", updated.profiles.last().name)
        assertEquals(lab.networkId, updated.selectedNetworkId)
    }

    @Test
    fun removesSavedNetworkAndChoosesNextProfileWhenSelectedOneIsDeleted() {
        val first = SavedNetwork("aaaaaaaaaaaaaaaa", createdAtMillis = 10L)
        val selected = SavedNetwork("bbbbbbbbbbbbbbbb", createdAtMillis = 20L)
        val state = NetworkProfileState(listOf(first, selected), selected.networkId)

        val updated = removeSavedNetwork(state, selected.networkId)

        assertEquals(listOf(first), updated.profiles)
        assertEquals(first.networkId, updated.selectedNetworkId)
        assertEquals(state, removeSavedNetwork(state, "cccccccccccccccc"))
    }

    @Test
    fun recordsUseTimeAndChangesDisplayOrder() {
        val first = SavedNetwork("aaaaaaaaaaaaaaaa", createdAtMillis = 10L)
        val second = SavedNetwork("bbbbbbbbbbbbbbbb", createdAtMillis = 20L)
        val state = NetworkProfileState(listOf(first, second), first.networkId)

        val reordered = moveSavedNetwork(state, second.networkId, -1)
        val marked = markSavedNetworkUsed(reordered, second.networkId, 100L)

        assertEquals(listOf(second.copy(lastUsedAtMillis = 100L), first), marked.profiles)
        assertEquals(100L, marked.profiles.first().lastUsedAtMillis)
    }
}
