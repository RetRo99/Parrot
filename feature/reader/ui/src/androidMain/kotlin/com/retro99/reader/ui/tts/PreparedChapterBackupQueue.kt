package com.retro99.reader.ui.tts

import com.retro99.analytics.api.Analytics
import com.retro99.analytics.api.ReaderAnalyticsEvent
import com.retro99.base.server.LOCAL_SERVER_ID
import com.retro99.base.server.PARROT_CLOUD_SERVER_ID
import com.retro99.books.domain.BookFileTransferManager
import com.retro99.books.domain.PREPARED_AUDIO_MEDIA_TYPE
import com.retro99.reader.ui.reader.PreparedChapterBackupInputs
import com.retro99.reader.ui.reader.PreparedChapterBackupState
import com.retro99.reader.ui.reader.PreparedChapterTransfer
import com.retro99.reader.ui.reader.preparedChapterBackupState
import com.retro99.reader.ui.reader.shouldQueuePreparedChapterUpload
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.coroutines.launch

/**
 * What finally hands a prepared chapter to the transfer engine.
 *
 * It owns no uploading of its own: it gathers the facts, asks the pure
 * [preparedChapterBackupState] decision, and only when that says `Queued` does
 * it pack the chapter (Step 2) and call the engine's auxiliary upload. Every
 * other state is reported back to the caller and nothing happens, which is what
 * makes "storage full" one message rather than a retry loop.
 */
internal class PreparedChapterBackupQueue(
    private val store: TtsPreparedStore,
    private val archive: TtsPreparedChapterArchive,
    /** Where a packed archive waits for the engine. App-owned, not the cache. */
    private val outbox: File,
    private val transfers: BookFileTransferManager,
    private val account: PreparedBackupAccount,
    private val network: PreparedBackupNetwork,
    private val scope: CoroutineScope,
    private val analytics: Analytics? = null,
    /**
     * Called whenever this chapter's upload changes, the last time being when it
     * reaches its end. Nothing else tells the row the transfer engine has moved,
     * so without this the row keeps whatever it last read.
     */
    private val onTransferChanged: () -> Unit = {},
    private val serverId: String = PARROT_CLOUD_SERVER_ID,
) {

    /** What the row should say about this chapter's backup, and why. */
    suspend fun stateOf(id: PreparedChapterId, settings: PreparedVoiceSettings): PreparedChapterBackupState =
        preparedChapterBackupState(gather(id, settings).inputs)

    /**
     * Backs the chapter up if every condition is met, and returns the state
     * either way. Safe to call again: the same chapter with the same settings
     * is the same file, and the engine refuses to upload it twice.
     */
    suspend fun backUp(id: PreparedChapterId, settings: PreparedVoiceSettings): PreparedChapterBackupState {
        val gathered = gather(id, settings)
        val state = preparedChapterBackupState(gathered.inputs)
        if (!shouldQueuePreparedChapterUpload(gathered.inputs)) return state
        val attestation = gathered.attestation ?: return PreparedChapterBackupState.NotAllowed

        val destination = File(outbox, gathered.relativePath.substringAfterLast('/'))
        return when (val packed = archive.pack(store.chapterDirectory(id), destination)) {
            is PreparedArchiveResult.Packed -> {
                try {
                    transfers.enqueueAuxiliaryUpload(
                        serverId = serverId,
                        libraryBookId = id.bookId,
                        mediaType = PREPARED_AUDIO_MEDIA_TYPE,
                        relativePath = packed.relativePath,
                        sourcePath = packed.file.path,
                        sizeBytes = packed.sizeBytes,
                        rightsAttestation = attestation,
                    )
                    watchOutcome(id.bookId, packed.relativePath, packed.sizeBytes, packed.file)
                    PreparedChapterBackupState.Queued
                } catch (exception: CancellationException) {
                    throw exception
                } catch (_: Exception) {
                    // The engine refuses before it writes a row: an unsynced
                    // book, a size that no longer matches, uploads switched
                    // off for this server. Nothing was queued, so nothing will
                    // retry, and the row says so once.
                    destination.delete()
                    log("refused", packed.sizeBytes)
                    PreparedChapterBackupState.Failed
                }
            }

            is PreparedArchiveResult.Rejected -> {
                log("pack_rejected", 0)
                if (packed.reason == PreparedArchiveRejection.CHAPTER_INCOMPLETE) {
                    PreparedChapterBackupState.NotApplicable
                } else {
                    PreparedChapterBackupState.Failed
                }
            }

            is PreparedArchiveResult.Installed -> PreparedChapterBackupState.Failed
        }
    }

    /**
     * Every chapter prepared earlier, in the order it was prepared. Run at app
     * start: a chapter prepared while the device was on mobile data, or before
     * the account was linked, gets its chance here and nowhere else.
     */
    suspend fun backUpEverythingPrepared() {
        val chapters = store.completeChapters()
        if (chapters.isEmpty()) {
            discardOrphanArchives(emptySet())
            return
        }
        val queued = mutableSetOf<String>()
        for (chapter in chapters) {
            queued += archive.relativePath(chapter.id.chapterHref, chapter.settings)
                .substringAfterLast('/')
            try {
                backUp(chapter.id, chapter.settings)
            } catch (exception: CancellationException) {
                throw exception
            } catch (_: Exception) {
                // One unbackable chapter must not stop the rest of the sweep.
            }
        }
        discardOrphanArchives(queued)
    }

    /**
     * Deleting the chapter from the row: the pending upload is cancelled and
     * the cloud file goes with it, because the confirmation says it will.
     */
    suspend fun remove(id: PreparedChapterId, settings: PreparedVoiceSettings) {
        val relativePath = archive.relativePath(id.chapterHref, settings)
        File(outbox, relativePath.substringAfterLast('/')).delete()
        // With no cloud account there is nothing of this chapter in any cloud,
        // so there is nothing to ask any server for.
        if (!backupPossibleFor(id) || account.snapshot() == null) return
        try {
            transfers.cancelUpload(serverId, id.bookId, PREPARED_AUDIO_MEDIA_TYPE, relativePath)
            transfers.deleteRemoteFile(serverId, id.bookId, PREPARED_AUDIO_MEDIA_TYPE, relativePath)
        } catch (exception: CancellationException) {
            throw exception
        } catch (_: Exception) {
            // The local copy is already gone, which is what the user asked
            // for. The server's own cascade removes the row when its book goes.
        }
    }

    // -----------------------------------------------------------------------

    private class Gathered(
        val inputs: PreparedChapterBackupInputs,
        val relativePath: String,
        val attestation: com.retro99.books.domain.UploadRightsAttestation?,
    )

    private suspend fun gather(id: PreparedChapterId, settings: PreparedVoiceSettings): Gathered {
        val relativePath = archive.relativePath(id.chapterHref, settings)
        val complete = store.state(id, settings) is PreparedChapterState.Ready
        if (!backupPossibleFor(id)) {
            return Gathered(PreparedChapterBackupInputs(chapterComplete = complete), relativePath, null)
        }
        val snapshot = account.snapshot()
            ?: return Gathered(PreparedChapterBackupInputs(chapterComplete = complete), relativePath, null)

        val files = transfers.cloudFilesFor(id.bookId)
        val ours = transfers.transfersFor(serverId, id.bookId).lastOrNull { transfer ->
            transfer.direction == DIRECTION_UPLOAD &&
                transfer.relativePath == relativePath &&
                transfer.mediaType.equals(PREPARED_AUDIO_MEDIA_TYPE, ignoreCase = true)
        }
        return Gathered(
            inputs = PreparedChapterBackupInputs(
                signedIn = true,
                uploadsAllowed = transfers.supportsUpload(serverId) && snapshot.attestation != null,
                autoBackupEnabled = snapshot.autoBackupEnabled,
                onUnmeteredNetwork = network.onUnmeteredNetwork(),
                bookBackedUp = files.any { file ->
                    !file.mediaType.equals(PREPARED_AUDIO_MEDIA_TYPE, ignoreCase = true) &&
                        file.status == STATUS_AVAILABLE
                },
                booksWaitingToUpload = transfers.pendingBookUploadCount(serverId),
                chapterComplete = complete,
                alreadyBackedUp = files.any { file ->
                    file.relativePath == relativePath && file.status == STATUS_AVAILABLE
                },
                transfer = ours?.let { transfer ->
                    PreparedChapterTransfer(
                        state = transfer.state,
                        willRetry = transfer.willRetry,
                        lastError = transfer.lastError,
                    )
                },
            ),
            relativePath = relativePath,
            attestation = snapshot.attestation,
        )
    }

    /**
     * Only a book of this device's own library has a cloud identity. A chapter
     * prepared from a catalogue book is identified by that server's id, which
     * means nothing to Parrot Cloud, so it is never backed up.
     */
    private fun backupPossibleFor(id: PreparedChapterId): Boolean =
        id.serverId == LOCAL_SERVER_ID && id.bookId.isNotEmpty()

    /**
     * One event per outcome, at the end: the engine owns the attempts, so
     * waiting for its terminal state is the only way to report the outcome
     * rather than the intention.
     */
    private fun watchOutcome(
        libraryBookId: String,
        relativePath: String,
        sizeBytes: Long,
        archiveFile: File,
    ) {
        val analytics = analytics ?: return
        scope.launch {
            try {
                val terminal = transfers.observeForBook(serverId, libraryBookId)
                    .mapNotNull { rows ->
                        rows.firstOrNull { transfer ->
                            transfer.relativePath == relativePath &&
                                transfer.state in TERMINAL_STATES
                        }
                    }
                    .first()
                analytics.logEvent(
                    ReaderAnalyticsEvent.TtsPreparedAudioBackupEnded(
                        outcome = terminal.lastError ?: terminal.state,
                        sizeBytes = sizeBytes,
                    ),
                )
                // The archive existed only to be uploaded.
                if (terminal.state == STATE_COMPLETED) archiveFile.delete()
            } catch (exception: CancellationException) {
                throw exception
            } catch (_: Exception) {
                // No event rather than a wrong one.
            }
        }
    }

    private fun log(outcome: String, sizeBytes: Long) {
        analytics?.logEvent(
            ReaderAnalyticsEvent.TtsPreparedAudioBackupEnded(outcome = outcome, sizeBytes = sizeBytes),
        )
    }

    /** An archive whose chapter is gone has nothing left to be uploaded for. */
    private fun discardOrphanArchives(keep: Set<String>) {
        outbox.listFiles()?.forEach { file -> if (file.name !in keep) file.delete() }
    }

    private companion object {
        const val DIRECTION_UPLOAD = "upload"
        const val STATUS_AVAILABLE = "available"
        const val STATE_COMPLETED = "completed"
        val TERMINAL_STATES = setOf(STATE_COMPLETED, "failed", "cancelled")
    }
}
