package com.retro99.reader.domain.usecase

import com.retro99.reader.domain.fakes.FakeReaderRepository
import com.retro99.reader.domain.fakes.FakeRepositoryProvider
import com.retro99.reader.domain.fakes.serverPosition
import com.retro99.reader.domain.fakes.FakePositionDatabase
import com.retro99.reader.domain.fakes.StoredPosition
import com.retro99.reader.domain.model.ReadingProgressResult
import com.retro99.reader.domain.model.toPositionDomainModel
import com.retro99.reader.domain.model.PositionDomainModel
import com.retro99.server.api.PositionOrigin
import com.retro99.server.api.InstallationDeviceIdentity
import com.retro99.server.api.SourceDeviceIdentity
import com.retro99.server.api.TextAnchor
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

/** Each writer of positions stamps where the position came from (§1.4). */
class PositionOriginWritersTest {

    private val repository = FakeReaderRepository(serverId = "st-1")
    private val provider = FakeRepositoryProvider(readers = listOf(repository))
    private val installationDeviceIdentity = InstallationDeviceIdentity {
        SourceDeviceIdentity("aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa", "Test tablet")
    }

    @Test
    fun `the reader saving progress stamps user, now and the anchor`() = runTest {
        // Given
        val anchor = TextAnchor(before = "It was", after = "the best of times")

        // When
        SaveReadingProgressUseCase(provider, installationDeviceIdentity)(domainPosition(textAnchor = anchor))

        // Then
        val saved = repository.syncedSaves.single()
        assertEquals(PositionOrigin.User, saved.origin)
        assertNotNull(saved.observedAt)
        assertEquals(anchor, saved.textAnchor)
        assertEquals("aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa", saved.sourceDeviceId)
        assertEquals("Test tablet", saved.deviceName)
    }

    @Test
    fun `choosing the server's position stamps remote and its own time`() = runTest {
        // Given
        repository.remote["book-1"] = serverPosition(
            bookUuid = "book-1",
            serverId = "st-1",
            totalProgression = 0.6,
            observedAt = "2026-10-01T08:00:00Z",
        )

        // When
        val database = FakePositionDatabase()
        database.upsertPosition(StoredPosition("book-1"))
        ResolvePositionConflictUseCase(database, installationDeviceIdentity).useRemote(
            ReadingProgressResult.Conflict(domainPosition(null), repository.remote.getValue("book-1").toPositionDomainModel()),
        )

        // Then
        val saved = database.local.value.getValue("book-1")
        assertEquals(PositionOrigin.Remote.value, saved.origin)
        assertEquals("2026-10-01T08:00:00Z", saved.observedAt)
    }

    @Test
    fun `keeping this device's position keeps its origin`() = runTest {
        // Given
        repository.local["book-1"] = serverPosition(
            bookUuid = "book-1",
            serverId = "st-1",
            totalProgression = 0.3,
            origin = PositionOrigin.User,
        )

        // When
        val database = FakePositionDatabase()
        database.upsertPosition(StoredPosition("book-1"))
        ResolvePositionConflictUseCase(database, installationDeviceIdentity).useLocal(
            ReadingProgressResult.Conflict(repository.local.getValue("book-1").toPositionDomainModel(), domainPosition(null)),
        )

        // Then
        assertEquals(PositionOrigin.User.value, database.local.value.getValue("book-1").origin)
    }

    private fun domainPosition(textAnchor: TextAnchor?) = PositionDomainModel(
        bookUuid = "book-1",
        serverId = "st-1",
        timestamp = null,
        createdAt = null,
        updatedAt = null,
        locatorHref = "chapter-1.xhtml",
        locatorType = null,
        locatorTitle = null,
        locatorTarget = null,
        audioTimestampMs = null,
        chapterIndex = null,
        progression = 0.2,
        totalChapters = null,
        totalDurationMs = null,
        totalProgression = 0.1,
        position = null,
        textAnchor = textAnchor,
    )
}
