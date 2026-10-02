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

/**
 * Guards against a tampered manifest writing outside the model directory or
 * pulling files from an unexpected host. The manifest itself is not signed.
 */
object TtsModelManifestValidator {

    const val TRUSTED_URL_PREFIX = "https://github.com/RetRo99/tts-models/releases/download/"

    private val segment = Regex("^[A-Za-z0-9._-]+$")
    private val relativePath = Regex("^[A-Za-z0-9._-]+(/[A-Za-z0-9._-]+)*$")
    private val releaseAsset = Regex("^[A-Za-z0-9._-]+/[A-Za-z0-9._-]+$")
    private val sha256 = Regex("^[0-9A-Fa-f]{64}$")

    /** Largest single asset and whole model we accept; keeps size sums exact. */
    const val MAX_FILE_BYTES = 4L * 1024L * 1024L * 1024L
    const val MAX_TOTAL_BYTES = 8L * 1024L * 1024L * 1024L

    /** Returns why [entry] is unsafe, or null when every field is safe to use. */
    fun violation(entry: TtsModelManifestEntry): String? {
        if (!isSafeFolderName(entry.version)) return "unsafe version '${entry.version}'"
        if (entry.files.isEmpty()) return "no files"
        val paths = entry.files.map { file -> file.path }
        if (paths.toSet().size != paths.size) return "duplicate file paths"
        var totalBytes = 0L
        entry.files.forEach { file ->
            if (!isSafeRelativePath(file.path)) return "unsafe path '${file.path}'"
            val extractTo = file.extractTo
            if (extractTo != null && !isSafeFolderName(extractTo)) {
                return "unsafe extractTo '$extractTo'"
            }
            if (!isTrustedUrl(file.url)) return "untrusted url '${file.url}'"
            if (file.size !in 0L..MAX_FILE_BYTES) return "bad size for '${file.path}'"
            if (!sha256.matches(file.sha256)) return "bad sha256 for '${file.path}'"
            // Each size is capped, so this sum cannot overflow.
            totalBytes += file.size
            if (totalBytes > MAX_TOTAL_BYTES) return "model too large"
        }
        return null
    }

    fun isSafeFolderName(name: String): Boolean = isSafeSegment(name)

    fun isSafeRelativePath(path: String): Boolean =
        relativePath.matches(path) && path.split('/').all(::isSafeSegment)

    /**
     * Hosts a download may be redirected to: GitHub itself and its release
     * asset CDN. Checked on every hop because redirects are not signed.
     */
    fun isTrustedRedirectHost(host: String): Boolean {
        val normalized = host.lowercase()
        return normalized == "github.com" || normalized.endsWith(".githubusercontent.com")
    }

    fun isTrustedUrl(url: String): Boolean {
        if (!url.startsWith(TRUSTED_URL_PREFIX)) return false
        val asset = url.removePrefix(TRUSTED_URL_PREFIX)
        return releaseAsset.matches(asset) && asset.split('/').all(::isSafeSegment)
    }

    private fun isSafeSegment(value: String): Boolean =
        segment.matches(value) && value != "." && value != ".."
}
