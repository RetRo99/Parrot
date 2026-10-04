package com.retro99.saved.domain.model

/** What the end of a saved list says about Parrot Cloud. */
sealed interface SavedSyncState {
    /** Auth is still being restored; say nothing yet. */
    data object Unknown : SavedSyncState

    /** "Sign in to sync across devices". */
    data object SignedOut : SavedSyncState

    /** "✓ Synced to Parrot Cloud", with the last successful sync time when known. */
    data class Synced(val lastSyncedAt: String?) : SavedSyncState

    /** Changes are on their way. */
    data object Syncing : SavedSyncState

    /** "Will sync when you're online". */
    data object WaitingForNetwork : SavedSyncState
}
