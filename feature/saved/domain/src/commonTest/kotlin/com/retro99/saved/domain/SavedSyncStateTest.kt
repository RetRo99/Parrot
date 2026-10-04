package com.retro99.saved.domain

import com.retro99.cloudaccount.domain.model.CloudAccount
import com.retro99.cloudaccount.domain.model.CloudAuthState
import com.retro99.saved.domain.model.SavedSyncState
import com.retro99.saved.domain.usecase.savedSyncState
import com.retro99.sync.domain.SyncStatus
import kotlin.test.Test
import kotlin.test.assertEquals

class SavedSyncStateTest {

    @Test
    fun `signed out users are asked to sign in`() {
        assertEquals(
            SavedSyncState.SignedOut,
            savedSyncState(CloudAuthState.SignedOut, SyncStatus.Disabled, pendingCount = 3),
        )
    }

    @Test
    fun `nothing pending reads as synced with the last sync time`() {
        assertEquals(
            SavedSyncState.Synced("2026-10-04T12:00:00Z"),
            savedSyncState(signedIn(), SyncStatus.Idle("2026-10-04T12:00:00Z"), pendingCount = 0),
        )
    }

    @Test
    fun `pending changes while offline wait for the network`() {
        assertEquals(
            SavedSyncState.WaitingForNetwork,
            savedSyncState(signedIn(), SyncStatus.Offline(pendingCount = 2), pendingCount = 2),
        )
    }

    private fun signedIn(): CloudAuthState = CloudAuthState.SignedIn(
        account = CloudAccount(id = "user-1", email = null),
    )
}
