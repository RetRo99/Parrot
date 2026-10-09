package com.retro99.server.storyteller

import com.retro99.server.api.PositionOrigin
import com.retro99.server.api.ServerPosition
import com.retro99.server.api.TextAnchor
import com.retro99.server.storyteller.model.StorytellerPositionApiModel
import com.retro99.server.storyteller.model.StorytellerLocatorApiModel
import com.retro99.server.storyteller.model.StorytellerLocationsApiModel
import com.retro99.server.storyteller.model.toStorytellerApiModel
import com.retro99.sync.domain.ProgressKind
import com.retro99.sync.domain.ProgressLocator
import com.retro99.sync.domain.ProgressMutation
import com.retro99.sync.domain.ProgressSnapshot
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class StorytellerProgressMappingTest {
    @Test
    fun `the payload ignores origin observation time and text anchor`() {
        // Given
        val plain = ServerPosition(
            bookUuid = "book-1",
            serverId = "storyteller",
            timestamp = 100L,
            createdAt = null,
            updatedAt = null,
            locatorHref = "chapter.xhtml",
            locatorType = null,
            locatorTitle = null,
            locatorTarget = null,
            audioTimestampMs = null,
            chapterIndex = 1,
            progression = 0.5,
            totalChapters = 4,
            totalDurationMs = null,
            totalProgression = 0.3,
            position = null,
        )
        val annotated = plain.copy(
            origin = PositionOrigin.LinkedCopy,
            observedAt = "2026-10-01T10:00:00Z",
            textAnchor = TextAnchor(before = "before", after = "after"),
        )

        // When
        val payload = annotated.toStorytellerApiModel()

        // Then
        assertEquals(plain.toStorytellerApiModel(), payload)
    }

    @Test
    fun mutationMappingPreservesLocatorAndAudioFields() {
        val snapshot = snapshot()
        val mutation = ProgressMutation(
            mutationId = "mutation-1",
            entityId = "local-book",
            remoteBookId = "remote-book",
            libraryBookId = "library-book",
            kind = ProgressKind.EBOOK,
            snapshot = snapshot,
            baseVersion = null,
            observedAt = "2026-09-22T12:00:00Z",
        )

        val serverPosition = mutation.toStorytellerServerPosition("storyteller")
        val remote = serverPosition.toRemoteProgressSnapshot("remote-book")

        assertEquals("local-book", serverPosition.bookUuid)
        assertEquals("library-book", serverPosition.libraryBookId)
        assertEquals(snapshot, remote.snapshot)
        assertEquals("remote-book", remote.remoteBookId)
        assertEquals("library-book", remote.libraryBookId)
    }

    @Test
    fun apiModelMappingPreservesLocatorFieldsAndOmitsServerTimestampsOnWrite() {
        val apiModel = StorytellerPositionApiModel(
            locator = StorytellerLocatorApiModel(
                href = "chapter.xhtml",
                type = "application/xhtml+xml",
                title = "Chapter 1",
                target = 42,
                locations = StorytellerLocationsApiModel(
                    audioTimestampMs = 12_345L,
                    chapterIndex = 3,
                    progression = 0.42,
                    totalChapters = 10,
                    totalDurationMs = 99_000L,
                    totalProgression = 0.84,
                    position = 42,
                ),
            ),
            timestamp = 100L,
            createdAt = "2026-09-22T11:00:00Z",
            updatedAt = "2026-09-22T12:00:00Z",
        )

        val remote = apiModel.toRemoteProgressSnapshot(
            remoteBookId = "remote-book",
            serverId = "storyteller",
        )
        val outbound = remote.snapshot
            .toStorytellerServerPosition("local-book", "storyteller", "library-book")
            .toStorytellerApiModel()

        assertEquals("chapter.xhtml", remote.snapshot.locator?.href)
        assertEquals(12_345L, remote.snapshot.audioTimestampMs)
        assertEquals(42, remote.snapshot.locator?.target)
        assertNull(outbound.createdAt)
        assertNull(outbound.updatedAt)
        assertEquals(42, outbound.locator?.locations?.position)
    }

    private fun snapshot() = ProgressSnapshot(
        timestamp = 100L,
        createdAt = "2026-09-22T11:00:00Z",
        updatedAt = "2026-09-22T12:00:00Z",
        locator = ProgressLocator(
            href = "chapter.xhtml",
            type = "application/xhtml+xml",
            title = "Chapter 1",
            target = 42,
            cssSelector = "p:nth-child(2)",
        ),
        audioTimestampMs = 12_345L,
        chapterIndex = 3,
        progression = 0.42,
        totalChapters = 10,
        totalDurationMs = 99_000L,
        totalProgression = 0.84,
        position = 42,
    )
}
