package com.retro99.reader.domain.usecase

import com.retro99.reader.domain.fakes.FakeReaderRepository
import com.retro99.reader.domain.fakes.FakeRepositoryProvider
import com.retro99.reader.domain.fakes.StoredPosition
import com.retro99.reader.domain.model.toPositionDomainModel
import com.retro99.server.api.InstallationDeviceIdentity
import com.retro99.server.api.SourceDeviceIdentity
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SaveReadingProgressRegressionTest {
    private val repository = FakeReaderRepository("audiobookshelf")
    private val save = SaveReadingProgressUseCase(
        FakeRepositoryProvider(listOf(repository)),
        InstallationDeviceIdentity { SourceDeviceIdentity("this-device", "Phone") },
    )

    @Test
    fun `saving an ebook preserves its raw server location`() = runTest {
        val raw = "{\"cfi\":\"epubcfi(/6/4!/4/2/1:12)\"}"
        val position = StoredPosition("book", totalProgression = 0.5, ebookLocationRaw = raw)

        assertTrue(save(position.toPositionDomainModel(repository.serverId)).isOk)

        assertEquals(raw, repository.syncedSaves.single().ebookLocationRaw)
    }

    @Test
    fun `saving audio preserves chapter and whole book checkpoints with device attribution`() = runTest {
        val position = StoredPosition(
            "book", audioTimestampMs = 12_000L, chapterIndex = 3, bookTimeMs = 172_000L,
            totalDurationMs = 344_000L, totalProgression = 0.5,
        )

        assertTrue(save(position.toPositionDomainModel(repository.serverId)).isOk)

        val saved = repository.syncedSaves.single()
        assertEquals(12_000L, saved.audioTimestampMs)
        assertEquals(3, saved.chapterIndex)
        assertEquals(172_000L, saved.bookTimeMs)
        assertEquals(344_000L, saved.totalDurationMs)
        assertEquals(0.5, saved.totalProgression)
        assertEquals("this-device", saved.sourceDeviceId)
        assertEquals("Phone", saved.deviceName)
    }

    @Test
    fun `failed persistence returns failure without recording a successful save`() = runTest {
        repository.saveFails = true

        assertTrue(save(StoredPosition("book").toPositionDomainModel(repository.serverId)).isErr)
        assertTrue(repository.syncedSaves.isEmpty())
        assertTrue(repository.local.isEmpty())
    }

    @Test
    fun `missing destination returns failure instead of silently saving elsewhere`() = runTest {
        assertTrue(save(StoredPosition("book").toPositionDomainModel("missing-server")).isErr)
        assertTrue(repository.syncedSaves.isEmpty())
    }
}
