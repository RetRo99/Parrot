package com.retro99.reader.domain.linked

import com.retro99.books.domain.model.links.CopySource
import com.retro99.database.api.books.PositionEntity
import com.retro99.reader.domain.fakes.FakeCopyFiles
import com.retro99.reader.domain.fakes.FakeDismissals
import com.retro99.reader.domain.fakes.FakeLinkedCopiesSource
import com.retro99.reader.domain.fakes.FakeLinkedCopyWrites
import com.retro99.reader.domain.fakes.FakePositionDatabase
import com.retro99.reader.domain.fakes.FakeReaderRepository
import com.retro99.reader.domain.fakes.FakeRepositoryProvider
import com.retro99.reader.domain.fakes.StoredPosition
import com.retro99.reader.domain.fakes.serverPosition
import com.retro99.reader.domain.translate.TranslationConfidence
import com.retro99.reader.domain.translate.book
import com.retro99.reader.domain.translate.linkedCopy
import com.retro99.reader.domain.usecase.FindLinkedResumeUseCase
import com.retro99.server.api.PositionOrigin
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class FindLinkedResumeUseCaseTest {

    private val library = linkedCopy(CopySource.Library, "lib", serverId = "local")
    private val storyteller = linkedCopy(CopySource.Storyteller, "st", serverId = "st-1")
    private val positions = FakePositionDatabase()
    private val dismissals = FakeDismissals()
    private val files = FakeCopyFiles()
    private val storytellerServer = FakeReaderRepository(serverId = "st-1")
    private val writes = FakeLinkedCopyWrites()

    private fun useCase() = FindLinkedResumeUseCase(
        linkedCopiesSource = FakeLinkedCopiesSource(listOf(library, storyteller)),
        translatePositionUseCase = files.translateUseCase(positions),
        dismissals = dismissals,
        remoteCopyPositions = RemoteCopyPositions(
            repositoryProvider = FakeRepositoryProvider(readers = listOf(storytellerServer)),
            linkedCopyWritesDatabase = writes,
        ),
        positionDatabase = positions,
    )

    private suspend fun store(
        uuid: String,
        totalProgression: Double,
        observedAt: String,
        origin: String = PositionEntity.ORIGIN_USER,
    ) {
        positions.upsertPosition(
            StoredPosition(
                bookUuid = uuid,
                totalProgression = totalProgression,
                observedAt = observedAt,
                origin = origin,
            ),
        )
    }

    @Test
    fun `newer reading in another copy at a different place gives an offer`() = runTest {
        // Given
        store("lib", 0.1, "2026-10-01T10:00:00Z")
        store("st", 0.5, "2026-10-01T11:00:00Z")

        // When
        val offer = assertNotNull(useCase()("local", "lib"))

        // Then
        assertEquals(storyteller.key, offer.source.key)
        assertEquals(library.key, offer.translated.target)
        assertEquals(0.5, offer.translated.position.totalProgression)
        assertEquals(TranslationConfidence.Approximate, offer.translated.confidence)
    }

    @Test
    fun `the offer is high when the anchor is found in the opened copy`() = runTest {
        // Given
        files.chapters["lib"] = book("a", listOf(4, 4, 4))
        files.chapters["st"] = book("b", listOf(3, 3, 3, 3))
        store("lib", 0.1, "2026-10-01T10:00:00Z")
        positions.upsertPosition(
            StoredPosition(
                bookUuid = "st",
                totalProgression = 0.6,
                progression = 0.0,
                locatorHref = "OEBPS/b3.xhtml",
                cssSelector = "#b-s6",
                observedAt = "2026-10-01T11:00:00Z",
            ),
        )

        // When
        val offer = assertNotNull(useCase()("local", "lib"))

        // Then
        assertEquals(TranslationConfidence.High, offer.translated.confidence)
        assertEquals("#a-s6", offer.translated.position.cssSelector)
    }

    @Test
    fun `newer by less than a minute gives no offer`() = runTest {
        // Given
        store("lib", 0.1, "2026-10-01T10:00:00Z")
        store("st", 0.5, "2026-10-01T10:00:45Z")

        // When
        val offer = useCase()("local", "lib")

        // Then
        assertNull(offer)
    }

    @Test
    fun `the same place gives no offer`() = runTest {
        // Given
        store("lib", 0.5, "2026-10-01T10:00:00Z")
        store("st", 0.505, "2026-10-01T11:00:00Z")

        // When
        val offer = useCase()("local", "lib")

        // Then
        assertNull(offer)
    }

    @Test
    fun `written or restored positions are not real reading`() = runTest {
        listOf(PositionEntity.ORIGIN_LINKED_COPY, PositionEntity.ORIGIN_RESTORE).forEach { origin ->
            // Given
            store("lib", 0.1, "2026-10-01T10:00:00Z")
            store("st", 0.5, "2026-10-01T11:00:00Z", origin = origin)

            // When
            val offer = useCase()("local", "lib")

            // Then
            assertNull(offer, "for $origin")
        }
    }

    @Test
    fun `reading on another device pulled from the server counts`() = runTest {
        // Given
        store("lib", 0.1, "2026-10-01T10:00:00Z")
        store("st", 0.2, "2026-10-01T09:00:00Z")
        storytellerServer.remote["st"] = serverPosition(
            bookUuid = "st",
            serverId = "st-1",
            totalProgression = 0.7,
            observedAt = "2026-10-01T12:00:00Z",
            origin = PositionOrigin.Remote,
        )

        // When
        val offer = assertNotNull(useCase()("local", "lib"))

        // Then
        assertEquals(0.7, offer.translated.position.totalProgression)
    }

    @Test
    fun `a stored echo of our own write is ignored`() = runTest {
        // Given
        store("lib", 0.1, "2026-10-01T10:00:00Z")
        store("st", 0.5, "2026-10-01T11:00:00Z", origin = PositionEntity.ORIGIN_LINKED_COPY)

        // When
        val offer = useCase()("local", "lib")

        // Then
        assertNull(offer)
    }

    @Test
    fun `a fetched echo of our own write is not real reading`() = runTest {
        // Given
        store("lib", 0.1, "2026-10-01T10:00:00Z")
        writes.write(targetKey = "storyteller:st", bookUuid = "st", marker = "1759320000000")
        storytellerServer.remote["st"] = serverPosition(
            bookUuid = "st",
            serverId = "st-1",
            totalProgression = 0.7,
            observedAt = "2026-10-01T12:00:00Z",
            timestamp = 1_759_320_000_000,
        )

        // When
        val offer = useCase()("local", "lib")

        // Then
        assertNull(offer)
    }

    @Test
    fun `a remote fetch timeout still gives the local-based result`() = runTest {
        // Given
        store("lib", 0.1, "2026-10-01T10:00:00Z")
        store("st", 0.5, "2026-10-01T11:00:00Z")
        storytellerServer.remoteDelayMs = 10_000
        storytellerServer.remote["st"] =
            serverPosition("st", "st-1", 0.9, observedAt = "2026-10-01T12:00:00Z")

        // When
        val offer = assertNotNull(useCase()("local", "lib"))

        // Then
        assertEquals(0.5, offer.translated.position.totalProgression)
        assertEquals(1, storytellerServer.remoteFetches)
    }

    @Test
    fun `after stay here the same source reading is not offered again`() = runTest {
        // Given
        store("lib", 0.1, "2026-10-01T10:00:00Z")
        store("st", 0.5, "2026-10-01T11:00:00Z")
        val offer = assertNotNull(useCase()("local", "lib"))

        // When
        dismissals.dismiss(offer.dismissalEntry)

        // Then
        assertNull(useCase()("local", "lib"))
        assertEquals("library:lib|storyteller:st|2026-10-01T11:00:00Z", offer.dismissalEntry)
    }

    @Test
    fun `the opened copy's own newest reading means no offer`() = runTest {
        // Given
        store("lib", 0.1, "2026-10-01T12:00:00Z")
        store("st", 0.5, "2026-10-01T11:00:00Z")

        // When
        val offer = useCase()("local", "lib")

        // Then
        assertNull(offer)
    }
}
