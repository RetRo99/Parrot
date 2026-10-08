package com.retro99.reader.domain.usecase

import com.github.michaelbull.result.getOrElse
import com.retro99.base.server.LOCAL_SERVER_ID
import com.retro99.base.server.PARROT_CLOUD_SERVER_ID
import com.retro99.database.api.sync.SyncOutboxEntry
import com.retro99.reader.domain.fakes.FakePositionDatabase
import com.retro99.reader.domain.fakes.FakeReaderRepository
import com.retro99.reader.domain.fakes.FakeRepositoryProvider
import com.retro99.reader.domain.fakes.FakeSyncOutboxDatabase
import com.retro99.reader.domain.fakes.StoredPosition
import com.retro99.reader.domain.model.ReadingProgressResult
import com.retro99.reader.domain.model.toPositionDomainModel
import com.retro99.reader.domain.model.toServerPosition
import com.retro99.server.api.InstallationDeviceIdentity
import com.retro99.server.api.SourceDeviceIdentity
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class ConflictDismissalRegressionTest {
    @Test
    fun `cloud Keep Local survives binding to its account`() = runTest {
        checkBoundChoice(PARROT_CLOUD_SERVER_ID)
    }

    @Test
    fun `library Keep Local survives binding to its account`() = runTest {
        checkBoundChoice(LOCAL_SERVER_ID)
    }

    @Test
    fun `cloud dismissal bound to another account cannot hide this accounts conflict`() = runTest {
        val fixture = chosenLocal(PARROT_CLOUD_SERVER_ID)
        fixture.database.mutations[0] = fixture.database.mutations.single().copy(cloudUserId = "another-cloud-account")

        assertIs<ReadingProgressResult.Conflict>(fixture.progress(PARROT_CLOUD_SERVER_ID, "book").getOrElse { error("$it") })
    }

    private suspend fun checkBoundChoice(serverId: String) {
        val fixture = chosenLocal(serverId)
        assertIs<ReadingProgressResult.Resolved>(fixture.progress(serverId, "book").getOrElse { error("$it") })
        fixture.database.mutations[0] = fixture.database.mutations.single().copy(cloudUserId = "cloud-account-uuid")
        assertIs<ReadingProgressResult.Resolved>(fixture.progress(serverId, "book").getOrElse { error("$it") })
    }

    @Test
    fun `dispatched local choice still suppresses its rejected snapshot`() = runTest {
        val fixture = chosenLocal("storyteller")
        fixture.database.mutations[0] = fixture.database.mutations.single().copy(state = SyncOutboxEntry.STATE_DISPATCHED)
        assertTrue(fixture.progress.hasDismissedRemote("storyteller", "book", fixture.remote.toPositionDomainModel("storyteller")))
    }

    @Test
    fun `dismissal never suppresses a new revision at the same place`() = runTest {
        val fixture = chosenLocal("storyteller")
        assertFalse(fixture.progress.hasDismissedRemote(
            "storyteller", "book", fixture.remote.copy(remoteRevision = 10L).toPositionDomainModel("storyteller"),
        ))
    }

    @Test
    fun `dismissal never suppresses a new reading timestamp at the same place`() = runTest {
        val fixture = chosenLocal("storyteller")
        assertFalse(fixture.progress.hasDismissedRemote(
            "storyteller", "book", fixture.remote.copy(timestamp = 300L).toPositionDomainModel("storyteller"),
        ))
    }

    @Test
    fun `dismissal for another book does not suppress this book`() = runTest {
        val fixture = chosenLocal("storyteller")
        fixture.database.mutations[0] = fixture.database.mutations.single().copy(entityId = "other-book")
        assertFalse(fixture.progress.hasDismissedRemote("storyteller", "book", fixture.remote.toPositionDomainModel("storyteller")))
    }

    @Test
    fun `dismissal for another destination does not suppress this destination`() = runTest {
        val fixture = chosenLocal("storyteller")
        fixture.database.mutations[0] = fixture.database.mutations.single().copy(cloudUserId = "other-server")
        assertFalse(fixture.progress.hasDismissedRemote("storyteller", "book", fixture.remote.toPositionDomainModel("storyteller")))
    }

    @Test
    fun `malformed dismissal payload cannot hide a conflict or crash opening`() = runTest {
        val fixture = chosenLocal("storyteller")
        fixture.database.mutations[0] = fixture.database.mutations.single().copy(payload = "not-json")
        assertIs<ReadingProgressResult.Conflict>(fixture.progress("storyteller", "book").getOrElse { error("$it") })
    }

    private suspend fun chosenLocal(serverId: String): Fixture {
        val database = FakePositionDatabase()
        val local = StoredPosition("book", totalProgression = 0.2, timestamp = 100L, localGeneration = 4L, libraryBookId = "book")
        val remote = local.copy(totalProgression = 0.8, progression = 0.8, timestamp = 200L, remoteRevision = 9L)
        database.upsertPosition(local)
        database.upsertRemotePosition(remote)
        val resolver = ResolvePositionConflictUseCase(
            database, InstallationDeviceIdentity { SourceDeviceIdentity("device", "Phone") },
        )
        assertTrue(resolver.useLocal(ReadingProgressResult.Conflict(
            local.toPositionDomainModel(serverId), remote.toPositionDomainModel(serverId),
        )).isOk)
        val repository = FakeReaderRepository(serverId)
        repository.local["book"] = database.local.value.getValue("book").toPositionDomainModel(serverId).toServerPosition()
        // Simulate the next refresh preserving the unchanged rejected server snapshot.
        database.upsertRemotePosition(remote)
        return Fixture(database, remote, GetReadingProgressWithConflictUseCase(
            FakeRepositoryProvider(listOf(repository)), database, FakeSyncOutboxDatabase(database.mutations),
            com.retro99.sync.domain.ProgressAccountResolver {
                if (it == LOCAL_SERVER_ID || it == PARROT_CLOUD_SERVER_ID) "cloud-account-uuid" else it
            },
        ))
    }

    private data class Fixture(
        val database: FakePositionDatabase,
        val remote: StoredPosition,
        val progress: GetReadingProgressWithConflictUseCase,
    )
}
