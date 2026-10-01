package com.retro99.reader.domain.write

import com.retro99.books.domain.model.links.CopySource
import com.retro99.reader.domain.fakes.FakeLinkedCopyWrites
import com.retro99.reader.domain.fakes.FakeReaderRepository
import com.retro99.reader.domain.fakes.FakeRepositoryProvider
import com.retro99.reader.domain.translate.linkedCopy
import com.retro99.reader.domain.translate.position
import com.retro99.server.api.PositionOrigin
import com.retro99.sync.domain.ObservedTime
import kotlinx.coroutines.test.runTest
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.time.Clock

class WriteCopyPositionUseCaseTest {

    private val storytellerServer = FakeReaderRepository(serverId = "st-1")
    private val audiobookshelfServer = FakeReaderRepository(serverId = "abs-1")
    private val writes = FakeLinkedCopyWrites()
    private val useCase = WriteCopyPositionUseCase(
        repositoryProvider = FakeRepositoryProvider(
            readers = listOf(storytellerServer, audiobookshelfServer),
        ),
        linkedCopyWritesDatabase = writes,
    )
    private val library = linkedCopy(CopySource.Library, "lib", serverId = "local")
    private val storyteller = linkedCopy(CopySource.Storyteller, "st", serverId = "st-1")

    @Test
    fun `a manual write is observed now, replaces the write log row and saves nothing else`() =
        runTest {
            // Given
            writes.write(targetKey = "storyteller:st", bookUuid = "st", totalProgression = 0.1)
            val target = position("st").copy(locatorHref = "c2.xhtml", totalProgression = 0.4)

            // When
            val result = useCase(
                CopyWrite(library, null, storyteller, target, PositionOrigin.Manual),
            )

            // Then
            assertIs<CopyWriteResult.Written>(result)
            val saved = storytellerServer.syncedSaves.single()
            assertEquals(PositionOrigin.Manual, saved.origin)
            val observed = assertNotNull(ObservedTime.toEpochMillis(saved.observedAt))
            assertTrue(abs(Clock.System.now().toEpochMilliseconds() - observed) < 60_000)
            val row = writes.writes.getValue("storyteller:st")
            assertEquals(0.4, row.totalProgression)
            assertEquals("library:lib", row.sourceKey)
            // Guard 13: the timestamp sent is the echo marker.
            assertEquals(saved.timestamp.toString(), row.marker)
            // Guard 12: only the target's position is saved; no reading session exists here.
            assertEquals(1, storytellerServer.syncedSaves.size + storytellerServer.localSaves.size)
        }

    @Test
    fun `an automatic write sends the source's reading time`() = runTest {
        // Given
        val target = position("st").copy(locatorHref = "c2.xhtml", totalProgression = 0.4)

        // When
        useCase(
            CopyWrite(
                library,
                "2026-10-01T10:00:00Z",
                storyteller,
                target,
                PositionOrigin.LinkedCopy,
            ),
        )

        // Then
        val saved = storytellerServer.syncedSaves.single()
        assertEquals(ObservedTime.toEpochMillis("2026-10-01T10:00:00Z"), saved.timestamp)
        assertEquals("2026-10-01T10:00:00Z", saved.observedAt)
        assertEquals(PositionOrigin.LinkedCopy, saved.origin)
    }

    @Test
    fun `an audiobookshelf ebook takes ebook positions`() = runTest {
        // Given: its transport writes the shape Audiobookshelf's readers stored (B4).
        val absEbook = linkedCopy(CopySource.Audiobookshelf, "abs", serverId = "abs-1")
        val target = position("abs").copy(locatorHref = "c2.xhtml", totalProgression = 0.4)

        // When
        val result = useCase(CopyWrite(library, null, absEbook, target, PositionOrigin.Manual))

        // Then
        assertIs<CopyWriteResult.Written>(result)
        assertEquals("c2.xhtml", audiobookshelfServer.syncedSaves.single().locatorHref)
    }

    @Test
    fun `an audiobookshelf copy is refused a position with nothing it can use`() = runTest {
        // Given
        val absEbook = linkedCopy(CopySource.Audiobookshelf, "abse", serverId = "abs-1")
        val absAudio = linkedCopy(
            CopySource.Audiobookshelf,
            "absa",
            hasEbook = false,
            hasAudiobook = true,
            serverId = "abs-1",
        )
        val empty = position("abs")

        // When
        val results = listOf(absEbook, absAudio).map { target ->
            useCase(CopyWrite(library, null, target, empty, PositionOrigin.Manual))
        }

        // Then
        results.forEach { result ->
            assertEquals(CopyWriteResult.Refused(CopyWriteResult.Reason.NotSupported), result)
        }
        assertTrue(audiobookshelfServer.syncedSaves.isEmpty())
        assertTrue(writes.writes.isEmpty())
    }

    @Test
    fun `an audiobookshelf audiobook takes audio positions`() = runTest {
        // Given
        val absAudio = linkedCopy(
            CopySource.Audiobookshelf,
            "abs",
            hasEbook = false,
            hasAudiobook = true,
            serverId = "abs-1",
        )
        val target = position("abs").copy(audioTimestampMs = 60_000, totalProgression = 0.4)

        // When
        val result = useCase(CopyWrite(library, null, absAudio, target, PositionOrigin.Manual))

        // Then
        assertIs<CopyWriteResult.Written>(result)
        assertEquals(60_000L, audiobookshelfServer.syncedSaves.single().audioTimestampMs)
    }

    @Test
    fun `an audiobookshelf audiobook takes a position known only by its book time`() = runTest {
        // Given
        val absAudio = linkedCopy(
            CopySource.Audiobookshelf,
            "abs",
            hasEbook = false,
            hasAudiobook = true,
            serverId = "abs-1",
        )
        val target = position("abs").copy(bookTimeMs = 3_600_000, totalProgression = 0.4)

        // When
        val result = useCase(CopyWrite(library, null, absAudio, target, PositionOrigin.Manual))

        // Then
        assertIs<CopyWriteResult.Written>(result)
        assertEquals(3_600_000L, audiobookshelfServer.syncedSaves.single().bookTimeMs)
    }
}
