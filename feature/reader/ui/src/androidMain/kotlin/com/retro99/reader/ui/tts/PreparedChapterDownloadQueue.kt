package com.retro99.reader.ui.tts

import com.retro99.base.server.LOCAL_SERVER_ID
import com.retro99.base.server.PARROT_CLOUD_SERVER_ID
import com.retro99.books.domain.BookFileTransferManager
import com.retro99.books.domain.CloudBookFileRecord
import com.retro99.books.domain.PREPARED_AUDIO_MEDIA_TYPE
import com.retro99.reader.ui.reader.PreparedChapterCloudAudio
import com.retro99.reader.ui.reader.PreparedChapterDownloadInputs
import com.retro99.reader.ui.reader.PreparedChapterDownloadState
import com.retro99.reader.ui.reader.PreparedChapterTransfer
import com.retro99.reader.ui.reader.preparedChapterDownloadState
import com.retro99.reader.ui.reader.shouldStartPreparedChapterDownload
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * The other half of Step 3's queue: a chapter another device prepared, fetched
 * on request and only on request.
 *
 * A device learns what the cloud holds from `cloud_book_files` rows it already
 * syncs, grouped by the chapter-href hash in the relative path. It cannot read
 * the voice and speed back out of that path, because they are a hash too, so
 * what it can say is whether the archive matches the settings now selected.
 *
 * Nothing is ever installed that has not been verified twice: the engine checks
 * the cloud's content hash before the bytes reach the inbox, and
 * [TtsPreparedChapterArchive.unpack] checks the archive against the manifest
 * and the chapter it is being installed into before anything is moved into the
 * prepared store.
 */
internal class PreparedChapterDownloadQueue(
    private val store: TtsPreparedStore,
    private val archive: TtsPreparedChapterArchive,
    /** Where a fetched archive lands before it is verified and unpacked. */
    private val inbox: File,
    private val transfers: BookFileTransferManager,
    private val account: PreparedBackupAccount,
    private val freeBytes: () -> Long,
    private val scope: CoroutineScope,
    /**
     * Called whenever this chapter's download changes, the last time being when
     * it is installed or refused. See [PreparedChapterBackupQueue].
     */
    private val onTransferChanged: () -> Unit = {},
    private val serverId: String = PARROT_CLOUD_SERVER_ID,
) {
    /**
     * Archives this device fetched and then refused. In memory only: after a
     * restart the row offers Download again, which is the right offer, because
     * the cloud may hold a different file by then.
     */
    private val rejected = mutableSetOf<String>()
    private val installing = Mutex()

    /** What the cloud holds for this chapter, or null for nothing. */
    suspend fun cloudAudioFor(
        id: PreparedChapterId,
        settings: PreparedVoiceSettings,
    ): PreparedChapterCloudAudio? = chosen(id, settings)?.let { file ->
        PreparedChapterCloudAudio(
            sizeBytes = file.sizeBytes,
            forCurrentSettings = file.relativePath == archive.relativePath(id.chapterHref, settings),
        )
    }

    suspend fun stateOf(
        id: PreparedChapterId,
        settings: PreparedVoiceSettings,
    ): PreparedChapterDownloadState = preparedChapterDownloadState(gather(id, settings))

    /**
     * Fetches the chapter if there is something to fetch and this device can.
     * Returns the state either way, so the row never has to guess.
     */
    suspend fun download(
        id: PreparedChapterId,
        settings: PreparedVoiceSettings,
    ): PreparedChapterDownloadState {
        val inputs = gather(id, settings)
        if (!shouldStartPreparedChapterDownload(inputs)) return preparedChapterDownloadState(inputs)
        val file = chosen(id, settings) ?: return PreparedChapterDownloadState.NotInCloud

        val destination = File(inbox, file.relativePath.substringAfterLast('/'))
        inbox.mkdirs()
        return try {
            rejected -= file.relativePath
            transfers.enqueueAuxiliaryDownload(
                serverId = serverId,
                libraryBookId = id.bookId,
                mediaType = PREPARED_AUDIO_MEDIA_TYPE,
                relativePath = file.relativePath,
                destinationPath = destination.path,
            )
            watchAndInstall(id, file.relativePath, destination)
            PreparedChapterDownloadState.Downloading
        } catch (exception: CancellationException) {
            throw exception
        } catch (_: Exception) {
            // The engine refuses before it writes a row: the file is not
            // available any more, or it is empty. Nothing was queued.
            destination.delete()
            PreparedChapterDownloadState.FailedGoneFromCloud
        }
    }

    /**
     * Installs a fetched archive. Public for the sake of the app-start sweep
     * below and of a test; the normal path is [watchAndInstall].
     */
    suspend fun install(
        id: PreparedChapterId,
        relativePath: String,
        downloaded: File,
    ): PreparedChapterDownloadState = installing.withLock {
        val result = archive.unpack(downloaded, id, store.chapterDirectory(id))
        downloaded.delete()
        when (result) {
            is PreparedArchiveResult.Installed -> {
                rejected -= relativePath
                PreparedChapterDownloadState.Installed
            }

            else -> {
                // Nothing was installed and nothing already installed was
                // disturbed; the archive's own tests pin that.
                rejected += relativePath
                PreparedChapterDownloadState.FailedArchiveRejected
            }
        }
    }

    /**
     * An archive that arrived while the app was last running but was never
     * unpacked -- the process died in between -- is thrown away rather than
     * trusted, because nothing records which chapter it was for.
     */
    fun discardUninstalledDownloads() {
        inbox.listFiles()?.forEach { file -> file.delete() }
    }

    // -----------------------------------------------------------------------

    private suspend fun gather(
        id: PreparedChapterId,
        settings: PreparedVoiceSettings,
    ): PreparedChapterDownloadInputs {
        val local = store.state(id, settings)
        val installedLocally = local is PreparedChapterState.Ready ||
            (local is PreparedChapterState.OtherSettings && local.complete)
        if (installedLocally) return PreparedChapterDownloadInputs(installedLocally = true)
        if (!cloudPossibleFor(id) || account.snapshot() == null) {
            return PreparedChapterDownloadInputs()
        }
        val file = chosen(id, settings) ?: return PreparedChapterDownloadInputs()
        val ours = transfers.transfersFor(serverId, id.bookId).lastOrNull { transfer ->
            transfer.direction == DIRECTION_DOWNLOAD && transfer.relativePath == file.relativePath
        }
        return PreparedChapterDownloadInputs(
            cloudAudio = PreparedChapterCloudAudio(
                sizeBytes = file.sizeBytes,
                forCurrentSettings = file.relativePath == archive.relativePath(id.chapterHref, settings),
            ),
            installedLocally = false,
            transfer = ours?.let { transfer ->
                PreparedChapterTransfer(
                    state = transfer.state,
                    willRetry = transfer.willRetry,
                    lastError = transfer.lastError,
                )
            },
            freeBytesOnDevice = freeBytes(),
            archiveRejected = file.relativePath in rejected,
        )
    }

    /**
     * The chapter's audio in the cloud. When the cloud holds more than one
     * version of the same chapter, the one made for the settings now selected
     * wins, because that is the one that will play instantly.
     */
    private suspend fun chosen(
        id: PreparedChapterId,
        settings: PreparedVoiceSettings,
    ): CloudBookFileRecord? {
        if (!cloudPossibleFor(id)) return null
        val prefix = archive.chapterPathPrefix(id.chapterHref)
        val exact = archive.relativePath(id.chapterHref, settings)
        val candidates = transfers.cloudFilesFor(id.bookId).filter { file ->
            file.mediaType.equals(PREPARED_AUDIO_MEDIA_TYPE, ignoreCase = true) &&
                file.status == STATUS_AVAILABLE &&
                file.relativePath.startsWith(prefix) &&
                file.sizeBytes > 0
        }
        return candidates.firstOrNull { file -> file.relativePath == exact }
            ?: candidates.minByOrNull { file -> file.relativePath }
    }

    private fun cloudPossibleFor(id: PreparedChapterId): Boolean =
        id.serverId == LOCAL_SERVER_ID && id.bookId.isNotEmpty()

    private fun watchAndInstall(id: PreparedChapterId, relativePath: String, destination: File) {
        scope.launch {
            try {
                val terminal = transfers.observeForBook(serverId, id.bookId)
                    .mapNotNull { rows ->
                        rows.firstOrNull { transfer ->
                            transfer.direction == DIRECTION_DOWNLOAD &&
                                transfer.relativePath == relativePath &&
                                transfer.state in TERMINAL_STATES
                        }
                    }
                    .first()
                if (terminal.state == STATE_COMPLETED) install(id, relativePath, destination)
                else destination.delete()
            } catch (exception: CancellationException) {
                throw exception
            } catch (_: Exception) {
                destination.delete()
            }
        }
    }

    private companion object {
        const val DIRECTION_DOWNLOAD = "download"
        const val STATUS_AVAILABLE = "available"
        const val STATE_COMPLETED = "completed"
        val TERMINAL_STATES = setOf(STATE_COMPLETED, "failed", "cancelled")
    }
}
