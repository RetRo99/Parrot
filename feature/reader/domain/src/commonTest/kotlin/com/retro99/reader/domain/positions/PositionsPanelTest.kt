package com.retro99.reader.domain.positions

import com.retro99.books.domain.model.links.CopySource
import com.retro99.books.domain.model.links.LinkedCopy
import com.retro99.database.api.books.PositionEntity
import com.retro99.reader.domain.fakes.FakeCopyFiles
import com.retro99.reader.domain.fakes.FakeLinkedCopiesSource
import com.retro99.reader.domain.fakes.FakeLinkedCopyWrites
import com.retro99.reader.domain.fakes.FakePositionDatabase
import com.retro99.reader.domain.fakes.FakeReaderRepository
import com.retro99.reader.domain.fakes.FakeRepositoryProvider
import com.retro99.reader.domain.fakes.StoredPosition
import com.retro99.reader.domain.fakes.serverPosition
import com.retro99.reader.domain.linked.RemoteCopyPositions
import com.retro99.reader.domain.linked.RemoteFetch
import com.retro99.reader.domain.translate.CopyContentCache
import com.retro99.reader.domain.translate.SENTENCES
import com.retro99.reader.domain.translate.book
import com.retro99.reader.domain.translate.chapter
import com.retro99.reader.domain.translate.linkedCopy
import com.retro99.reader.domain.usecase.ApplyPositionUseCase
import com.retro99.reader.domain.usecase.ObserveCopyPositionsUseCase
import com.retro99.reader.domain.usecase.PreviewApplyPositionUseCase
import com.retro99.reader.domain.write.CopyWriteResult
import com.retro99.reader.domain.write.WriteCopyPositionUseCase
import com.retro99.server.api.PositionOrigin
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PositionsPanelTest {

    private val library = linkedCopy(CopySource.Library, "lib", serverId = "local")
    private val storyteller = linkedCopy(CopySource.Storyteller, "st", serverId = "st-1")
    private val absEbook = linkedCopy(CopySource.Audiobookshelf, "abse", serverId = "abs-1")
    private val absAudio = linkedCopy(
        CopySource.Audiobookshelf,
        "absa",
        hasEbook = false,
        hasAudiobook = true,
        serverId = "abs-1",
    )
    private val positions = FakePositionDatabase()
    private val writes = FakeLinkedCopyWrites()
    private val files = FakeCopyFiles()
    private val storytellerServer = FakeReaderRepository(serverId = "st-1")
    private val absServer = FakeReaderRepository(serverId = "abs-1")
    private val localServer = FakeReaderRepository(serverId = "local")
    private val provider = FakeRepositoryProvider(
        readers = listOf(storytellerServer, absServer, localServer),
    )
    private val remote = RemoteCopyPositions(provider, writes)
    private val write = WriteCopyPositionUseCase(provider, writes)

    private fun rows(copies: List<LinkedCopy>) =
        ObserveCopyPositionsUseCase(
            linkedCopiesSource = FakeLinkedCopiesSource(copies),
            remoteCopyPositions = remote,
            fileLocator = files,
            contentCache = CopyContentCache(files, files),
            positionDatabase = positions,
            linkedCopyWritesDatabase = writes,
        )

    private suspend fun store(uuid: String, progression: Double, at: String, origin: String) {
        positions.upsertPosition(
            StoredPosition(
                bookUuid = uuid,
                totalProgression = progression,
                observedAt = at,
                origin = origin,
            ),
        )
    }

    /** An older listening position, which tells the audiobook's length. */
    private suspend fun storeAudiobook() {
        positions.upsertPosition(
            StoredPosition(
                bookUuid = "absa",
                totalProgression = 0.1,
                audioTimestampMs = 10_000,
                totalDurationMs = 100_000,
                observedAt = "2026-09-01T10:00:00Z",
                origin = PositionEntity.ORIGIN_REMOTE,
            ),
        )
    }

    @Test
    fun `latest goes to the newest real reading, never to an echo`() = runTest {
        // Given
        store("lib", 0.2, "2026-10-01T10:00:00Z", PositionEntity.ORIGIN_USER)
        store("st", 0.6, "2026-10-01T12:00:00Z", PositionEntity.ORIGIN_LINKED_COPY)

        // When
        val rows = rows(listOf(library, storyteller))("local", "lib").orEmpty()

        // Then
        assertEquals(listOf(true, false), rows.map { row -> row.isLatest })
        assertEquals(PositionSource.ThisDevice, rows[0].sourceLabel)
    }

    @Test
    fun `a failed fetch shows the last known position as stale`() = runTest {
        // Given
        store("lib", 0.2, "2026-10-01T10:00:00Z", PositionEntity.ORIGIN_USER)
        store("st", 0.5, "2026-10-01T09:00:00Z", PositionEntity.ORIGIN_REMOTE)
        storytellerServer.remoteFails = true

        // When
        val rows = rows(listOf(library, storyteller))("local", "lib").orEmpty()

        // Then
        val row = rows.first { candidate -> candidate.copy == storyteller }
        assertTrue(row.isStale)
        assertEquals(0.5, row.position?.totalProgression)
        assertFalse(rows.first().isStale)
    }

    @Test
    fun `a newer server position is shown and labelled as from the server`() = runTest {
        // Given
        store("lib", 0.2, "2026-10-01T10:00:00Z", PositionEntity.ORIGIN_USER)
        storytellerServer.remote["st"] = serverPosition(
            "st",
            "st-1",
            0.7,
            observedAt = "2026-10-01T12:00:00Z",
            timestamp = 1,
        )

        // When
        val rows = rows(listOf(library, storyteller))("local", "lib").orEmpty()

        // Then
        val row = rows.first { candidate -> candidate.copy == storyteller }
        assertEquals(0.7, row.position?.totalProgression)
        assertEquals(PositionSource.Server, row.sourceLabel)
        assertTrue(row.isLatest)
    }

    @Test
    fun `previews tick reliable targets and untick approximate or collapsing ones`() = runTest {
        // Given
        files.chapters["lib"] = book("a", listOf(4, 4, 4))
        files.chapters["st"] = book("b", listOf(3, 3, 3, 3))
        positions.upsertPosition(
            StoredPosition(
                bookUuid = "lib",
                totalProgression = 0.42,
                progression = 0.0,
                locatorHref = "OEBPS/a2.xhtml",
                cssSelector = "#a-s5",
                observedAt = "2026-10-01T10:00:00Z",
            ),
        )
        val other = linkedCopy(CopySource.Storyteller, "plain", serverId = "st-1")
        val rows = rows(listOf(library, storyteller, other, absEbook))("local", "lib").orEmpty()

        // When
        val previews = PreviewApplyPositionUseCase(files.translateUseCase(positions))(
            source = rows.first { row -> row.copy == library },
            rows = rows,
        ).associateBy { preview -> preview.target.uuid }

        // Then
        assertTrue(previews.getValue("st").defaultChecked)
        val approximate = previews.getValue("plain")
        assertFalse(approximate.defaultChecked)
        assertTrue(approximate.enabled)
        val percent = (approximate.translated?.position?.totalProgression ?: 0.0) * 100
        assertEquals(42, percent.toInt())
        // An Audiobookshelf ebook can be written since B4, as an approximate target here.
        val audiobookshelfEbook = previews.getValue("abse")
        assertTrue(audiobookshelfEbook.enabled)
        assertFalse(audiobookshelfEbook.defaultChecked)
    }

    @Test
    fun `different local and server positions remain separately selectable`() = runTest {
        store("st", 0.2, "2026-10-01T10:00:00Z", PositionEntity.ORIGIN_USER)
        storytellerServer.remote["st"] = serverPosition("st", "st-1", 0.8, observedAt = "2026-10-01T12:00:00Z")
        val rows = rows(listOf(library, storyteller))("local", "lib").orEmpty().filter { it.copy == storyteller }
        assertEquals(2, rows.size)
        assertEquals(setOf(0.2, 0.8), rows.map { it.position?.totalProgression }.toSet())
        assertEquals(2, rows.map { it.candidateId }.distinct().size)
        assertTrue(rows.all { it.isConflict })
        assertEquals(1, rows.count { it.isLocalCandidate })
        assertEquals(1, rows.count { it.isLatest })
    }

    @Test
    fun `a library cloud baseline is not hidden by a newer device position`() = runTest {
        store("lib", 0.8, "2026-10-02T10:00:00Z", PositionEntity.ORIGIN_USER)
        positions.upsertRemotePosition(StoredPosition("lib", totalProgression = 0.2, observedAt = "2026-10-01T10:00:00Z", origin = PositionEntity.ORIGIN_REMOTE))
        val rows = rows(listOf(library, storyteller))("local", "lib").orEmpty().filter { it.copy == library }
        assertEquals(2, rows.size)
        assertEquals(0.8, rows.first().position?.totalProgression)
        assertEquals(0.2, rows.last().position?.totalProgression)
        assertEquals(PositionSource.Server, rows.last().sourceLabel)
    }

    @Test
    fun `identical places with different times are one candidate rather than a conflict`() = runTest {
        store("st", 0.2, "2026-10-01T10:00:00Z", PositionEntity.ORIGIN_USER)
        storytellerServer.remote["st"] = serverPosition("st", "st-1", 0.2, observedAt = "2026-10-01T12:00:00Z")
        val rows = rows(listOf(library, storyteller))("local", "lib").orEmpty().filter { it.copy == storyteller }
        assertEquals(1, rows.size)
        assertFalse(rows.single().isConflict)
    }

    @Test
    fun `duplicate conflict candidates do not create duplicate write targets`() = runTest {
        store("lib", 0.2, "2026-10-01T10:00:00Z", PositionEntity.ORIGIN_USER)
        store("st", 0.3, "2026-10-01T10:00:00Z", PositionEntity.ORIGIN_USER)
        storytellerServer.remote["st"] = serverPosition("st", "st-1", 0.8, observedAt = "2026-10-01T12:00:00Z")
        val rows = rows(listOf(library, storyteller))("local", "lib").orEmpty()
        val previews = PreviewApplyPositionUseCase(files.translateUseCase(positions))(rows.first { it.copy == library }, rows)
        assertEquals(1, previews.size)
        assertEquals(storyteller.key, previews.single().target.key)
    }

    @Test
    fun `restored positions are explicitly attributed and never latest reading`() = runTest {
        store("lib", 0.2, "2026-10-01T10:00:00Z", PositionEntity.ORIGIN_RESTORE)
        val row = rows(listOf(library, storyteller))("local", "lib").orEmpty().first()
        assertEquals(PositionSource.Restored, row.sourceLabel)
        assertFalse(row.isLatest)
    }

    @Test
    fun `collapse to start or end is unticked with a warning`() = runTest {
        // Given: in the target, the sentence comes after a long preamble, in the last 0.5%.
        files.chapters["lib"] = book("a", listOf(4, 4, 4))
        val preamble = "Front matter and appendices fill this edition. ".repeat(500)
        files.chapters["st"] = listOf(
            chapter(
                href = "OEBPS/b1.xhtml",
                ids = listOf("b-front", "b-s8"),
                texts = listOf(preamble, SENTENCES[8]),
            ),
        )
        positions.upsertPosition(
            StoredPosition(
                bookUuid = "lib",
                totalProgression = 0.6,
                progression = 0.0,
                locatorHref = "OEBPS/a3.xhtml",
                cssSelector = "#a-s8",
                observedAt = "2026-10-01T10:00:00Z",
            ),
        )
        val rows = rows(listOf(library, storyteller))("local", "lib").orEmpty()

        // When
        val preview = PreviewApplyPositionUseCase(files.translateUseCase(positions))(
            source = rows.first { row -> row.copy == library },
            rows = rows,
        ).single()

        // Then
        assertFalse(preview.defaultChecked)
        assertEquals(ApplyWarning.CollapseToEnd, preview.warning)
    }

    @Test
    fun `apply writes only ticked targets, never the source, and goes on after a failure`() =
        runTest {
            // Given
            store("lib", 0.4, "2026-10-01T10:00:00Z", PositionEntity.ORIGIN_USER)
            storeAudiobook()
            val rows = rows(listOf(library, storyteller, absAudio))("local", "lib").orEmpty()
            val source = rows.first { row -> row.copy == library }
            val previews = PreviewApplyPositionUseCase(files.translateUseCase(positions))(
                source,
                rows,
            )
            storytellerServer.saveFails = true

            // When
            val results = ApplyPositionUseCase(write)(source, previews)

            // Then
            val targets = results.map { result -> result.target }.toSet()
            assertEquals(setOf(storyteller, absAudio), targets)
            val failed = results.first { result -> result.target == storyteller }
            assertIs<CopyWriteResult.Failed>(failed.result)
            assertTrue(localServer.syncedSaves.isEmpty())
            assertEquals(PositionOrigin.Manual, absServer.syncedSaves.singleOrNull()?.origin)
        }

    @Test
    fun `after applying, the next pull of our own write is an echo`() = runTest {
        // Given
        store("lib", 0.4, "2026-10-01T10:00:00Z", PositionEntity.ORIGIN_USER)
        storeAudiobook()
        val rows = rows(listOf(library, storyteller, absAudio))("local", "lib").orEmpty()
        val source = rows.first { row -> row.copy == library }
        val previews = PreviewApplyPositionUseCase(files.translateUseCase(positions))(source, rows)
        ApplyPositionUseCase(write)(source, previews)

        // When: each server returns what was written (Audiobookshelf rounds a little).
        val sentToStoryteller = storytellerServer.syncedSaves.single()
        storytellerServer.remote["st"] = sentToStoryteller
        absServer.remote["absa"] = absServer.syncedSaves.single().let { sent ->
            sent.copy(totalProgression = sent.totalProgression!! + 0.004, timestamp = 9)
        }
        val fetched = remote.fetch(listOf(storyteller, absAudio))

        // Then
        fetched.values.forEach { fetch ->
            assertEquals(PositionOrigin.LinkedCopy, (fetch as RemoteFetch.Fetched).position?.origin)
        }
        assertNull(fetched[library])
    }
}
