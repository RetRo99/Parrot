package com.retro99.reader.domain.usecase

import com.retro99.base.server.LOCAL_SERVER_ID
import com.retro99.base.server.PARROT_CLOUD_SERVER_ID
import com.retro99.database.api.books.PositionEntity
import com.retro99.database.api.sync.SyncOutboxEntry
import com.retro99.reader.domain.fakes.FakePositionDatabase
import com.retro99.reader.domain.fakes.StoredPosition
import com.retro99.reader.domain.model.ReadingProgressResult
import com.retro99.reader.domain.model.toPositionDomainModel
import com.retro99.server.api.InstallationDeviceIdentity
import com.retro99.server.api.SourceDeviceIdentity
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.assertFailsWith

class ResolvePositionConflictUseCaseTest {
    private val database = FakePositionDatabase()
    private val identity = InstallationDeviceIdentity { SourceDeviceIdentity("this-device", "This phone") }
    private val resolver = ResolvePositionConflictUseCase(database, identity)
    private val local = StoredPosition(
        bookUuid = "book", totalProgression = 0.2, timestamp = 100L,
        observedAt = "2026-10-01T08:00:00Z", localGeneration = 4L,
        remoteRevision = 2L, libraryBookId = "book",
    )
    private val remote = local.copy(
        totalProgression = 0.6, progression = 0.6, timestamp = 200L,
        origin = PositionEntity.ORIGIN_REMOTE, remoteRevision = 9L,
        observedAt = "2026-10-02T08:00:00Z", sourceDeviceId = "tablet", deviceName = "Other tablet",
    )

    private suspend fun conflict(serverId: String = "storyteller") : ReadingProgressResult.Conflict {
        database.upsertPosition(local)
        database.upsertRemotePosition(remote)
        return ReadingProgressResult.Conflict(
            local.toPositionDomainModel(serverId), remote.toPositionDomainModel(serverId),
        )
    }

    private fun entry(
        id: String, state: String = SyncOutboxEntry.STATE_PENDING,
        book: String = "book", account: String? = "storyteller",
    ) = SyncOutboxEntry.new(
        entityType = SyncOutboxEntry.ENTITY_TYPE_READING_POSITION, entityId = book,
        operation = SyncOutboxEntry.OPERATION_UPSERT, payload = "{}", cloudUserId = account,
    ).copy(mutationId = id, state = state)

    @Test fun `local choice queues exactly one fresh write based on the displayed remote revision`() = runTest {
        val conflict = conflict(PARROT_CLOUD_SERVER_ID)
        assertTrue(resolver.useLocal(conflict).isOk)
        val write = database.mutations.single()
        assertEquals(9L, write.baseRevision)
        assertEquals(5L, write.localGeneration)
        assertNull(write.cloudUserId)
        assertEquals(9L, database.local.value.getValue("book").remoteRevision)
        assertTrue(database.local.value.getValue("book").timestamp!! > 100L)
        assertTrue(database.remote.isEmpty())
    }

    @Test fun `remote choice is local only and preserves server reading time and attribution`() = runTest {
        assertTrue(resolver.useRemote(conflict()).isOk)
        val saved = database.local.value.getValue("book")
        assertEquals(0.6, saved.totalProgression)
        assertEquals(200L, saved.timestamp)
        assertEquals(remote.observedAt, saved.observedAt)
        assertEquals("tablet", saved.sourceDeviceId)
        assertEquals("Other tablet", saved.deviceName)
        assertEquals(PositionEntity.ORIGIN_REMOTE, saved.origin)
        assertTrue(database.mutations.isEmpty())
        assertTrue(database.remote.isEmpty())
    }

    @Test fun `remote choice clears pending dispatched and preserved conflicts for that copy`() = runTest {
        val conflict = conflict()
        database.mutations += listOf(
            entry("pending"), entry("sent", SyncOutboxEntry.STATE_DISPATCHED),
            entry("conflict", SyncOutboxEntry.STATE_CONFLICT_PRESERVED), entry("other", book = "other"),
        )
        resolver.useRemote(conflict)
        assertEquals(listOf("other"), database.mutations.map { it.mutationId })
    }

    @Test fun `local choice supersedes every old progress state but not another destination`() = runTest {
        val conflict = conflict()
        database.mutations += listOf(entry("old"), entry("conflict", SyncOutboxEntry.STATE_CONFLICT_PRESERVED), entry("other-server", account = "abs"))
        resolver.useLocal(conflict)
        assertEquals(2, database.mutations.size)
        assertEquals("other-server", database.mutations.first().mutationId)
        assertEquals("storyteller", database.mutations.last().cloudUserId)
    }

    @Test fun `local library remote choice uses the displayed cloud candidate without a network repository`() = runTest {
        assertTrue(resolver.useRemote(conflict(LOCAL_SERVER_ID)).isOk)
        assertEquals(0.6, database.local.value.getValue("book").totalProgression)
    }

    @Test fun `library choice clears bound and unbound writes`() = runTest {
        val conflict = conflict(LOCAL_SERVER_ID)
        database.mutations += listOf(entry("unbound", account = null), entry("bound", account = "cloud-user"))
        resolver.useRemote(conflict)
        assertTrue(database.mutations.isEmpty())
    }

    @Test fun `a changed remote baseline does not change the position the person chose`() = runTest {
        val conflict = conflict()
        database.upsertRemotePosition(remote.copy(totalProgression = 0.9, remoteRevision = 10L))
        resolver.useRemote(conflict)
        assertEquals(0.6, database.local.value.getValue("book").totalProgression)
        assertEquals(9L, database.local.value.getValue("book").remoteRevision)
    }

    @Test fun `new reading while the prompt is open is never overwritten by remote choice`() = runTest {
        val conflict = conflict()
        database.upsertPosition(local.copy(localGeneration = 5L, totalProgression = 0.8))
        assertFalse(resolver.useRemote(conflict).isOk)
        assertEquals(0.8, database.local.value.getValue("book").totalProgression)
        assertEquals(remote, database.remote["book"])
    }

    @Test fun `new reading between preparing and committing local choice is protected`() = runTest {
        val conflict = conflict()
        database.beforeResolution = { database.upsertPosition(local.copy(localGeneration = 5L, totalProgression = 0.8)) }
        assertFalse(resolver.useLocal(conflict).isOk)
        assertTrue(database.mutations.isEmpty())
        assertEquals(0.8, database.local.value.getValue("book").totalProgression)
    }

    @Test fun `repeated taps cannot apply the same stale choice twice`() = runTest {
        val conflict = conflict()
        assertTrue(resolver.useLocal(conflict).isOk)
        assertFalse(resolver.useLocal(conflict).isOk)
        assertEquals(1, database.mutations.size)
    }

    @Test fun `database failure leaves candidates and old writes intact`() = runTest {
        val conflict = conflict()
        database.mutations += entry("old")
        database.resolutionFails = true
        assertFalse(resolver.useRemote(conflict).isOk)
        assertEquals(local, database.local.value["book"])
        assertEquals(remote, database.remote["book"])
        assertEquals("old", database.mutations.single().mutationId)
    }

    @Test fun `failed local choice can be retried without losing either candidate`() = runTest {
        val conflict = conflict()
        database.mutations += entry("old")
        database.resolutionFails = true

        assertFalse(resolver.useLocal(conflict).isOk)
        assertEquals(local, database.local.value["book"])
        assertEquals(remote, database.remote["book"])
        assertEquals("old", database.mutations.single().mutationId)

        database.resolutionFails = false
        assertTrue(resolver.useLocal(conflict).isOk)
        assertEquals(local.totalProgression, database.local.value.getValue("book").totalProgression)
        assertEquals(5L, database.local.value.getValue("book").localGeneration)
        assertTrue(database.remote.isEmpty())
        assertEquals(9L, database.mutations.single().baseRevision)
    }

    @Test fun `repeated remote choice cannot advance generation or queue another write`() = runTest {
        val conflict = conflict()
        assertTrue(resolver.useRemote(conflict).isOk)
        val selected = database.local.value.getValue("book")

        assertFalse(resolver.useRemote(conflict).isOk)
        assertEquals(selected, database.local.value.getValue("book"))
        assertEquals(5L, selected.localGeneration)
        assertTrue(database.mutations.isEmpty())
        assertTrue(database.remote.isEmpty())
    }

    @Test fun `missing local position does not consume the remote candidate`() = runTest {
        val conflict = conflict()
        database.deletePosition("book")
        assertFalse(resolver.useRemote(conflict).isOk)
        assertEquals(remote, database.remote["book"])
    }

    @Test fun `candidates from different books or servers are refused`() = runTest {
        val conflict = conflict()
        assertFalse(resolver.useLocal(conflict.copy(remotePosition = conflict.remotePosition.copy(bookUuid = "other"))).isOk)
        assertFalse(resolver.useRemote(conflict.copy(remotePosition = conflict.remotePosition.copy(serverId = "abs"))).isOk)
        assertEquals(local, database.local.value["book"])
    }

    @Test fun `local choice does not pretend an imported server snapshot is new reading`() = runTest {
        val conflict = conflict().copy(localPosition = local.toPositionDomainModel("storyteller").copy(origin = com.retro99.server.api.PositionOrigin.Remote))
        resolver.useLocal(conflict)
        val saved = database.local.value.getValue("book")
        assertEquals(PositionEntity.ORIGIN_REMOTE, saved.origin)
        assertEquals(local.observedAt, saved.observedAt)
    }

    @Test fun `audio book time and opaque ebook location survive a remote choice`() = runTest {
        val conflict = conflict().copy(remotePosition = remote.toPositionDomainModel("storyteller").copy(
            audioTimestampMs = 500L, bookTimeMs = 100_500L, chapterIndex = 2, ebookLocationRaw = "epubcfi(/6/4)",
        ))
        resolver.useRemote(conflict)
        val saved = database.local.value.getValue("book")
        assertEquals(500L, saved.audioTimestampMs)
        assertEquals(100_500L, saved.bookTimeMs)
        assertEquals(2, saved.chapterIndex)
        assertEquals("epubcfi(/6/4)", saved.ebookLocationRaw)
    }

    @Test fun `queued position uses the shared destination payload shape`() = runTest {
        resolver.useLocal(conflict(LOCAL_SERVER_ID))
        val payload = Json.parseToJsonElement(database.mutations.single().payload).jsonObject
        assertEquals("book", payload.getValue("bookUuid").jsonPrimitive.content)
        val position = payload.getValue("position").jsonObject
        assertEquals("book", position.getValue("libraryBookId").jsonPrimitive.content)
        assertEquals("0.2", position.getValue("totalProgression").jsonPrimitive.content)
        assertEquals("this-device", payload.getValue("sourceDevice").jsonObject.getValue("id").jsonPrimitive.content)
    }

    @Test fun `cancellation is propagated instead of converted to a failed choice`() = runTest {
        val conflict = conflict()
        database.beforeResolution = { throw CancellationException() }
        assertFailsWith<CancellationException> { resolver.useRemote(conflict) }
        assertEquals(local, database.local.value["book"])
    }

    @Test fun `explicitly keeping an automatic linked copy position becomes a manual choice`() = runTest {
        val conflict = conflict().copy(localPosition = local.toPositionDomainModel("storyteller").copy(
            origin = com.retro99.server.api.PositionOrigin.LinkedCopy,
        ))
        assertTrue(resolver.useLocal(conflict).isOk)
        assertEquals(PositionEntity.ORIGIN_MANUAL, database.local.value.getValue("book").origin)
        assertEquals(local.observedAt, database.local.value.getValue("book").observedAt)
    }
}
