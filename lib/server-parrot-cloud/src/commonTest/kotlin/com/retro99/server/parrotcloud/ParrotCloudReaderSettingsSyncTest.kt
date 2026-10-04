package com.retro99.server.parrotcloud

import com.retro99.database.api.reader.ReaderSettingsDatabase
import com.retro99.database.api.reader.ReaderSettingsEntity
import com.retro99.database.api.reader.ReaderSettingsMutation
import com.retro99.database.api.sync.SyncOutboxDatabase
import com.retro99.database.api.sync.SyncOutboxEntry
import com.retro99.sync.domain.SyncMutationResponse
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals

class ParrotCloudReaderSettingsSyncTest {
    @Test
    fun backfillQueuesPortableUnversionedSettingsButNotDeviceSpecificValues() = runTest {
        val settings = SettingsDatabase(
            listOf(
                setting("theme", "\"DARK\""),
                setting("font_family", "\"serif\""),
                setting("tts_voice_id", "\"platform-voice\""),
            ),
        )
        val outbox = SettingsOutbox()
        val sync = ParrotCloudReaderSettingsSync(settings, outbox)

        sync.enqueueUnsynced("cloud-user")

        assertEquals(listOf("theme", "font_family"), outbox.entries.map { it.entityId })
    }

    @Test
    fun backfillKeepsCustomFontSelectionOnDevice() = runTest {
        val settings = SettingsDatabase(listOf(setting("font_family", "\"local-family\"")))
        val outbox = SettingsOutbox()

        ParrotCloudReaderSettingsSync(settings, outbox).enqueueUnsynced("cloud-user")

        assertEquals(emptyList(), outbox.entries)
    }

    @Test
    fun pulledCloudSettingAppliesWhenNoLocalMutationIsPending() = runTest {
        val settings = SettingsDatabase()
        val sync = ParrotCloudReaderSettingsSync(settings, SettingsOutbox())

        sync.applyRemote(
            payload = Json.parseToJsonElement("""{"setting_key":"theme","value":"DARK"}"""),
            revision = 4,
            cloudUserId = "cloud-user",
        )

        assertEquals(setting("theme", "\"DARK\"", revision = 4), settings.rows.single())
    }

    @Test
    fun pulledSettingDoesNotOverwriteAQueuedLocalEdit() = runTest {
        val local = setting("theme", "\"DARK\"", revision = 2)
        val settings = SettingsDatabase(listOf(local))
        val outbox = SettingsOutbox().apply {
            enqueue(mutation("theme", "\"DARK\"", baseRevision = 2))
        }
        val sync = ParrotCloudReaderSettingsSync(settings, outbox)

        sync.applyRemote(
            payload = Json.parseToJsonElement("""{"setting_key":"theme","value":"LIGHT"}"""),
            revision = 3,
            cloudUserId = "cloud-user",
        )

        assertEquals(local, settings.rows.single())
    }

    @Test
    fun conflictPreservesLatestLocalValueAndQueuesItAgainstRemoteRevision() = runTest {
        val local = setting("theme", "\"DARK\"", revision = 2)
        val settings = SettingsDatabase(listOf(local))
        val outbox = SettingsOutbox()
        val sync = ParrotCloudReaderSettingsSync(settings, outbox)

        sync.onConflict(
            entry = mutation("theme", "\"DARK\"", baseRevision = 2),
            response = SyncMutationResponse(
                mutationId = "in-flight",
                status = "conflict",
                revision = 3,
                payload = """{"setting_key":"theme","value":"LIGHT","remote_revision":3}""",
                reason = "stale_reader_setting",
            ),
        )

        assertEquals(local.copy(remoteRevision = 3), settings.rows.single())
        assertEquals(1, outbox.entries.size)
        assertEquals("\"DARK\"", outbox.entries.single().payload)
        assertEquals(3L, outbox.entries.single().baseRevision)
    }

    private fun setting(key: String, value: String, revision: Long? = null) =
        ReaderSettingsEntity(key, value, revision, deletedAt = null)

    private fun mutation(key: String, value: String, baseRevision: Long?) = SyncOutboxEntry(
        mutationId = "in-flight",
        cloudUserId = "cloud-user",
        entityType = SyncOutboxEntry.ENTITY_TYPE_READER_SETTINGS,
        entityId = key,
        operation = SyncOutboxEntry.OPERATION_UPSERT,
        payload = value,
        baseRevision = baseRevision,
        createdAt = "2026-10-07T12:00:00Z",
        attemptCount = 0,
        nextAttemptAt = null,
        lastError = null,
        state = SyncOutboxEntry.STATE_DISPATCHED,
    )

    private class SettingsDatabase(initial: List<ReaderSettingsEntity> = emptyList()) : ReaderSettingsDatabase {
        val rows = initial.toMutableList()

        override suspend fun getAll(): List<ReaderSettingsEntity> = rows.toList()

        override fun observeAll(): Flow<List<ReaderSettingsEntity>> = flowOf(rows.toList())

        override suspend fun upsertSettings(settings: List<ReaderSettingsEntity>) {
            settings.forEach { setting ->
                rows.removeAll { it.key == setting.key }
                rows += setting
            }
        }

        override suspend fun upsertSettingsWithMutations(mutations: List<ReaderSettingsMutation>) {
            upsertSettings(mutations.map { it.settings })
        }
    }

    private class SettingsOutbox : SyncOutboxDatabase {
        val entries = mutableListOf<SyncOutboxEntry>()

        override suspend fun enqueue(entry: SyncOutboxEntry) {
            entries.removeAll {
                it.cloudUserId == entry.cloudUserId &&
                    it.entityType == entry.entityType &&
                    it.entityId == entry.entityId &&
                    it.state == SyncOutboxEntry.STATE_PENDING
            }
            entries += entry
        }

        override suspend fun bindUnassignedMutations(cloudUserId: String) = Unit
        override suspend fun getPending(cloudUserId: String) = entries.filter { it.cloudUserId == cloudUserId }
        override suspend fun updateBaseRevision(mutationId: String, baseRevision: Long) = Unit
        override suspend fun markDispatched(mutationId: String) = Unit
        override suspend fun markConflict(mutationId: String, error: String) = Unit
        override suspend fun delete(mutationId: String) { entries.removeAll { it.mutationId == mutationId } }
        override suspend fun deleteByEntityType(entityType: String) { entries.removeAll { it.entityType == entityType } }
        override suspend fun recordFailure(mutationId: String, nextAttemptAt: String, error: String) = Unit
        override suspend fun coalesce(entityType: String, entityId: String, entry: SyncOutboxEntry) = enqueue(entry)
        override suspend fun clearAllData() { entries.clear() }

        override suspend fun getPendingIncludingUnassigned(cloudUserId: String) = entries.filter {
            it.cloudUserId == cloudUserId || it.cloudUserId == null
        }
    }
}
