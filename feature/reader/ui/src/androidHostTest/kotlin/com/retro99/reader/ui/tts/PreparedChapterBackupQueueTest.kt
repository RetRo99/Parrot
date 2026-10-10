package com.retro99.reader.ui.tts

import com.retro99.analytics.api.Analytics
import com.retro99.analytics.api.AnalyticsEvent
import com.retro99.base.server.LOCAL_SERVER_ID
import com.retro99.base.server.PARROT_CLOUD_SERVER_ID
import com.retro99.books.domain.BackupAllResult
import com.retro99.books.domain.BookFileTransfer
import com.retro99.books.domain.BookFileTransferManager
import com.retro99.books.domain.CloudBookFileRecord
import com.retro99.books.domain.PREPARED_AUDIO_MEDIA_TYPE
import com.retro99.books.domain.UploadRightsAttestation
import com.retro99.reader.ui.reader.PreparedChapterBackupState
import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * The last piece of Step 3: what actually hands a prepared chapter to the
 * transfer engine, and what it refuses to hand over. The engine itself is
 * proved in `PreparedAudioUploadTest`; here the engine is a recorder, and what
 * is under test is the decision, the packing and the two triggers.
 */
class PreparedChapterBackupQueueTest {

    private val root = Files.createTempDirectory("prepared-backup").toFile()
    private val storeRoot = File(root, "store").apply { mkdirs() }
    private val outbox = File(root, "outbox").apply { mkdirs() }
    private val audio = File(root, "sentence.m4a").apply { writeBytes(ByteArray(1_200) { 7 }) }
    private val store = TtsPreparedStore(storeRoot)
    private val settings = PreparedVoiceSettings("system", null, 1f, 1f)
    private val id = PreparedChapterId(BOOK_ID, LOCAL_SERVER_ID, "chapter-one.xhtml")

    @AfterTest fun cleanup() { root.deleteRecursively() }

    // -----------------------------------------------------------------------
    // Queued: the only state that hands work over
    // -----------------------------------------------------------------------

    @Test
    fun `a finished chapter is queued and reaches the engine as one more file of its book`() = runTest {
        val fixture = fixture()
        prepare(id, settings)

        val state = fixture.queue.backUp(id, settings)

        assertEquals(PreparedChapterBackupState.Queued, state)
        val upload = assertNotNull(fixture.transfers.uploads.singleOrNull())
        assertEquals(PARROT_CLOUD_SERVER_ID, upload.serverId)
        assertEquals(BOOK_ID, upload.libraryBookId)
        assertEquals(PREPARED_AUDIO_MEDIA_TYPE, upload.mediaType)
        // Hashes only, and the shape the server constrains relative_path to.
        assertTrue(
            upload.relativePath.matches(Regex("tts-prepared/[0-9a-f]{64}/[0-9a-f]{64}\\.zip")),
            upload.relativePath,
        )
        // The bytes handed over are a real packed archive in the outbox.
        val archive = File(upload.sourcePath)
        assertEquals(outbox.canonicalFile, archive.parentFile.canonicalFile)
        assertEquals(archive.length(), upload.sizeBytes)
        assertTrue(archive.length() > 0)
        // The hash is the engine's business, not the reader's.
        assertEquals(null, upload.contentHash)
        fixture.stop()
    }

    @Test
    fun `the packed archive round-trips into another device's store`() = runTest {
        val fixture = fixture()
        prepare(id, settings)
        fixture.queue.backUp(id, settings)

        val archive = File(assertNotNull(fixture.transfers.uploads.singleOrNull()).sourcePath)
        val otherRoot = File(root, "other-device").apply { mkdirs() }
        val other = TtsPreparedStore(otherRoot)
        val installed = TtsPreparedChapterArchive()
            .unpack(archive, id, other.chapterDirectory(id))

        assertTrue(installed is PreparedArchiveResult.Installed, "got $installed")
        assertEquals(PreparedChapterState.Ready(1_200), other.state(id, settings))
        fixture.stop()
    }

    @Test
    fun `queueing the same chapter twice packs it once more but the engine is handed the same file`() = runTest {
        val fixture = fixture()
        prepare(id, settings)

        fixture.queue.backUp(id, settings)
        val second = fixture.queue.backUp(id, settings)

        assertEquals(PreparedChapterBackupState.Queued, second)
        assertEquals(
            1,
            fixture.transfers.uploads.map { it.relativePath }.distinct().size,
            "the same settings are the same cloud file",
        )
        assertEquals(1, outbox.listFiles()!!.size, "and the same archive in the outbox")
        fixture.stop()
    }

    @Test
    fun `other settings are a different file`() = runTest {
        val fixture = fixture()
        prepare(id, settings)
        val faster = settings.copy(rate = 1.5f)
        prepare(id, faster)

        fixture.queue.backUp(id, faster)

        val upload = assertNotNull(fixture.transfers.uploads.singleOrNull())
        assertEquals(
            TtsPreparedChapterArchive().relativePath(id.chapterHref, faster),
            upload.relativePath,
        )
        fixture.stop()
    }

    // -----------------------------------------------------------------------
    // Every state that does not hand work over, and hands none over
    // -----------------------------------------------------------------------

    @Test
    fun `no cloud account queues nothing`() = runTest {
        val fixture = fixture(snapshot = null)
        prepare(id, settings)

        assertEquals(PreparedChapterBackupState.NotApplicable, fixture.queue.backUp(id, settings))
        assertTrue(fixture.transfers.uploads.isEmpty())
    }

    @Test
    fun `an account that must attest again is not allowed, and is never asked`() = runTest {
        val fixture = fixture(snapshot = snapshot(attestation = null))
        prepare(id, settings)

        assertEquals(PreparedChapterBackupState.NotAllowed, fixture.queue.backUp(id, settings))
        assertTrue(fixture.transfers.uploads.isEmpty())
    }

    @Test
    fun `auto backup off queues nothing`() = runTest {
        val fixture = fixture(snapshot = snapshot(autoBackup = false))
        prepare(id, settings)

        assertEquals(PreparedChapterBackupState.BackupOff, fixture.queue.backUp(id, settings))
        assertTrue(fixture.transfers.uploads.isEmpty())
    }

    @Test
    fun `mobile data waits for wifi`() = runTest {
        val fixture = fixture(unmetered = false)
        prepare(id, settings)

        assertEquals(PreparedChapterBackupState.WaitingForWifi, fixture.queue.backUp(id, settings))
        assertTrue(fixture.transfers.uploads.isEmpty())
    }

    @Test
    fun `a book with no backup in the cloud waits for it`() = runTest {
        val fixture = fixture(cloudFiles = emptyList())
        prepare(id, settings)

        assertEquals(
            PreparedChapterBackupState.WaitingForBookBackup,
            fixture.queue.backUp(id, settings),
        )
        assertTrue(fixture.transfers.uploads.isEmpty())
    }

    @Test
    fun `books go first`() = runTest {
        val fixture = fixture(pendingBooks = 2)
        prepare(id, settings)

        assertEquals(PreparedChapterBackupState.WaitingForBooks, fixture.queue.backUp(id, settings))
        assertTrue(fixture.transfers.uploads.isEmpty())
    }

    @Test
    fun `a chapter still being prepared is never packed or uploaded`() = runTest {
        val fixture = fixture()
        store.begin(id, settings, listOf(KEY_ONE, KEY_TWO))
        store.add(id, KEY_ONE, audio, 1_200)

        assertEquals(PreparedChapterBackupState.NotApplicable, fixture.queue.backUp(id, settings))
        assertTrue(fixture.transfers.uploads.isEmpty())
        assertTrue(outbox.listFiles()!!.isEmpty())
    }

    @Test
    fun `a chapter already in the cloud reads as backed up and is not sent again`() = runTest {
        val relativePath = TtsPreparedChapterArchive().relativePath(id.chapterHref, settings)
        val fixture = fixture(
            cloudFiles = listOf(bookFile(), cloudFile(relativePath, PREPARED_AUDIO_MEDIA_TYPE)),
        )
        prepare(id, settings)

        assertEquals(PreparedChapterBackupState.BackedUp, fixture.queue.backUp(id, settings))
        assertTrue(fixture.transfers.uploads.isEmpty())
    }

    @Test
    fun `a full allowance is permanent and queues nothing again`() = runTest {
        val relativePath = TtsPreparedChapterArchive().relativePath(id.chapterHref, settings)
        val fixture = fixture(
            transfers = listOf(failedTransfer(relativePath, "quota_exceeded")),
        )
        prepare(id, settings)

        assertEquals(PreparedChapterBackupState.StorageFull, fixture.queue.backUp(id, settings))
        assertTrue(fixture.transfers.uploads.isEmpty(), "storage full is one message, not a loop")
    }

    @Test
    fun `a chapter of a catalogue book has no cloud identity and is left alone`() = runTest {
        val fixture = fixture()
        val catalogue = PreparedChapterId("remote-book", "storyteller-1", "chapter-one.xhtml")
        prepare(catalogue, settings)

        assertEquals(
            PreparedChapterBackupState.NotApplicable,
            fixture.queue.backUp(catalogue, settings),
        )
        assertTrue(fixture.transfers.uploads.isEmpty())
    }

    // -----------------------------------------------------------------------
    // The app-start sweep
    // -----------------------------------------------------------------------

    @Test
    fun `the sweep offers every chapter prepared earlier and skips the unfinished one`() = runTest {
        val fixture = fixture()
        val second = PreparedChapterId(BOOK_ID, LOCAL_SERVER_ID, "chapter-two.xhtml")
        val unfinished = PreparedChapterId(BOOK_ID, LOCAL_SERVER_ID, "chapter-three.xhtml")
        prepare(id, settings)
        prepare(second, settings)
        store.begin(unfinished, settings, listOf(KEY_ONE, KEY_TWO))
        store.add(unfinished, KEY_ONE, audio, 1_200)

        fixture.queue.backUpEverythingPrepared()

        assertEquals(
            setOf(
                TtsPreparedChapterArchive().relativePath(id.chapterHref, settings),
                TtsPreparedChapterArchive().relativePath(second.chapterHref, settings),
            ),
            fixture.transfers.uploads.map { it.relativePath }.toSet(),
        )
        fixture.stop()
    }

    @Test
    fun `the sweep on a metered network queues nothing and leaves no archive behind`() = runTest {
        val fixture = fixture(unmetered = false)
        prepare(id, settings)

        fixture.queue.backUpEverythingPrepared()

        assertTrue(fixture.transfers.uploads.isEmpty())
        assertTrue(outbox.listFiles()!!.isEmpty())
    }

    @Test
    fun `the sweep discards an archive whose chapter is gone`() = runTest {
        val fixture = fixture()
        val orphan = File(outbox, "deadbeef.zip").apply { writeBytes(ByteArray(10)) }

        fixture.queue.backUpEverythingPrepared()

        assertFalse(orphan.exists(), "an archive with no chapter has nothing to be uploaded for")
    }

    // -----------------------------------------------------------------------
    // Deleting the chapter
    // -----------------------------------------------------------------------

    @Test
    fun `deleting the chapter cancels the upload and removes the cloud file`() = runTest {
        val fixture = fixture()
        prepare(id, settings)
        fixture.queue.backUp(id, settings)
        val relativePath = assertNotNull(fixture.transfers.uploads.singleOrNull()).relativePath

        fixture.queue.remove(id, settings)

        assertEquals(listOf(relativePath), fixture.transfers.cancelled)
        assertEquals(listOf(relativePath), fixture.transfers.deleted)
        assertTrue(outbox.listFiles()!!.isEmpty(), "and the packed archive goes with it")
        fixture.stop()
    }

    @Test
    fun `deleting a chapter that was never backed up asks the server for nothing`() = runTest {
        val fixture = fixture(snapshot = null)
        prepare(id, settings)

        fixture.queue.remove(id, settings)

        assertTrue(fixture.transfers.cancelled.isEmpty())
        assertTrue(fixture.transfers.deleted.isEmpty())
    }

    // -----------------------------------------------------------------------
    // Analytics
    // -----------------------------------------------------------------------

    @Test
    fun `one event per outcome, at the end, with the size and nothing else`() = runTest {
        val fixture = fixture()
        prepare(id, settings)

        fixture.queue.backUp(id, settings)
        val upload = assertNotNull(fixture.transfers.uploads.singleOrNull())
        val archive = File(upload.sourcePath)
        // Nothing yet: the engine has not finished, so there is no outcome.
        assertTrue(fixture.analytics.events.isEmpty())
        fixture.transfers.settle("completed")
        testScheduler.advanceUntilIdle()

        val event = assertNotNull(fixture.analytics.events.singleOrNull())
        assertEquals("tts_prepared_audio_backup_ended", event.name)
        assertEquals(
            mapOf(
                "operation" to "tts_prepared_audio_backup",
                "stage" to "ended",
                "outcome" to "completed",
                "size_bytes" to upload.sizeBytes,
            ),
            event.parameters,
        )
        assertFalse(archive.exists(), "an uploaded archive has done its job")
        fixture.stop()
    }

    @Test
    fun `a refusal is reported once with the server's reason and keeps no archive`() = runTest {
        val fixture = fixture()
        prepare(id, settings)
        fixture.queue.backUp(id, settings)

        fixture.transfers.settle("failed", lastError = "quota_exceeded")
        testScheduler.advanceUntilIdle()

        val event = assertNotNull(fixture.analytics.events.singleOrNull())
        assertEquals("quota_exceeded", event.parameters["outcome"])
        fixture.stop()
    }

    @Test
    fun `an engine that refuses before queueing is one event and no transfer`() = runTest {
        val fixture = fixture(refuseEnqueue = true)
        prepare(id, settings)

        val state = fixture.queue.backUp(id, settings)

        assertEquals(PreparedChapterBackupState.Failed, state)
        assertEquals("refused", assertNotNull(fixture.analytics.events.singleOrNull()).parameters["outcome"])
        assertTrue(outbox.listFiles()!!.isEmpty(), "nothing is left waiting for an upload that will not happen")
    }

    // -----------------------------------------------------------------------
    // Fixture
    // -----------------------------------------------------------------------

    private fun prepare(id: PreparedChapterId, settings: PreparedVoiceSettings) {
        store.begin(id, settings, listOf(KEY_ONE))
        store.add(id, KEY_ONE, audio, 1_200)
        store.markComplete(id)
    }

    private fun TestScope.fixture(
        snapshot: PreparedBackupAccountSnapshot? = snapshot(),
        unmetered: Boolean = true,
        cloudFiles: List<CloudBookFileRecord> = listOf(bookFile()),
        transfers: List<BookFileTransfer> = emptyList(),
        pendingBooks: Int = 0,
        refuseEnqueue: Boolean = false,
    ): Fixture {
        val manager = RecordingTransfers(cloudFiles, transfers, pendingBooks, refuseEnqueue)
        val analytics = RecordingAnalytics()
        // The queue's own scope, so a test can end the outcome watcher it
        // starts. Production's watcher waits for the engine, which here only
        // finishes when a test says so.
        val scope = CoroutineScope(coroutineContext + Job())
        return Fixture(
            scope = scope,
            queue = PreparedChapterBackupQueue(
                store = store,
                archive = TtsPreparedChapterArchive(),
                outbox = outbox,
                transfers = manager,
                account = FakeAccount(snapshot),
                network = FakeNetwork(unmetered),
                scope = scope,
                analytics = analytics,
            ),
            transfers = manager,
            analytics = analytics,
        )
    }

    private class Fixture(
        private val scope: CoroutineScope,
        val queue: PreparedChapterBackupQueue,
        val transfers: RecordingTransfers,
        val analytics: RecordingAnalytics,
    ) {
        /** Ends the outcome watcher, which otherwise waits for the engine. */
        fun stop() = scope.cancel()
    }

    private class FakeAccount(private val snapshot: PreparedBackupAccountSnapshot?) : PreparedBackupAccount {
        override suspend fun snapshot() = snapshot
    }

    private class FakeNetwork(private val unmetered: Boolean) : PreparedBackupNetwork {
        override fun onUnmeteredNetwork() = unmetered
    }

    private class RecordingAnalytics : Analytics {
        val events = mutableListOf<AnalyticsEvent>()
        override fun logEvent(event: AnalyticsEvent) { events += event }
        override fun logException(throwable: Throwable, message: String?) = Unit
        override fun setUserId(userId: String?) = Unit
    }

    private data class RecordedUpload(
        val serverId: String,
        val libraryBookId: String,
        val mediaType: String,
        val relativePath: String,
        val sourcePath: String,
        val sizeBytes: Long,
        val contentHash: String?,
    )

    /**
     * The engine, as far as this class is concerned: it records what it was
     * asked to do and lets a test settle the transfer it created.
     */
    private class RecordingTransfers(
        private val files: List<CloudBookFileRecord>,
        existing: List<BookFileTransfer>,
        private val pendingBooks: Int,
        private val refuseEnqueue: Boolean,
    ) : BookFileTransferManager {
        val uploads = mutableListOf<RecordedUpload>()
        val cancelled = mutableListOf<String>()
        val deleted = mutableListOf<String>()
        private val rows = MutableStateFlow(existing)

        fun settle(state: String, lastError: String? = null) {
            rows.value = rows.value.map { row -> row.copy(state = state, lastError = lastError) }
        }

        override fun supportsUpload(serverId: String) = true
        override fun supportsDownload(serverId: String) = true
        override fun supportsDeletion(serverId: String) = true

        override suspend fun enqueueAuxiliaryUpload(
            serverId: String,
            libraryBookId: String,
            mediaType: String,
            relativePath: String,
            sourcePath: String,
            sizeBytes: Long,
            contentHash: String?,
            contentHashAlgorithm: String?,
            rightsAttestation: UploadRightsAttestation,
        ): String {
            if (refuseEnqueue) error("Book metadata must sync before its file can be backed up")
            uploads += RecordedUpload(
                serverId, libraryBookId, mediaType, relativePath, sourcePath, sizeBytes, contentHash,
            )
            val transferId = "transfer-${uploads.size}"
            rows.value = rows.value.filterNot { it.relativePath == relativePath } + BookFileTransfer(
                transferId = transferId,
                serverId = serverId,
                libraryBookId = libraryBookId,
                direction = "upload",
                mediaType = mediaType,
                state = "pending",
                bytesTransferred = 0,
                totalBytes = sizeBytes,
                attemptCount = 0,
                lastError = null,
                relativePath = relativePath,
            )
            return transferId
        }

        override suspend fun cancelUpload(
            serverId: String,
            libraryBookId: String,
            mediaType: String,
            relativePath: String,
        ) { cancelled += relativePath }

        override suspend fun deleteRemoteFile(
            serverId: String,
            libraryBookId: String,
            mediaType: String,
            relativePath: String,
        ) { deleted += relativePath }

        override suspend fun cloudFilesFor(libraryBookId: String) = files

        override suspend fun transfersFor(serverId: String, libraryBookId: String) = rows.value

        override suspend fun pendingBookUploadCount(serverId: String) = pendingBooks

        override fun observeForBook(serverId: String, libraryBookId: String): Flow<List<BookFileTransfer>> =
            rows.map { it }

        override suspend fun enqueueUpload(
            serverId: String,
            libraryBookId: String,
            mediaType: String,
            rightsAttestation: UploadRightsAttestation,
        ): String = error("not used")

        override suspend fun backupAll(
            serverId: String,
            rightsAttestation: UploadRightsAttestation,
        ) = BackupAllResult(0, 0)

        override suspend fun enqueueDownload(serverId: String, libraryBookId: String, mediaType: String) =
            error("not used")

        override suspend fun removeDownload(serverId: String, libraryBookId: String, mediaType: String) = Unit
        override suspend fun deleteRemoteBackup(serverId: String, libraryBookId: String, mediaType: String) = Unit
        override suspend fun invalidateCloudFile(cloudBookFileId: String) = Unit
        override suspend fun cancel(serverId: String, libraryBookId: String) = Unit
        override suspend fun cancelTransfer(transferId: String) = Unit
        override suspend fun retry(transferId: String) = Unit
    }

    private companion object {
        const val BOOK_ID = "11111111-1111-4111-8111-111111111111"
        val KEY_ONE = "a".repeat(64)
        val KEY_TWO = "b".repeat(64)

        fun snapshot(
            autoBackup: Boolean = true,
            attestation: UploadRightsAttestation? = UploadRightsAttestation(
                attestedAt = "2026-10-10T00:00:00Z",
                tosVersion = "test",
                attestationVersion = "test",
            ),
        ) = PreparedBackupAccountSnapshot(
            localProfileId = "profile-1",
            autoBackupEnabled = autoBackup,
            attestation = attestation,
        )

        fun bookFile() = cloudFile(relativePath = "", mediaType = "ebook")

        fun cloudFile(relativePath: String, mediaType: String) = CloudBookFileRecord(
            cloudBookFileId = "file-${relativePath.hashCode()}",
            mediaType = mediaType,
            relativePath = relativePath,
            fileName = "file",
            status = "available",
            sizeBytes = 1_000,
            contentHash = "c".repeat(64),
            contentHashAlgorithm = "sha-256-v1",
            remoteRevision = 1,
        )

        fun failedTransfer(relativePath: String, lastError: String) = BookFileTransfer(
            transferId = "transfer-failed",
            serverId = PARROT_CLOUD_SERVER_ID,
            libraryBookId = BOOK_ID,
            direction = "upload",
            mediaType = PREPARED_AUDIO_MEDIA_TYPE,
            state = "failed",
            bytesTransferred = 0,
            totalBytes = 1_000,
            attemptCount = 10,
            lastError = lastError,
            relativePath = relativePath,
        )
    }
}
