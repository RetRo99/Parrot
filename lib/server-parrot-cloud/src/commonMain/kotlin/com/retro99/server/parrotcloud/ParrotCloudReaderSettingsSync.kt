package com.retro99.server.parrotcloud

import com.retro99.database.api.reader.ReaderSettingsDatabase
import com.retro99.database.api.reader.ReaderSettingsEntity
import com.retro99.database.api.sync.SyncOutboxDatabase
import com.retro99.database.api.sync.SyncOutboxEntry
import com.retro99.sync.domain.SyncMutationResponse
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.decodeFromJsonElement
import org.koin.core.annotation.Provided
import org.koin.core.annotation.Single

/** Per-setting-key cloud sync. Device-specific TTS voices and custom font selections stay local. */
@Single
class ParrotCloudReaderSettingsSync(
    @Provided private val readerSettingsDatabase: ReaderSettingsDatabase,
    @Provided private val syncOutboxDatabase: SyncOutboxDatabase,
) {
    private val json = Json {
        encodeDefaults = true
        ignoreUnknownKeys = true
        coerceInputValues = true
    }

    /** Backfills local settings that predate durable outbox capture, after cloud changes were pulled. */
    suspend fun enqueueUnsynced(cloudUserId: String) {
        val pendingKeys = syncOutboxDatabase.getPendingIncludingUnassigned(cloudUserId)
            .asSequence()
            .filter { it.entityType == SyncOutboxEntry.ENTITY_TYPE_READER_SETTINGS }
            .map { it.entityId }
            .toSet()

        readerSettingsDatabase.getAll()
            .filter { it.remoteRevision == null && it.deletedAt == null && it.isCloudSyncable() }
            .filterNot { it.key in pendingKeys }
            .forEach { setting ->
                syncOutboxDatabase.enqueue(
                    SyncOutboxEntry.new(
                        entityType = SyncOutboxEntry.ENTITY_TYPE_READER_SETTINGS,
                        entityId = setting.key,
                        operation = SyncOutboxEntry.OPERATION_UPSERT,
                        payload = setting.value,
                        cloudUserId = cloudUserId,
                    ),
                )
            }
    }

    /** Drops pre-policy outbox entries for settings that are intentionally device-only. */
    suspend fun preparePush(entries: List<SyncOutboxEntry>): List<SyncOutboxEntry> {
        entries.filter { entry ->
            entry.entityType == SyncOutboxEntry.ENTITY_TYPE_READER_SETTINGS &&
                !entry.isCloudSyncable()
        }.forEach { entry -> syncOutboxDatabase.delete(entry.mutationId) }
        return entries.filterNot { entry ->
            entry.entityType == SyncOutboxEntry.ENTITY_TYPE_READER_SETTINGS &&
                !entry.isCloudSyncable()
        }
    }

    suspend fun applyRemote(payload: JsonElement, revision: Long, cloudUserId: String) {
        val remote = payload.decodeReaderSetting() ?: return
        if (!remote.settingKey.isCloudSyncable(remote.value)) return

        val pendingLocally = syncOutboxDatabase.getPendingIncludingUnassigned(cloudUserId)
            .any { entry ->
                entry.entityType == SyncOutboxEntry.ENTITY_TYPE_READER_SETTINGS &&
                    entry.entityId == remote.settingKey
            }
        if (pendingLocally) return

        val current = readerSettingsDatabase.getAll().firstOrNull { it.key == remote.settingKey }
        if (current?.remoteRevision?.let { it >= revision } == true) return
        // A custom font file is local-only. Do not replace its selection with a
        // portable font arriving from another device.
        if (remote.settingKey == FONT_FAMILY_KEY && current != null && !current.isCloudSyncable()) return

        readerSettingsDatabase.upsertSettings(
            listOf(
                ReaderSettingsEntity(
                    key = remote.settingKey,
                    value = json.encodeToString(remote.value),
                    remoteRevision = revision,
                    deletedAt = null,
                ),
            ),
        )
    }

    suspend fun onAccepted(entry: SyncOutboxEntry, response: SyncMutationResponse) {
        val revision = response.revision ?: return
        val local = readerSettingsDatabase.getAll().firstOrNull { it.key == entry.entityId } ?: return
        readerSettingsDatabase.upsertSettings(listOf(local.copy(remoteRevision = revision)))
    }

    /** Keep the local edit and rebase it on the server revision after a concurrent write. */
    suspend fun onConflict(entry: SyncOutboxEntry, response: SyncMutationResponse) {
        val local = readerSettingsDatabase.getAll().firstOrNull { it.key == entry.entityId } ?: return
        val remote = response.payload
            ?.let { payload -> json.decodeFromString<JsonElement>(payload).decodeReaderSetting() }
        val remoteRevision = response.revision ?: remote?.remoteRevision
        if (remoteRevision != null) {
            readerSettingsDatabase.upsertSettings(listOf(local.copy(remoteRevision = remoteRevision)))
        }
        if (!local.isCloudSyncable()) return

        syncOutboxDatabase.enqueue(
            SyncOutboxEntry.new(
                entityType = SyncOutboxEntry.ENTITY_TYPE_READER_SETTINGS,
                entityId = local.key,
                operation = SyncOutboxEntry.OPERATION_UPSERT,
                payload = local.value,
                baseRevision = remoteRevision,
                cloudUserId = entry.cloudUserId,
            ),
        )
    }

    private fun JsonElement.decodeReaderSetting(): ParrotCloudReaderSettingPayload? = try {
        json.decodeFromJsonElement<ParrotCloudReaderSettingPayload>(this)
    } catch (_: SerializationException) {
        null
    } catch (_: IllegalArgumentException) {
        null
    }

    private fun SyncOutboxEntry.isCloudSyncable(): Boolean {
        if (entityType != SyncOutboxEntry.ENTITY_TYPE_READER_SETTINGS) return true
        val value = runCatching { json.parseToJsonElement(payload) }.getOrNull() ?: return false
        return entityId.isCloudSyncable(value)
    }

    private fun ReaderSettingsEntity.isCloudSyncable(): Boolean {
        val parsedValue = runCatching { json.parseToJsonElement(value) }.getOrNull() ?: return false
        return key.isCloudSyncable(parsedValue)
    }

    private fun String.isCloudSyncable(value: JsonElement): Boolean = when (this) {
        TTS_VOICE_ID_KEY -> false
        FONT_FAMILY_KEY -> value.asSettingValue() in PORTABLE_FONT_FAMILY_VALUES
        else -> true
    }

    private fun JsonElement.asSettingValue(): String = json.encodeToString(this)

    private companion object {
        const val FONT_FAMILY_KEY = "font_family"
        const val TTS_VOICE_ID_KEY = "tts_voice_id"

        val PORTABLE_FONT_FAMILY_VALUES = setOf(
            "\"default\"",
            "\"serif\"",
            "\"sans-serif\"",
            "\"cursive\"",
            "\"fantasy\"",
            "\"monospace\"",
            "\"AccessibleDfA\"",
            "\"IA Writer Duospace\"",
            "\"OpenDyslexic\"",
            "\"Droid Sans\"",
            "\"Atkinson Hyperlegible\"",
            "\"Literata\"",
            "\"Merriweather\"",
            "\"Source Serif 4\"",
            "\"Noto Sans\"",
            "\"Noto Serif\"",
        )
    }
}

@Serializable
internal data class ParrotCloudReaderSettingPayload(
    @SerialName("setting_key")
    val settingKey: String,
    val value: JsonElement,
    @SerialName("remote_revision")
    val remoteRevision: Long? = null,
)
