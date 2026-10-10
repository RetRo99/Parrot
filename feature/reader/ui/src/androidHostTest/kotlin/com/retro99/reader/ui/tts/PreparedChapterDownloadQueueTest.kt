package com.retro99.reader.ui.tts

import com.retro99.base.server.LOCAL_SERVER_ID
import com.retro99.base.server.PARROT_CLOUD_SERVER_ID
import com.retro99.books.domain.BackupAllResult
import com.retro99.books.domain.BookFileTransfer
import com.retro99.books.domain.BookFileTransferManager
import com.retro99.books.domain.CloudBookFileRecord
import com.retro99.books.domain.PREPARED_AUDIO_MEDIA_TYPE
import com.retro99.books.domain.UploadRightsAttestation
import com.retro99.reader.ui.reader.PreparedChapterDownloadState
import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Step 4 on the device: what the row learns the cloud holds, and what pressing
 * Download does. The engine is a recorder here -- `PreparedAudioDownloadTest`
 * proves the real one -- so what is under test is the discovery, the decision,
 * the verification and the install.
 */
class PreparedChapterDownloadQueueTest {

    private val root = Files.createTempDirectory("prepared-download").toFile()
    private val storeRoot = File(root, "store").apply { mkdirs() }
    private val inbox = File(root, "inbox").apply { mkdirs() }
    private val sourceRoot = File(root, "source").apply { mkdirs() }
    private val audio = File(root, "sentence.m4a").apply { writeBytes(ByteArray(1_200) { 7 }) }
    private val store = TtsPreparedStore(storeRoot)
    private val archive = TtsPreparedChapterArchive()
    private val settings = PreparedVoiceSettings("system", null, 1f, 1f)
    private val faster = PreparedVoiceSettings("system", null, 1.5f, 1f)
    private val id = PreparedChapterId(BOOK_ID, LOCAL_SERVER_ID, "chapter-one.xhtml")

    @AfterTest fun cleanup() { root.deleteRecursively() }

    // -----------------------------------------------------------------------
    // What the device learns
    // -----------------------------------------------------------------------

    @Test
    fun `a chapter the cloud holds for the current settings is offered with its size`() = runTest {
        val packed = packOnAnotherDevice(settings)
        val fixture = fixture(cloudFiles = listOf(bookFile(), chapterFile(packed)))

        val audio = assertNotNull(fixture.queue.cloudAudioFor(id, settings))

        assertEquals(packed.length(), audio.sizeBytes)
        assertTrue(audio.forCurrentSettings)
        assertEquals(PreparedChapterDownloadState.AvailableInCloud, fixture.queue.stateOf(id, settings))
        fixture.stop()
    }

    @Test
    fun `a chapter the cloud holds for other settings is found and says so`() = runTest {
        val packed = packOnAnotherDevice(faster)
        val fixture = fixture(cloudFiles = listOf(bookFile(), chapterFile(packed, faster)))

        val audio = assertNotNull(fixture.queue.cloudAudioFor(id, settings))

        assertFalse(
            audio.forCurrentSettings,
            "the path carries a hash of the settings, so this is all the device can know",
        )
        assertEquals(PreparedChapterDownloadState.AvailableInCloud, fixture.queue.stateOf(id, settings))
        fixture.stop()
    }

    @Test
    fun `when the cloud holds both, the one for the current settings is chosen`() = runTest {
        val mine = packOnAnotherDevice(settings)
        val other = packOnAnotherDevice(faster)
        val fixture = fixture(
            cloudFiles = listOf(bookFile(), chapterFile(other, faster), chapterFile(mine, settings)),
        )

        fixture.queue.download(id, settings)

        assertEquals(
            archive.relativePath(id.chapterHref, settings),
            assertNotNull(fixture.transfers.downloads.singleOrNull()).relativePath,
        )
        fixture.stop()
    }

    @Test
    fun `another chapter's audio is never mistaken for this one`() = runTest {
        val packed = packOnAnotherDevice(settings, href = "chapter-two.xhtml")
        val fixture = fixture(
            cloudFiles = listOf(
                bookFile(),
                chapterFile(packed, settings, href = "chapter-two.xhtml"),
            ),
        )

        assertNull(fixture.queue.cloudAudioFor(id, settings))
        assertEquals(PreparedChapterDownloadState.NotInCloud, fixture.queue.stateOf(id, settings))
        fixture.stop()
    }

    @Test
    fun `the book's own backup is not prepared audio`() = runTest {
        val fixture = fixture(cloudFiles = listOf(bookFile()))

        assertNull(fixture.queue.cloudAudioFor(id, settings))
        fixture.stop()
    }

    @Test
    fun `a chapter already on this device needs no download`() = runTest {
        val packed = packOnAnotherDevice(settings)
        val fixture = fixture(cloudFiles = listOf(bookFile(), chapterFile(packed)))
        prepareLocally(settings)

        assertEquals(PreparedChapterDownloadState.Installed, fixture.queue.stateOf(id, settings))
        assertEquals(PreparedChapterDownloadState.Installed, fixture.queue.download(id, settings))
        assertTrue(fixture.transfers.downloads.isEmpty())
        fixture.stop()
    }

    @Test
    fun `no cloud account means nothing is in the cloud as far as the row is concerned`() = runTest {
        val packed = packOnAnotherDevice(settings)
        val fixture = fixture(cloudFiles = listOf(bookFile(), chapterFile(packed)), snapshot = null)

        assertEquals(PreparedChapterDownloadState.NotInCloud, fixture.queue.stateOf(id, settings))
        fixture.stop()
    }

    @Test
    fun `a catalogue book's chapter is never looked for in the cloud`() = runTest {
        val packed = packOnAnotherDevice(settings)
        val fixture = fixture(cloudFiles = listOf(bookFile(), chapterFile(packed)))
        val catalogue = PreparedChapterId("remote-book", "storyteller-1", "chapter-one.xhtml")

        assertNull(fixture.queue.cloudAudioFor(catalogue, settings))
        assertEquals(
            PreparedChapterDownloadState.NotInCloud,
            fixture.queue.stateOf(catalogue, settings),
        )
        fixture.stop()
    }

    // -----------------------------------------------------------------------
    // Downloading and installing
    // -----------------------------------------------------------------------

    @Test
    fun `a downloaded chapter is verified, installed and plays instantly`() = runTest {
        val packed = packOnAnotherDevice(settings)
        val fixture = fixture(cloudFiles = listOf(bookFile(), chapterFile(packed)))

        assertEquals(PreparedChapterDownloadState.Downloading, fixture.queue.download(id, settings))
        val download = assertNotNull(fixture.transfers.downloads.singleOrNull())
        assertEquals(PARROT_CLOUD_SERVER_ID, download.serverId)
        assertEquals(BOOK_ID, download.libraryBookId)
        assertEquals(PREPARED_AUDIO_MEDIA_TYPE, download.mediaType)
        // The engine puts the bytes where it was told; here, the test does.
        packed.copyTo(File(download.destinationPath), overwrite = true)
        fixture.transfers.settle("completed")
        testScheduler.advanceUntilIdle()

        assertEquals(PreparedChapterState.Ready(1_200), store.state(id, settings))
        assertEquals(PreparedChapterDownloadState.Installed, fixture.queue.stateOf(id, settings))
        assertTrue(inbox.listFiles()!!.isEmpty(), "the archive existed only to be unpacked")
        fixture.stop()
    }

    @Test
    fun `a chapter made for other settings installs and the store says what it is for`() = runTest {
        val packed = packOnAnotherDevice(faster)
        val fixture = fixture(cloudFiles = listOf(bookFile(), chapterFile(packed, faster)))

        fixture.queue.download(id, settings)
        packed.copyTo(File(assertNotNull(fixture.transfers.downloads.singleOrNull()).destinationPath), true)
        fixture.transfers.settle("completed")
        testScheduler.advanceUntilIdle()

        // Exactly what the row already says for local audio prepared for
        // other settings.
        assertEquals(
            PreparedChapterState.OtherSettings(faster, true, 1, 1),
            store.state(id, settings),
        )
        assertEquals(PreparedChapterState.Ready(1_200), store.state(id, faster))
        fixture.stop()
    }

    @Test
    fun `a rejected archive is never installed and leaves nothing behind`() = runTest {
        val packed = packOnAnotherDevice(settings)
        val fixture = fixture(cloudFiles = listOf(bookFile(), chapterFile(packed)))

        fixture.queue.download(id, settings)
        val destination = File(assertNotNull(fixture.transfers.downloads.singleOrNull()).destinationPath)
        // Truncated: the bytes passed the engine's hash check in this test's
        // world, and the archive's own checks still have to catch it.
        destination.writeBytes(packed.readBytes().copyOfRange(0, 40))
        fixture.transfers.settle("completed")
        testScheduler.advanceUntilIdle()

        assertEquals(PreparedChapterState.NotPrepared, store.state(id, settings))
        assertEquals(
            PreparedChapterDownloadState.FailedArchiveRejected,
            fixture.queue.stateOf(id, settings),
        )
        assertTrue(inbox.listFiles()!!.isEmpty())
        assertTrue(
            storeRoot.walkTopDown().none { file -> file.name.startsWith("unpack-") },
            "no staging folder survives a rejection",
        )
        fixture.stop()
    }

    @Test
    fun `a rejected archive is not fetched again by itself`() = runTest {
        val packed = packOnAnotherDevice(settings)
        val fixture = fixture(cloudFiles = listOf(bookFile(), chapterFile(packed)))
        fixture.queue.download(id, settings)
        File(fixture.transfers.downloads.single().destinationPath).writeBytes(ByteArray(8))
        fixture.transfers.settle("completed")
        testScheduler.advanceUntilIdle()

        val again = fixture.queue.download(id, settings)

        assertEquals(PreparedChapterDownloadState.FailedArchiveRejected, again)
        assertEquals(1, fixture.transfers.downloads.size, "once, not in a loop")
        fixture.stop()
    }

    @Test
    fun `an archive that already installed a chapter does not disturb it when a later one is refused`() = runTest {
        val good = packOnAnotherDevice(settings)
        val fixture = fixture(cloudFiles = listOf(bookFile(), chapterFile(good)))
        fixture.queue.download(id, settings)
        good.copyTo(File(fixture.transfers.downloads.single().destinationPath), true)
        fixture.transfers.settle("completed")
        testScheduler.advanceUntilIdle()
        val installed = File(store.chapterDirectory(id), "${KEY_ONE}.m4a").readBytes()

        // A second, broken archive aimed at the same chapter.
        val broken = File(root, "broken.zip").apply { writeBytes(ByteArray(12)) }
        fixture.queue.install(id, "tts-prepared/x/y.zip", broken)

        assertEquals(PreparedChapterState.Ready(1_200), store.state(id, settings))
        assertContentEqualsBytes(installed, File(store.chapterDirectory(id), "${KEY_ONE}.m4a").readBytes())
        fixture.stop()
    }

    @Test
    fun `no network ends in one state and the archive is not left behind`() = runTest {
        val packed = packOnAnotherDevice(settings)
        val fixture = fixture(cloudFiles = listOf(bookFile(), chapterFile(packed)))

        fixture.queue.download(id, settings)
        fixture.transfers.settle("failed", lastError = "connection reset")
        testScheduler.advanceUntilIdle()

        assertEquals(
            PreparedChapterDownloadState.FailedNoNetwork,
            fixture.queue.stateOf(id, settings),
        )
        assertEquals(PreparedChapterState.NotPrepared, store.state(id, settings))
        assertTrue(inbox.listFiles()!!.isEmpty())
        fixture.stop()
    }

    @Test
    fun `a file gone from the cloud ends in its own state and queues nothing`() = runTest {
        val packed = packOnAnotherDevice(settings)
        val fixture = fixture(cloudFiles = listOf(bookFile(), chapterFile(packed)), refuseEnqueue = true)

        val state = fixture.queue.download(id, settings)

        assertEquals(PreparedChapterDownloadState.FailedGoneFromCloud, state)
        assertTrue(inbox.listFiles()!!.isEmpty())
        fixture.stop()
    }

    @Test
    fun `a device with no room says so before fetching anything`() = runTest {
        val packed = packOnAnotherDevice(settings)
        val fixture = fixture(cloudFiles = listOf(bookFile(), chapterFile(packed)), freeBytes = 100)

        assertEquals(PreparedChapterDownloadState.FailedDeviceFull, fixture.queue.stateOf(id, settings))
        assertEquals(PreparedChapterDownloadState.FailedDeviceFull, fixture.queue.download(id, settings))
        assertTrue(fixture.transfers.downloads.isEmpty())
        fixture.stop()
    }

    @Test
    fun `an archive left over from a previous run is thrown away rather than trusted`() = runTest {
        val fixture = fixture()
        val stale = File(inbox, "deadbeef.zip").apply { writeBytes(ByteArray(10)) }

        fixture.queue.discardUninstalledDownloads()

        assertFalse(stale.exists())
        fixture.stop()
    }

    // -----------------------------------------------------------------------
    // Fixture
    // -----------------------------------------------------------------------

    /**
     * Builds a real archive the way another device would have, in a store of
     * its own, so what is downloaded here is a genuine packed chapter.
     */
    private fun packOnAnotherDevice(
        settings: PreparedVoiceSettings,
        href: String = id.chapterHref,
    ): File {
        val otherDevice = TtsPreparedStore(File(sourceRoot, "device-${href.hashCode()}-${settings.rate}").apply { mkdirs() })
        val chapterId = id.copy(chapterHref = href)
        otherDevice.begin(chapterId, settings, listOf(KEY_ONE))
        otherDevice.add(chapterId, KEY_ONE, audio, 1_200)
        otherDevice.markComplete(chapterId)
        val destination = File(sourceRoot, "${href.hashCode()}-${settings.rate}.zip")
        val packed = archive.pack(otherDevice.chapterDirectory(chapterId), destination)
        assertTrue(packed is PreparedArchiveResult.Packed, "got $packed")
        return destination
    }

    private fun prepareLocally(settings: PreparedVoiceSettings) {
        store.begin(id, settings, listOf(KEY_ONE))
        store.add(id, KEY_ONE, audio, 1_200)
        store.markComplete(id)
    }

    private fun TestScope.fixture(
        cloudFiles: List<CloudBookFileRecord> = emptyList(),
        snapshot: PreparedBackupAccountSnapshot? = snapshot(),
        freeBytes: Long = 100_000_000,
        refuseEnqueue: Boolean = false,
    ): Fixture {
        val manager = RecordingTransfers(cloudFiles, refuseEnqueue)
        val scope = CoroutineScope(coroutineContext + Job())
        return Fixture(
            scope = scope,
            queue = PreparedChapterDownloadQueue(
                store = store,
                archive = archive,
                inbox = inbox,
                transfers = manager,
                account = FakeAccount(snapshot),
                freeBytes = { freeBytes },
                scope = scope,
            ),
            transfers = manager,
        )
    }

    private class Fixture(
        private val scope: CoroutineScope,
        val queue: PreparedChapterDownloadQueue,
        val transfers: RecordingTransfers,
    ) {
        fun stop() = scope.cancel()
    }

    private class FakeAccount(private val snapshot: PreparedBackupAccountSnapshot?) : PreparedBackupAccount {
        override suspend fun snapshot() = snapshot
    }

    private data class RecordedDownload(
        val serverId: String,
        val libraryBookId: String,
        val mediaType: String,
        val relativePath: String,
        val destinationPath: String,
    )

    private class RecordingTransfers(
        private val files: List<CloudBookFileRecord>,
        private val refuseEnqueue: Boolean,
    ) : BookFileTransferManager {
        val downloads = mutableListOf<RecordedDownload>()
        private val rows = MutableStateFlow(emptyList<BookFileTransfer>())

        fun settle(state: String, lastError: String? = null) {
            rows.value = rows.value.map { row -> row.copy(state = state, lastError = lastError) }
        }

        override suspend fun enqueueAuxiliaryDownload(
            serverId: String,
            libraryBookId: String,
            mediaType: String,
            relativePath: String,
            destinationPath: String,
        ): String {
            if (refuseEnqueue) error("No available cloud file was found for this book")
            downloads += RecordedDownload(serverId, libraryBookId, mediaType, relativePath, destinationPath)
            val transferId = "download-${downloads.size}"
            rows.value = rows.value.filterNot { it.relativePath == relativePath } + BookFileTransfer(
                transferId = transferId,
                serverId = serverId,
                libraryBookId = libraryBookId,
                direction = "download",
                mediaType = mediaType,
                state = "pending",
                bytesTransferred = 0,
                totalBytes = 0,
                attemptCount = 0,
                lastError = null,
                relativePath = relativePath,
            )
            return transferId
        }

        override suspend fun cloudFilesFor(libraryBookId: String) =
            if (libraryBookId == BOOK_ID) files else emptyList()

        override suspend fun transfersFor(serverId: String, libraryBookId: String) = rows.value

        override fun observeForBook(serverId: String, libraryBookId: String): Flow<List<BookFileTransfer>> =
            rows.map { it }

        override fun supportsUpload(serverId: String) = true
        override fun supportsDownload(serverId: String) = true
        override fun supportsDeletion(serverId: String) = true

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

    private fun assertContentEqualsBytes(expected: ByteArray, actual: ByteArray) {
        assertTrue(expected.contentEquals(actual), "the installed audio changed")
    }

    private fun chapterFile(
        packed: File,
        forSettings: PreparedVoiceSettings = settings,
        href: String = id.chapterHref,
    ) = CloudBookFileRecord(
        cloudBookFileId = "chapter-${packed.name}",
        mediaType = PREPARED_AUDIO_MEDIA_TYPE,
        relativePath = archive.relativePath(href, forSettings),
        fileName = "chapter.zip",
        status = "available",
        sizeBytes = packed.length(),
        contentHash = "c".repeat(64),
        contentHashAlgorithm = "sha-256-v1",
        remoteRevision = 1,
    )

    private companion object {
        const val BOOK_ID = "11111111-1111-4111-8111-111111111111"
        val KEY_ONE = "a".repeat(64)

        fun bookFile() = CloudBookFileRecord(
            cloudBookFileId = "book-file",
            mediaType = "ebook",
            relativePath = "",
            fileName = "book.epub",
            status = "available",
            sizeBytes = 1_000,
            contentHash = "b".repeat(64),
            contentHashAlgorithm = "sha-256-v1",
            remoteRevision = 1,
        )

        fun snapshot() = PreparedBackupAccountSnapshot(
            localProfileId = "profile-1",
            autoBackupEnabled = true,
            attestation = UploadRightsAttestation(
                attestedAt = "2026-10-10T00:00:00Z",
                tosVersion = "test",
                attestationVersion = "test",
            ),
        )
    }
}
