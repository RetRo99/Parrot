package com.retro99.reader.data.model

import com.retro99.database.api.reader.ReaderSettingsEntity
import com.retro99.reader.domain.model.ReaderSettingsDomainModel
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.jsonObject

internal object ReaderSettingsJsonCodec {

    private val json = Json {
        encodeDefaults = true
        ignoreUnknownKeys = true
        coerceInputValues = true
    }

    fun decode(entries: List<ReaderSettingsEntity>): ReaderSettingsLocalModel {
        if (entries.isEmpty()) {
            return ReaderSettingsLocalModel()
        }
        val merged = json.encodeToJsonElement(ReaderSettingsLocalModel()).jsonObject.toMutableMap()
        entries.forEach { entry ->
            val value = parseValue(entry.value)
            if (value != null) {
                merged[entry.key] = value
            }
        }
        return runCatching {
            json.decodeFromJsonElement<ReaderSettingsLocalModel>(JsonObject(merged))
        }.getOrDefault(ReaderSettingsLocalModel()).let { settings ->
            if (settings.readingSpeedWpm in 51..999) settings
            else settings.copy(readingSpeedWpm = ReaderSettingsDomainModel.DEFAULT_READING_SPEED_WPM)
        }
    }

    /** Repair old pinned bounds on first read, including settings restored by sync. */
    fun readingSpeedRepairs(entries: List<ReaderSettingsEntity>): List<ReaderSettingsEntity> =
        entries.filter { it.key == "readingSpeedWpm" && it.deletedAt == null &&
            it.value.toIntOrNull()?.let { speed -> speed !in 51..999 } == true }
            .map { it.copy(value = ReaderSettingsDomainModel.DEFAULT_READING_SPEED_WPM.toString()) }

    fun encodeDiff(
        settings: ReaderSettingsLocalModel,
        stored: List<ReaderSettingsEntity>,
    ): List<ReaderSettingsEntity> {
        val storedByKey = stored.associateBy { entry -> entry.key }
        val encoded = json.encodeToJsonElement(settings).jsonObject
        return encoded.mapNotNull { (key, value) ->
            val existing = storedByKey[key]
            val encodedValue = value.toString()
            if (existing != null && existing.value == encodedValue && existing.deletedAt == null) {
                null
            } else {
                ReaderSettingsEntity(
                    key = key,
                    value = encodedValue,
                    remoteRevision = existing?.remoteRevision,
                    deletedAt = null,
                )
            }
        }
    }

    fun encodeAll(settings: ReaderSettingsLocalModel): List<ReaderSettingsEntity> {
        return encodeDiff(settings = settings, stored = emptyList())
    }

    private fun parseValue(raw: String): JsonElement? {
        return runCatching { json.parseToJsonElement(raw) }.getOrNull()
    }
}
