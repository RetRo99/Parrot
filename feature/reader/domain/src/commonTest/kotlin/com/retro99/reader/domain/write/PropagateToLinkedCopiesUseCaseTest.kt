package com.retro99.reader.domain.write

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
import com.retro99.reader.domain.translate.CopyContentCache
import com.retro99.reader.domain.translate.TranslatedPosition
import com.retro99.reader.domain.translate.TranslationConfidence
import com.retro99.reader.domain.translate.TranslationStrategy
import com.retro99.reader.domain.translate.book
import com.retro99.reader.domain.translate.chapter
import com.retro99.reader.domain.translate.linkedCopy
import com.retro99.reader.domain.translate.position
import com.retro99.reader.domain.translate.progressKind
import com.retro99.reader.domain.translate.timingFor
import com.retro99.reader.domain.usecase.PositionTranslation
import com.retro99.reader.domain.usecase.PropagateToLinkedCopiesUseCase
import com.retro99.server.api.PositionOrigin
import com.retro99.sync.domain.ProgressKind
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PropagateToLinkedCopiesUseCaseTest {

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
    private val provider = FakeRepositoryProvider(readers = listOf(storytellerServer, absServer))
    private val setting = FixedSetting(enabled = true)

    /** Translations by target uuid; a missing entry means no translation. */
    private val translations = mutableMapOf<String, Pair<Double?, TranslationConfidence>>()
    private var audioMs: Long? = null

    private fun useCase(
        copies: List<LinkedCopy> = listOf(library, storyteller),
        translation: PositionTranslation = PositionTranslation { _, _, target, _ ->
            translations[target.uuid]?.let { (progression, confidence) ->
                translated(target, progression, confidence)
            }
        },
    ) = PropagateToLinkedCopiesUseCase(
        linkedCopiesSource = FakeLinkedCopiesSource(copies),
        translation = translation,
        writeCopyPositionUseCase = WriteCopyPositionUseCase(provider, writes),
        fileLocator = files,
        contentCache = CopyContentCache(files, files),
        setting = setting,
        positionDatabase = positions,
        linkedCopyWritesDatabase = writes,
    )

    private fun translated(
        target: LinkedCopy,
        progression: Double?,
        confidence: TranslationConfidence,
    ) = TranslatedPosition(
        target = target.key,
        position = position(target.uuid).copy(
            serverId = target.serverId,
            locatorHref = "c.xhtml".takeIf { _ -> target.progressKind == ProgressKind.EBOOK },
            totalProgression = progression,
            audioTimestampMs = audioMs,
        ),
        kind = target.progressKind,
        confidence = confidence,
        strategy = TranslationStrategy.TextAnchor,
    )

    private suspend fun read(
        progression: Double,
        origin: String = PositionEntity.ORIGIN_USER,
        at: String = SOURCE_TIME,
    ) {
        positions.upsertPosition(
            StoredPosition(
                bookUuid = "lib",
                totalProgression = progression,
                observedAt = at,
                origin = origin,
            ),
        )
    }

    @Test
    fun `a high translation is written as linked_copy and queued for the target's server`() =
        runTest {
            // Given
            read(0.4)
            translations["st"] = 0.42 to TranslationConfidence.High

            // When
            useCase()("local", "lib")

            // Then
            val saved = storytellerServer.syncedSaves.single()
            assertEquals(PositionOrigin.LinkedCopy, saved.origin)
            assertEquals(SOURCE_TIME, saved.observedAt)
            assertEquals(0.42, saved.totalProgression)
        }

    @Test
    fun `an approximate translation writes nothing`() = runTest {
        // Given
        read(0.4)
        translations["st"] = 0.42 to TranslationConfidence.Approximate

        // When
        useCase()("local", "lib")

        // Then
        assertTrue(storytellerServer.syncedSaves.isEmpty())
    }

    @Test
    fun `a target read more recently is not overwritten`() = runTest {
        // Given
        read(0.4)
        positions.upsertPosition(
            StoredPosition(
                bookUuid = "st",
                totalProgression = 0.7,
                observedAt = "2026-10-01T12:00:00Z",
                origin = PositionEntity.ORIGIN_REMOTE,
            ),
        )
        translations["st"] = 0.42 to TranslationConfidence.High

        // When
        useCase()("local", "lib")

        // Then
        assertTrue(storytellerServer.syncedSaves.isEmpty())
    }

    @Test
    fun `written, manual and pulled positions never propagate`() = runTest {
        listOf(
            PositionEntity.ORIGIN_LINKED_COPY,
            PositionEntity.ORIGIN_MANUAL,
            PositionEntity.ORIGIN_REMOTE,
        ).forEach { origin ->
            // Given
            read(0.4, origin = origin)
            translations["st"] = 0.42 to TranslationConfidence.High

            // When
            useCase()("local", "lib")

            // Then
            assertTrue(storytellerServer.syncedSaves.isEmpty(), "for $origin")
        }
    }

    @Test
    fun `a small ebook move counts by characters`() = runTest {
        // Given: the target's current position is 0.5% behind.
        storeTarget("st", 0.400, "2026-10-01T09:00:00Z")
        read(0.405)
        translations["st"] = 0.405 to TranslationConfidence.High

        // When: 0.5% of 100 000 characters is 500, under 2000.
        files.chapters["st"] = textOf(100_000)
        useCase()("local", "lib")
        val smallBook = storytellerServer.syncedSaves.size
        // And 0.5% of 500 000 characters is 2500.
        files.chapters["st"] = textOf(500_000)
        useCase()("local", "lib")

        // Then
        assertEquals(0, smallBook)
        assertEquals(1, storytellerServer.syncedSaves.size)
    }

    @Test
    fun `a small audio move is not written`() = runTest {
        // Given
        storeTarget("absa", 0.400, "2026-10-01T09:00:00Z")
        read(0.405)
        audioMs = 40_500
        translations["absa"] = 0.405 to TranslationConfidence.High

        // When
        useCase(copies = listOf(library, absAudio))("local", "lib")

        // Then
        assertTrue(absServer.syncedSaves.isEmpty())
    }

    @Test
    fun `the same source reading is written once`() = runTest {
        // Given
        read(0.4)
        translations["st"] = 0.42 to TranslationConfidence.High

        // When
        useCase()("local", "lib")
        positions.deletePosition("st")
        useCase()("local", "lib")

        // Then
        assertEquals(1, storytellerServer.syncedSaves.size)
    }

    @Test
    fun `collapse to the start is blocked unless the source is at the start too`() = runTest {
        // Given
        read(0.40)
        translations["st"] = 0.002 to TranslationConfidence.High

        // When
        useCase()("local", "lib")
        val blocked = storytellerServer.syncedSaves.size
        read(0.003, at = "2026-10-01T10:30:00Z")
        translations["st"] = 0.001 to TranslationConfidence.High
        useCase()("local", "lib")

        // Then
        assertEquals(0, blocked)
        assertEquals(0.001, storytellerServer.syncedSaves.single().totalProgression)
    }

    @Test
    fun `collapse to the end is blocked unless the source is close behind`() = runTest {
        // Given
        read(0.60)
        translations["st"] = 0.998 to TranslationConfidence.High

        // When
        useCase()("local", "lib")
        val blocked = storytellerServer.syncedSaves.size
        read(0.97, at = "2026-10-01T10:30:00Z")
        useCase()("local", "lib")

        // Then
        assertEquals(0, blocked)
        assertEquals(0.998, storytellerServer.syncedSaves.single().totalProgression)
    }

    @Test
    fun `reading updates an audiobookshelf ebook and its audiobook`() = runTest {
        // Given
        read(0.4)
        translations["abse"] = 0.42 to TranslationConfidence.High
        translations["absa"] = 0.42 to TranslationConfidence.High
        audioMs = 42_000

        // When
        useCase(copies = listOf(library, absEbook, absAudio))("local", "lib")

        // Then
        val saves = absServer.syncedSaves.associateBy { saved -> saved.bookUuid }
        assertEquals(setOf("abse", "absa"), saves.keys)
        assertEquals("c.xhtml", saves.getValue("abse").locatorHref)
        assertEquals(42_000L, saves.getValue("absa").audioTimestampMs)
    }

    @Test
    fun `a read-aloud's audio maps straight onto a linked audiobook`() = runTest {
        // Given: the real translator, with the read-aloud's text and timing on this device.
        val readaloud = linkedCopy(
            CopySource.Storyteller,
            "st",
            hasReadaloud = true,
            serverId = "st-1",
        )
        val chapters = book("r", listOf(6, 6))
        val timing = timingFor(chapters)
        files.chapters["st"] = chapters
        files.timings["st"] = timing
        storeTarget("absa", 0.1, "2026-09-01T09:00:00Z", durationMs = timing.totalDurationMs)
        val position = position("st").copy(
            serverId = "st-1",
            locatorHref = "OEBPS/r1.xhtml",
            progression = 0.5,
            totalProgression = 0.5,
            audioTimestampMs = 60_000,
            totalDurationMs = timing.totalDurationMs,
            observedAt = SOURCE_TIME,
        )

        // When
        PropagateToLinkedCopiesUseCase(
            linkedCopiesSource = FakeLinkedCopiesSource(listOf(readaloud, absAudio)),
            translation = files.translateUseCase(positions),
            writeCopyPositionUseCase = WriteCopyPositionUseCase(provider, writes),
            fileLocator = files,
            contentCache = CopyContentCache(files, files),
            setting = setting,
            positionDatabase = positions,
            linkedCopyWritesDatabase = writes,
        )("st-1", "st", position)

        // Then: the audiobook's file lengths aren't known, so only the book time is set.
        val saved = absServer.syncedSaves.single()
        assertEquals(60_000L, saved.bookTimeMs)
        assertNull(saved.audioTimestampMs)
    }

    @Test
    fun `nothing is written with the setting off`() = runTest {
        // Given
        setting.enabled = false
        read(0.4)
        translations["st"] = 0.42 to TranslationConfidence.High

        // When
        useCase()("local", "lib")

        // Then
        assertTrue(storytellerServer.syncedSaves.isEmpty())
    }

    private suspend fun storeTarget(
        uuid: String,
        progression: Double,
        at: String,
        durationMs: Long? = null,
    ) {
        positions.upsertPosition(
            StoredPosition(
                bookUuid = uuid,
                totalProgression = progression,
                observedAt = at,
                origin = PositionEntity.ORIGIN_REMOTE,
                totalDurationMs = durationMs,
            ),
        )
    }

    private fun textOf(length: Int) = listOf(
        chapter("OEBPS/c.xhtml", listOf("all"), listOf("x".repeat(length))),
    )

    private class FixedSetting(var enabled: Boolean) : LinkedCopyPropagationSetting {
        override fun observeEnabled(): Flow<Boolean> = flowOf(enabled)

        override suspend fun isEnabled(): Boolean = enabled

        override suspend fun setEnabled(enabled: Boolean) {
            this.enabled = enabled
        }
    }

    private companion object {
        const val SOURCE_TIME = "2026-10-01T10:00:00Z"
    }
}
