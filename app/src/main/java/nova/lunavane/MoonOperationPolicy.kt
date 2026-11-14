package nova.lunavane

internal fun offlineMoonSaveOutcome(persisted: Boolean): MoonOperationOutcome =
    if (persisted) MoonOperationOutcome.SAVED_PENDING else MoonOperationOutcome.SAVE_FAILED

internal fun localMoonRemovalOutcome(persisted: Boolean): MoonOperationOutcome =
    if (persisted) MoonOperationOutcome.LOCAL_REMOVED else MoonOperationOutcome.LOCAL_REMOVE_FAILED
