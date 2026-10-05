package com.retro99.reader.ui.reader

import com.retro99.reader.domain.model.PositionDomainModel
import com.retro99.reader.domain.model.ReadingProgressResult
import com.retro99.reader.ui.model.toUiData
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame

class PositionConflictCandidateTest {
    private val local = PositionDomainModel(
        bookUuid = "book", serverId = "server", timestamp = 100L, createdAt = null, updatedAt = null,
        locatorHref = "chapter.xhtml", locatorType = null, locatorTitle = null, locatorTarget = null,
        audioTimestampMs = null, chapterIndex = null, progression = 0.2, totalChapters = null,
        totalDurationMs = null, totalProgression = 0.2, position = null,
        localGeneration = 7L, remoteRevision = 3L,
    )

    @Test fun `UI retains the exact immutable candidates that will be resolved`() {
        val conflict = ReadingProgressResult.Conflict(local, local.copy(totalProgression = 0.8, remoteRevision = 9L))
        val ui = conflict.toUiData().conflict!!
        assertSame(conflict, ui.candidates)
        assertEquals(7L, ui.candidates.localPosition.localGeneration)
        assertEquals(9L, ui.candidates.remotePosition.remoteRevision)
    }

    @Test fun `remote device attribution survives dialog mapping`() {
        val conflict = ReadingProgressResult.Conflict(local, local.copy(
            totalProgression = 0.8, sourceDeviceId = "tablet", deviceName = "Other tablet",
            observedAt = "2026-10-01T08:00:00Z",
        ))
        val remote = conflict.toUiData().conflict!!.candidates.remotePosition
        assertEquals("tablet", remote.sourceDeviceId)
        assertEquals("Other tablet", remote.deviceName)
        assertEquals("2026-10-01T08:00:00Z", remote.observedAt)
    }

    @Test fun `later model changes cannot change the candidates in an already displayed dialog`() {
        val remote = local.copy(totalProgression = 0.8)
        val ui = ReadingProgressResult.Conflict(local, remote).toUiData().conflict!!
        val newerRemote = remote.copy(totalProgression = 0.9)
        assertEquals(0.8, ui.candidates.remotePosition.totalProgression)
        assertEquals(0.9, newerRemote.totalProgression)
    }
}
