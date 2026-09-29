package com.retro99.reader.ui.tts

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Describes the downloadable TTS model assets.
 *
 * Models are hosted as plain files (no archives to extract on-device except small
 * zipped data directories), so preparing a voice pack is a pure download.
 * The manifest is fetched from [TtsModelManager.MANIFEST_URL] and every file URL is
 * resolved from it, so the hosting location can change without app updates.
 */
@Serializable
data class TtsModelManifest(
    val schemaVersion: Int,
    val models: List<TtsModelManifestEntry>,
) {

    fun model(id: String): TtsModelManifestEntry? = models.firstOrNull { entry -> entry.id == id }

    companion object {
        private val json = Json { ignoreUnknownKeys = true }

        fun parse(raw: String): TtsModelManifest? = runCatching {
            json.decodeFromString(TtsModelManifest.serializer(), raw)
        }.getOrNull()
    }
}

@Serializable
data class TtsModelManifestEntry(
    val id: String,
    val version: String,
    val files: List<TtsModelFile>,
    /** Optional size of the incremental download from the previous version. */
    val updateSizeBytes: Long? = null,
) {
    val totalBytes: Long
        get() = files.sumOf { file -> file.size }
}

@Serializable
data class TtsModelFile(
    val url: String,
    val path: String,
    val size: Long,
    val sha256: String,
    /** When set, this entry is a zip extracted into a directory with this name. */
    val extractTo: String? = null,
)
