package nova.lunavane

import org.junit.Assert.assertEquals
import org.junit.Test

class MoonOperationPolicyTest {
    @Test
    fun offlineOrbitSaveWaitsForCoreInsteadOfClaimingAcceptance() {
        assertEquals(MoonOperationOutcome.SAVED_PENDING, offlineMoonSaveOutcome(persisted = true))
        assertEquals(MoonOperationOutcome.SAVE_FAILED, offlineMoonSaveOutcome(persisted = false))
    }

    @Test
    fun localRemovalReportsPersistenceSeparatelyFromCoreDeorbit() {
        assertEquals(MoonOperationOutcome.LOCAL_REMOVED, localMoonRemovalOutcome(persisted = true))
        assertEquals(MoonOperationOutcome.LOCAL_REMOVE_FAILED, localMoonRemovalOutcome(persisted = false))
    }
}
