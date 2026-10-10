package com.retro99.reader.ui.reader

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PreparedChapterBackupStateTest {

    /** Every condition met: the one case that actually queues an upload. */
    private val ready = PreparedChapterBackupInputs(
        signedIn = true,
        uploadsAllowed = true,
        autoBackupEnabled = true,
        onUnmeteredNetwork = true,
        bookBackedUp = true,
        booksWaitingToUpload = 0,
        chapterComplete = true,
    )

    @Test
    fun `a finished chapter on wifi with its book backed up is queued`() {
        assertEquals(PreparedChapterBackupState.Queued, preparedChapterBackupState(ready))
        assertTrue(shouldQueuePreparedChapterUpload(ready))
    }

    @Test
    fun `queueing is the only state that hands work to the engine`() {
        val states = PreparedChapterBackupState.entries.associateWith { state ->
            inputsFor(state)
        }
        states.forEach { (state, inputs) ->
            assertEquals(state, preparedChapterBackupState(inputs), "built the wrong inputs for $state")
            assertEquals(
                state == PreparedChapterBackupState.Queued,
                shouldQueuePreparedChapterUpload(inputs),
                "$state should ${if (state == PreparedChapterBackupState.Queued) "" else "not "}queue",
            )
        }
    }

    // -----------------------------------------------------------------------
    // The gates, outermost first
    // -----------------------------------------------------------------------

    @Test
    fun `no account says nothing at all`() {
        assertEquals(
            PreparedChapterBackupState.NotApplicable,
            preparedChapterBackupState(ready.copy(signedIn = false)),
        )
    }

    @Test
    fun `an account that may not upload is told once and never queued`() {
        val inputs = ready.copy(uploadsAllowed = false)
        assertEquals(PreparedChapterBackupState.NotAllowed, preparedChapterBackupState(inputs))
        assertFalse(shouldQueuePreparedChapterUpload(inputs))
    }

    @Test
    fun `auto-backup off is the user's own choice and not a failure`() {
        assertEquals(
            PreparedChapterBackupState.BackupOff,
            preparedChapterBackupState(ready.copy(autoBackupEnabled = false)),
        )
    }

    @Test
    fun `a partly prepared chapter is never queued`() {
        val inputs = ready.copy(chapterComplete = false)
        assertEquals(PreparedChapterBackupState.NotApplicable, preparedChapterBackupState(inputs))
        assertFalse(shouldQueuePreparedChapterUpload(inputs))
    }

    @Test
    fun `audio waits for its book's backup`() {
        assertEquals(
            PreparedChapterBackupState.WaitingForBookBackup,
            preparedChapterBackupState(ready.copy(bookBackedUp = false)),
        )
    }

    @Test
    fun `books go first`() {
        assertEquals(
            PreparedChapterBackupState.WaitingForBooks,
            preparedChapterBackupState(ready.copy(booksWaitingToUpload = 2)),
        )
    }

    @Test
    fun `off wifi it waits`() {
        assertEquals(
            PreparedChapterBackupState.WaitingForWifi,
            preparedChapterBackupState(ready.copy(onUnmeteredNetwork = false)),
        )
    }

    @Test
    fun `the book's backup is wanted before wifi because only one of them is the user's to fix`() {
        assertEquals(
            PreparedChapterBackupState.WaitingForBookBackup,
            preparedChapterBackupState(ready.copy(bookBackedUp = false, onUnmeteredNetwork = false)),
        )
    }

    // -----------------------------------------------------------------------
    // What already happened beats what might
    // -----------------------------------------------------------------------

    @Test
    fun `a chapter already in the cloud stays backed up whatever the switches now say`() {
        val inputs = PreparedChapterBackupInputs(alreadyBackedUp = true)
        assertEquals(PreparedChapterBackupState.BackedUp, preparedChapterBackupState(inputs))
        assertFalse(shouldQueuePreparedChapterUpload(inputs))
    }

    @Test
    fun `a completed transfer is backed up`() {
        assertEquals(
            PreparedChapterBackupState.BackedUp,
            preparedChapterBackupState(ready.copy(transfer = PreparedChapterTransfer("completed"))),
        )
    }

    @Test
    fun `a full allowance is reported even after the device leaves wifi`() {
        val inputs = ready.copy(
            onUnmeteredNetwork = false,
            transfer = PreparedChapterTransfer("failed", lastError = "quota_exceeded"),
        )
        assertEquals(PreparedChapterBackupState.StorageFull, preparedChapterBackupState(inputs))
    }

    @Test
    fun `storage full never queues again on its own`() {
        val inputs = ready.copy(
            transfer = PreparedChapterTransfer("failed", lastError = "quota_exceeded"),
        )
        assertEquals(PreparedChapterBackupState.StorageFull, preparedChapterBackupState(inputs))
        assertFalse(shouldQueuePreparedChapterUpload(inputs))
    }

    @Test
    fun `being backed up outranks even a full allowance`() {
        val inputs = ready.copy(
            alreadyBackedUp = true,
            transfer = PreparedChapterTransfer("failed", lastError = "quota_exceeded"),
        )
        assertEquals(PreparedChapterBackupState.BackedUp, preparedChapterBackupState(inputs))
    }

    // -----------------------------------------------------------------------
    // Transfers in flight and transfers that stopped
    // -----------------------------------------------------------------------

    @Test
    fun `a transfer in flight is uploading`() {
        assertEquals(
            PreparedChapterBackupState.Uploading,
            preparedChapterBackupState(ready.copy(transfer = PreparedChapterTransfer("transferring"))),
        )
        assertEquals(
            PreparedChapterBackupState.Uploading,
            preparedChapterBackupState(ready.copy(transfer = PreparedChapterTransfer("finalizing"))),
        )
    }

    @Test
    fun `a transfer waiting to try again says so`() {
        val inputs = ready.copy(
            transfer = PreparedChapterTransfer("pending", willRetry = true, lastError = "network"),
        )
        assertEquals(PreparedChapterBackupState.FailedWillRetry, preparedChapterBackupState(inputs))
        assertFalse(shouldQueuePreparedChapterUpload(inputs), "the engine already owns this one")
    }

    @Test
    fun `a transfer out of attempts has failed`() {
        assertEquals(
            PreparedChapterBackupState.Failed,
            preparedChapterBackupState(ready.copy(transfer = PreparedChapterTransfer("failed"))),
        )
    }

    @Test
    fun `a refusal retrying cannot fix has failed and is never queued again`() {
        listOf(
            "uploads_not_enabled",
            "invalid_upload_metadata",
            "file_too_large",
            "content_blocked",
            "cloud_book_not_owned",
        ).forEach { reason ->
            val inputs = ready.copy(
                transfer = PreparedChapterTransfer("failed", lastError = reason),
            )
            assertEquals(PreparedChapterBackupState.Failed, preparedChapterBackupState(inputs), reason)
            assertFalse(shouldQueuePreparedChapterUpload(inputs), reason)
        }
    }

    @Test
    fun `the server refusing for want of a book backup says what the gate says`() {
        // The same fact, and the gate's wording is the one the user can act on.
        val inputs = ready.copy(
            transfer = PreparedChapterTransfer("failed", lastError = "book_backup_unavailable"),
        )
        assertEquals(PreparedChapterBackupState.WaitingForBookBackup, preparedChapterBackupState(inputs))
    }

    @Test
    fun `a cancelled transfer is simply queued again`() {
        val inputs = ready.copy(transfer = PreparedChapterTransfer("cancelled"))
        assertEquals(PreparedChapterBackupState.Queued, preparedChapterBackupState(inputs))
        assertTrue(shouldQueuePreparedChapterUpload(inputs))
    }

    /** One set of inputs per state, so the table test above covers all of them. */
    private fun inputsFor(state: PreparedChapterBackupState): PreparedChapterBackupInputs = when (state) {
        PreparedChapterBackupState.NotApplicable -> ready.copy(signedIn = false)
        PreparedChapterBackupState.NotAllowed -> ready.copy(uploadsAllowed = false)
        PreparedChapterBackupState.BackupOff -> ready.copy(autoBackupEnabled = false)
        PreparedChapterBackupState.WaitingForBooks -> ready.copy(booksWaitingToUpload = 1)
        PreparedChapterBackupState.WaitingForBookBackup -> ready.copy(bookBackedUp = false)
        PreparedChapterBackupState.WaitingForWifi -> ready.copy(onUnmeteredNetwork = false)
        PreparedChapterBackupState.Queued -> ready
        PreparedChapterBackupState.Uploading -> ready.copy(transfer = PreparedChapterTransfer("transferring"))
        PreparedChapterBackupState.BackedUp -> ready.copy(alreadyBackedUp = true)
        PreparedChapterBackupState.StorageFull ->
            ready.copy(transfer = PreparedChapterTransfer("failed", lastError = "quota_exceeded"))
        PreparedChapterBackupState.FailedWillRetry ->
            ready.copy(transfer = PreparedChapterTransfer("pending", willRetry = true))
        PreparedChapterBackupState.Failed -> ready.copy(transfer = PreparedChapterTransfer("failed"))
    }
}
