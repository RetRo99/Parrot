package com.retro99.reader.domain.usecase

import com.github.michaelbull.result.getOrElse
import com.retro99.reader.domain.fakes.FakePositionDatabase
import com.retro99.reader.domain.fakes.FakeReaderRepository
import com.retro99.reader.domain.fakes.FakeRepositoryProvider
import com.retro99.reader.domain.fakes.StoredPosition
import com.retro99.reader.domain.fakes.serverPosition
import com.retro99.reader.domain.model.ReadingProgressResult
import com.retro99.server.api.PositionOrigin
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull

class GetReadingProgressWithConflictUseCaseTest {
    private val database = FakePositionDatabase()
    private val repository = FakeReaderRepository("server")
    private val useCase = GetReadingProgressWithConflictUseCase(FakeRepositoryProvider(listOf(repository)), database)

    private suspend fun progress() = useCase("server", "book").getOrElse { error("Unexpected failure: $it") }
    private fun local() = serverPosition("book", "server", totalProgression = 0.2, remoteRevision = 2L)
        .copy(localGeneration = 4L)
    private fun remote() = local().copy(totalProgression = 0.8, progression = 0.8, remoteRevision = 9L)

    @Test fun `no position restores the beginning without a conflict`() = runTest {
        assertNull(assertIs<ReadingProgressResult.Resolved>(progress()).position)
    }

    @Test fun `local only restores the local candidate`() = runTest {
        repository.local["book"] = local()
        repository.remoteFails = true
        assertEquals(0.2, assertIs<ReadingProgressResult.Resolved>(progress()).position?.totalProgression)
    }

    @Test fun `remote only restores remote attribution`() = runTest {
        repository.remote["book"] = remote().copy(origin = PositionOrigin.Remote, deviceName = "Tablet")
        val position = assertIs<ReadingProgressResult.Resolved>(progress()).position!!
        assertEquals(PositionOrigin.Remote, position.origin)
        assertEquals("Tablet", position.deviceName)
    }

    @Test fun `durable baseline is displayed offline without another fetch`() = runTest {
        repository.local["book"] = local()
        repository.remoteFails = true
        database.upsertRemotePosition(StoredPosition("book", totalProgression = 0.8, remoteRevision = 9L))
        val conflict = assertIs<ReadingProgressResult.Conflict>(progress())
        assertEquals(0.8, conflict.remotePosition.totalProgression)
        assertEquals(9L, conflict.remotePosition.remoteRevision)
        assertEquals(4L, conflict.localPosition.localGeneration)
        assertEquals(0, repository.remoteFetches)
    }

    @Test fun `baseline retains device time audio book time and opaque ebook location`() = runTest {
        repository.local["book"] = local()
        database.upsertRemotePosition(StoredPosition(
            "book", totalProgression = 0.8, remoteRevision = 9L,
            deviceName = "Tablet", sourceDeviceId = "device", observedAt = "2026-10-01T08:00:00Z",
            bookTimeMs = 90_000L, ebookLocationRaw = "epubcfi(/6/4)",
        ))
        val position = assertIs<ReadingProgressResult.Conflict>(progress()).remotePosition
        assertEquals("Tablet", position.deviceName)
        assertEquals("device", position.sourceDeviceId)
        assertEquals("2026-10-01T08:00:00Z", position.observedAt)
        assertEquals(90_000L, position.bookTimeMs)
        assertEquals("epubcfi(/6/4)", position.ebookLocationRaw)
        assertEquals(PositionOrigin.Remote, position.origin)
    }

    @Test fun `device timestamps revisions and titles alone do not create a conflict`() = runTest {
        repository.local["book"] = local()
        repository.remote["book"] = local().copy(
            deviceName = "Other tablet", sourceDeviceId = "other", timestamp = 20L,
            observedAt = "2026-10-01T08:00:00Z", remoteRevision = 9L, locatorTitle = "Different chapter name",
        )
        assertIs<ReadingProgressResult.Resolved>(progress())
    }

    @Test fun `differences below one displayed percent remain conflicts`() = runTest {
        repository.local["book"] = local()
        repository.remote["book"] = local().copy(totalProgression = 0.2001)
        assertIs<ReadingProgressResult.Conflict>(progress())
    }

    @Test fun `different text selectors at identical percentage remain conflicts`() = runTest {
        repository.local["book"] = local().copy(cssSelector = "#one")
        repository.remote["book"] = local().copy(cssSelector = "#two")
        assertIs<ReadingProgressResult.Conflict>(progress())
    }

    @Test fun `different book level audio time cannot resolve as the same file offset`() = runTest {
        repository.local["book"] = local().copy(audioTimestampMs = 500L, bookTimeMs = 100_500L)
        repository.remote["book"] = local().copy(audioTimestampMs = 500L, bookTimeMs = 200_500L)
        assertIs<ReadingProgressResult.Conflict>(progress())
    }

    @Test fun `different audio files at the same percentage remain conflicts`() = runTest {
        repository.local["book"] = local().copy(chapterIndex = 1, audioTimestampMs = 500L)
        repository.remote["book"] = local().copy(chapterIndex = 2, audioTimestampMs = 500L)
        assertIs<ReadingProgressResult.Conflict>(progress())
    }

    @Test fun `a settled remote choice does not produce another conflict`() = runTest {
        repository.local["book"] = remote()
        repository.remote["book"] = remote()
        assertIs<ReadingProgressResult.Resolved>(progress())
    }

    @Test fun `duration and chapter count metadata alone do not produce a conflict`() = runTest {
        repository.local["book"] = local()
        repository.remote["book"] = local().copy(totalChapters = 20, totalDurationMs = 120_000L)
        assertIs<ReadingProgressResult.Resolved>(progress())
    }
}
