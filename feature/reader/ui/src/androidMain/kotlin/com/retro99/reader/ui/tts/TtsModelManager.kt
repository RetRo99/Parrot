package com.retro99.reader.ui.tts

import android.content.Context
import android.system.ErrnoException
import android.system.Os
import android.util.Log
import com.retro99.analytics.api.Analytics
import com.retro99.analytics.api.ReaderAnalyticsEvent
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.koin.core.annotation.Provided
import org.koin.core.annotation.Single
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.zip.ZipInputStream

data class KokoroModelFiles(
    val model: File,
    val voices: File,
    val tokens: File,
    val dataDir: File,
)

data class SupertonicModelFiles(
    val durationPredictor: File,
    val textEncoder: File,
    val vectorEstimator: File,
    val vocoder: File,
    val ttsJson: File,
    val unicodeIndexer: File,
    val voiceStyle: File,
)

/**
 * Downloads TTS model assets described by [TtsModelManifest].
 *
 * Each model version installs into its own directory (`<modelId>/<version>/`) and a
 * `.active` marker records which version the engines load from. Updates download
 * side-by-side and the marker only moves once every file is verified, so a failed
 * update never leaves the previous, working version unusable. The previous version is
 * kept until the next update succeeds (unchanged files are hard-linked, so keeping it
 * costs almost no extra space).
 */
@Single
class TtsModelManager(
    @Provided private val context: Context,
    @Provided private val analytics: Analytics,
) {

    fun isKokoroModelDownloaded(): Boolean =
        activeModelFiles(KOKORO_MODEL_ID, ::kokoroModelFiles, ::isComplete) != null

    fun isSupertonicModelDownloaded(): Boolean =
        activeModelFiles(SUPERTONIC_MODEL_ID, ::supertonicModelFiles, ::isComplete) != null

    fun kokoroDownloadSizeBytes(): Long? = manifestDownloadSizeBytes(KOKORO_MODEL_ID)

    fun supertonicDownloadSizeBytes(): Long? = manifestDownloadSizeBytes(SUPERTONIC_MODEL_ID)

    fun kokoroUpdateSizeBytes(): Long? = manifestUpdateSizeBytes(KOKORO_MODEL_ID)

    fun supertonicUpdateSizeBytes(): Long? = manifestUpdateSizeBytes(SUPERTONIC_MODEL_ID)

    fun activeKokoroVersion(): String? = activeVersion(KOKORO_MODEL_ID)

    fun activeSupertonicVersion(): String? = activeVersion(SUPERTONIC_MODEL_ID)

    fun isKokoroUpdateAvailable(): Boolean =
        isUpdateAvailable(KOKORO_MODEL_ID, ::kokoroModelFiles, ::isComplete)

    fun isSupertonicUpdateAvailable(): Boolean =
        isUpdateAvailable(SUPERTONIC_MODEL_ID, ::supertonicModelFiles, ::isComplete)

    /**
     * Refreshes the cached manifest at most once per [MANIFEST_REFRESH_INTERVAL_MS] so
     * update detection does not make a request per screen. No-op while the cache is fresh.
     */
    suspend fun refreshManifestIfStale() {
        withContext(Dispatchers.IO) {
            val cacheFile = manifestCacheFile
            val cacheAgeMs = if (cacheFile.isFile) {
                System.currentTimeMillis() - cacheFile.lastModified()
            } else {
                Long.MAX_VALUE
            }
            if (cacheAgeMs >= MANIFEST_REFRESH_INTERVAL_MS) {
                withTimeoutOrNull(MANIFEST_FETCH_TIMEOUT_MS) {
                    fetchManifest()
                }
            }
        }
    }

    suspend fun deleteKokoroModel(): Boolean = deleteModel(KOKORO_MODEL_ID, ::kokoroLegacyPaths)

    suspend fun deleteSupertonicModel(): Boolean =
        deleteModel(SUPERTONIC_MODEL_ID, ::supertonicLegacyPaths)

    suspend fun ensureKokoroModel(
        onProgress: ((TtsPreparationProgress) -> Unit)? = null,
        updateToLatest: Boolean = false,
    ): KokoroModelFiles? = ensureModel(
        modelId = KOKORO_MODEL_ID,
        files = ::kokoroModelFiles,
        isComplete = ::isComplete,
        onProgress = onProgress,
        updateToLatest = updateToLatest,
    )

    suspend fun ensureSupertonicModel(
        onProgress: ((TtsPreparationProgress) -> Unit)? = null,
        updateToLatest: Boolean = false,
    ): SupertonicModelFiles? = ensureModel(
        modelId = SUPERTONIC_MODEL_ID,
        files = ::supertonicModelFiles,
        isComplete = ::isComplete,
        onProgress = onProgress,
        updateToLatest = updateToLatest,
    )

    private suspend fun <T> ensureModel(
        modelId: String,
        files: (File) -> T,
        isComplete: (T) -> Boolean,
        onProgress: ((TtsPreparationProgress) -> Unit)?,
        updateToLatest: Boolean,
    ): T? {
        val startedAt = System.currentTimeMillis()
        val result = loadModel(
            modelId = modelId,
            files = files,
            isComplete = isComplete,
            onProgress = onProgress,
            updateToLatest = updateToLatest,
        )
        analytics.logEvent(
            ReaderAnalyticsEvent.TtsModelPrepared(
                isSuccess = result != null,
                durationMs = System.currentTimeMillis() - startedAt,
            ),
        )
        return result
    }

    private suspend fun <T> loadModel(
        modelId: String,
        files: (File) -> T,
        isComplete: (T) -> Boolean,
        onProgress: ((TtsPreparationProgress) -> Unit)?,
        updateToLatest: Boolean,
    ): T? {
        // The active version is authoritative: playback never waits for a newer
        // manifest version, and updates are never installed implicitly.
        val activeFiles = activeModelFiles(modelId, files, isComplete)
        if (activeFiles != null && !updateToLatest) return activeFiles

        return withContext(Dispatchers.IO) {
            try {
                val entry = loadManifestEntry(modelId)
                if (entry == null) {
                    Log.w(TAG, "No manifest available for $modelId, using local files if complete")
                    return@withContext activeFiles ?: adoptLocalModel(modelId, files, isComplete)
                }
                if (activeFiles != null && activeVersion(modelId) == entry.version) {
                    return@withContext activeFiles
                }

                val previousVersion = activeVersion(modelId)
                if (!installVersion(entry, modelId, onProgress)) {
                    return@withContext null
                }
                // Only move .active to a version the engine can load, so a bad
                // manifest never replaces a working model.
                val modelFiles = files(versionDir(modelId, entry.version))
                if (!isComplete(modelFiles)) {
                    throw IOException("Installed $modelId ${entry.version} is incomplete")
                }
                writeActiveVersion(modelId, entry.version)
                deleteOutdatedVersions(
                    modelId = modelId,
                    keepVersions = listOfNotNull(previousVersion, entry.version),
                )
                modelFiles
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                analytics.logException(error, "Failed to prepare $modelId model")
                null
            }
        }
    }

    /**
     * Installs every manifest file for [entry] into its own version directory. Files
     * already present are kept (interrupted installs resume), and files that are
     * unchanged from the active version are reused without downloading. Never touches
     * the active version's directory.
     */
    private suspend fun installVersion(
        entry: TtsModelManifestEntry,
        modelId: String,
        onProgress: ((TtsPreparationProgress) -> Unit)?,
    ): Boolean {
        val targetDir = versionDir(modelId, entry.version)
        targetDir.mkdirs()

        val sourceDir = activeVersion(modelId)?.let { version -> versionDir(modelId, version) }
        if (sourceDir != null) {
            for (file in entry.files) {
                if (isInstalled(file, targetDir)) continue
                reuseUnchangedFile(file, sourceDir, targetDir)
            }
        }
        if (entry.files.all { file -> isInstalled(file, targetDir) }) return true

        val missingFiles = entry.files.filterNot { file -> isInstalled(file, targetDir) }
        val remainingBytes = missingFiles.sumOf { file -> file.size }
        val usableSpace = context.filesDir.usableSpace
        if (usableSpace in 0 until (remainingBytes + DISK_MARGIN_BYTES)) {
            val error = IOException(
                "Insufficient storage for ${entry.id} model: " +
                        "$remainingBytes bytes needed, $usableSpace available",
            )
            analytics.logException(error, "Failed to prepare ${entry.id} model")
            return false
        }

        Log.i(
            TAG,
            "Downloading ${entry.id} ${entry.version} " +
                    "(${missingFiles.size} files, $remainingBytes bytes)",
        )
        val reporter = ProgressReporter(entry.totalBytes, onProgress)
        var installedBytes = entry.files.sumOf { file ->
            if (isInstalled(file, targetDir)) file.size else 0L
        }
        reporter.report(installedBytes)
        for (file in entry.files) {
            if (isInstalled(file, targetDir)) continue
            val baseBytes = installedBytes
            val startedAt = System.currentTimeMillis()
            downloadFile(file, targetDir) { downloaded ->
                reporter.report(baseBytes + downloaded)
            }
            installedBytes += file.size
            reporter.report(installedBytes)
            Log.i(
                TAG,
                "Prepared ${file.path} (${file.size} bytes) in " +
                        "${System.currentTimeMillis() - startedAt}ms",
            )
        }
        onProgress?.invoke(TtsPreparationProgress.Finalizing)
        return true
    }

    /**
     * Reuses an unchanged file from the active version (same size and SHA-256) via a
     * hard link so keeping the previous version after an update is nearly free.
     * Zipped data directories are always re-downloaded: only their extraction matters.
     */
    private suspend fun reuseUnchangedFile(
        file: TtsModelFile,
        sourceDir: File,
        targetDir: File,
    ): Boolean {
        if (file.extractTo != null) return false
        val source = sourceDir.resolveInside(file.path)
        if (!source.isFile || source.length() != file.size) return false
        if (!sha256(source).equals(file.sha256, ignoreCase = true)) return false

        val destination = targetDir.resolveInside(file.path)
        destination.delete()
        destination.parentFile?.mkdirs()
        return try {
            Os.link(source.absolutePath, destination.absolutePath)
            true
        } catch (error: ErrnoException) {
            try {
                source.copyTo(destination, overwrite = true)
                true
            } catch (copyError: Exception) {
                destination.delete()
                false
            }
        }
    }

    private fun <T> adoptLocalModel(
        modelId: String,
        files: (File) -> T,
        isComplete: (T) -> Boolean,
    ): T? {
        val completeVersion = (trustedModelRoot(modelId) ?: return null)
            .listFiles()
            ?.filter { child -> child.isDirectory && child.name.isSafeVersionName() }
            ?.sortedByDescending { child -> child.name }
            ?.firstOrNull { child -> isComplete(files(child)) }
            ?: return null
        writeActiveVersion(modelId, completeVersion.name)
        return files(completeVersion)
    }

    private suspend fun downloadFile(
        file: TtsModelFile,
        targetDir: File,
        onBytes: (Long) -> Unit,
    ) {
        val partial = partialFile(targetDir, file)
        var lastError: Exception? = null
        repeat(MAX_DOWNLOAD_ATTEMPTS) { attempt ->
            try {
                transfer(file, partial, onBytes)
                verifyChecksum(partial, file.sha256)
                install(file, targetDir, partial)
                return
            } catch (error: CancellationException) {
                // Keep the partial file: the next attempt or app run resumes it.
                throw error
            } catch (error: Exception) {
                lastError = error
                Log.w(TAG, "Download attempt ${attempt + 1}/${MAX_DOWNLOAD_ATTEMPTS} " +
                        "failed for ${file.path}", error)
                if (attempt < MAX_DOWNLOAD_ATTEMPTS - 1) {
                    delay(RETRY_BACKOFF_MS * (attempt + 1))
                }
            }
        }
        throw IOException("Failed to download ${file.path}", lastError)
    }

    private suspend fun transfer(
        file: TtsModelFile,
        partial: File,
        onBytes: (Long) -> Unit,
    ) {
        var offset = if (partial.isFile) partial.length() else 0L
        when {
            offset > file.size -> {
                partial.delete()
                offset = 0L
            }

            offset == file.size && file.size > 0L -> {
                onBytes(offset)
                return
            }
        }

        val connection = openTrustedConnection(file.url) {
            setRequestProperty("Accept-Encoding", "identity")
            if (offset > 0L) {
                setRequestProperty("Range", "bytes=$offset-")
            }
        }
        try {
            val responseCode = connection.responseCode
            if (responseCode == HTTP_RANGE_NOT_SATISFIABLE) partial.delete()
            offset = com.retro99.packs.packResumeOffset(responseCode, connection.getHeaderField("Content-Range"), offset, file.size)
            if (offset == 0L) partial.delete()
            connection.inputStream.use { input ->
                FileOutputStream(partial, offset > 0L).use { output ->
                    com.retro99.packs.copyPackBytes(file.size, offset,
                        read = { buffer -> input.read(buffer) },
                        write = { buffer, count -> output.write(buffer, 0, count) },
                        onBytes = onBytes)
                }
            }
        } finally {
            connection.disconnect()
        }
    }

    private suspend fun verifyChecksum(partial: File, expectedSha256: String) {
        val actual = sha256(partial)
        if (!actual.equals(expectedSha256, ignoreCase = true)) {
            partial.delete()
            throw IOException("Checksum mismatch for ${partial.name}")
        }
    }

    private suspend fun install(file: TtsModelFile, targetDir: File, partial: File) {
        val extractTo = file.extractTo
        if (extractTo == null) {
            val destination = targetDir.resolveInside(file.path)
            destination.delete()
            destination.parentFile?.mkdirs()
            if (!partial.renameTo(destination)) {
                partial.copyTo(destination, overwrite = true)
                partial.delete()
            }
            return
        }

        // Extract to a staging directory and rename it into place so an interrupted
        // extraction is never mistaken for an installed one.
        val stagingDir = targetDir.resolveInside("$extractTo.tmp")
        stagingDir.deleteRecursivelyNoFollow()
        unzip(partial, stagingDir, stripPrefix = extractTo)
        val outputDir = targetDir.resolveInside(extractTo)
        outputDir.deleteRecursivelyNoFollow()
        if (!stagingDir.renameTo(outputDir)) {
            stagingDir.copyRecursively(outputDir, overwrite = true)
            stagingDir.deleteRecursivelyNoFollow()
        }
        partial.delete()
    }

    private suspend fun unzip(archive: File, targetDir: File, stripPrefix: String) {
        val canonicalTargetDir = targetDir.canonicalFile
        val canonicalTargetPrefix = canonicalTargetDir.path + File.separator
        val prefix = "$stripPrefix/"
        ZipInputStream(archive.inputStream().buffered()).use { zip ->
            var entry = zip.nextEntry
            while (entry != null) {
                val name = entry.name.removePrefix("./")
                when {
                    name.startsWith(prefix) -> {
                        val relativeName = name.substringAfter('/')
                        if (relativeName.isNotEmpty()) {
                            val output = File(canonicalTargetDir, relativeName).canonicalFile
                            if (!output.path.startsWith(canonicalTargetPrefix)) {
                                throw IOException(
                                    "Archive entry escapes target directory: ${entry.name}",
                                )
                            }
                            if (entry.isDirectory) {
                                output.mkdirs()
                            } else {
                                output.parentFile?.mkdirs()
                                output.outputStream().use { fileOutput ->
                                    val buffer = ByteArray(BUFFER_SIZE)
                                    while (true) {
                                        currentCoroutineContext().ensureActive()
                                        val read = zip.read(buffer)
                                        if (read < 0) break
                                        fileOutput.write(buffer, 0, read)
                                    }
                                }
                            }
                        }
                    }

                    name.isBlank() || name.trimEnd('/') == stripPrefix -> Unit

                    else -> throw IOException("Unexpected archive entry: ${entry.name}")
                }
                entry = zip.nextEntry
            }
        }
    }

    private fun isInstalled(file: TtsModelFile, targetDir: File): Boolean {
        val extractTo = file.extractTo
        return if (extractTo != null) {
            targetDir.resolveInside(extractTo).isDirectory
        } else {
            val destination = targetDir.resolveInside(file.path)
            destination.isFile && destination.length() == file.size
        }
    }

    private fun <T> activeModelFiles(
        modelId: String,
        files: (File) -> T,
        isComplete: (T) -> Boolean,
    ): T? {
        val activeDir = activeVersion(modelId)?.let { version -> versionDir(modelId, version) }
            ?: return null
        return files(activeDir).takeIf(isComplete)
    }

    private fun <T> isUpdateAvailable(
        modelId: String,
        files: (File) -> T,
        isComplete: (T) -> Boolean,
    ): Boolean {
        val activeVersion = activeVersion(modelId) ?: return false
        if (activeModelFiles(modelId, files, isComplete) == null) return false
        val latestVersion = cachedManifest()?.trustedModel(modelId)?.version ?: return false
        return latestVersion != activeVersion
    }

    private fun modelRoot(modelId: String): File =
        File(File(context.filesDir, MODELS_DIR_NAME), modelId)

    /** [modelRoot] anchored to the real filesDir; null if a symlink was planted. */
    private fun trustedModelRoot(modelId: String): File? =
        trustedDirectory(context.filesDir, MODELS_DIR_NAME, modelId)

    private fun requireTrustedModelRoot(modelId: String): File =
        trustedModelRoot(modelId) ?: throw IOException("Untrusted $modelId model directory")

    private fun versionDir(modelId: String, version: String): File =
        requireTrustedModelRoot(modelId).resolveInside(version)

    private fun activeVersion(modelId: String): String? =
        trustedModelRoot(modelId)
            ?.let { root -> File(root, ACTIVE_MARKER_NAME) }
            ?.takeIf { marker -> marker.isFile }
            ?.readText()
            ?.trim()
            ?.takeIf { version -> version.isSafeVersionName() }

    private fun writeActiveVersion(modelId: String, version: String) {
        val marker = File(requireTrustedModelRoot(modelId), ACTIVE_MARKER_NAME)
        marker.parentFile?.mkdirs()
        marker.writeText(version)
    }

    private fun deleteOutdatedVersions(modelId: String, keepVersions: List<String>) {
        trustedModelRoot(modelId)?.listFiles()?.forEach { child ->
            if (child.isDirectory && child.name !in keepVersions) {
                child.deleteRecursivelyNoFollow()
            }
        }
    }

    private fun partialFile(targetDir: File, file: TtsModelFile): File =
        targetDir.resolveInside("${file.path}.part").also { partial ->
            partial.parentFile?.mkdirs()
        }

    private val manifestCacheFile: File
        get() = File(File(context.filesDir, MODELS_DIR_NAME), MANIFEST_CACHE_FILE_NAME)

    private fun manifestDownloadSizeBytes(modelId: String): Long? =
        cachedManifest()?.trustedModel(modelId)?.totalBytes

    private fun manifestUpdateSizeBytes(modelId: String): Long? =
        cachedManifest()?.trustedModel(modelId)?.updateSizeBytes

    private suspend fun deleteModel(
        modelId: String,
        legacyPaths: () -> List<File>,
    ): Boolean = withContext(Dispatchers.IO) {
        val paths = listOf(modelRoot(modelId)) + legacyPaths()
        try {
            var deleted = true
            paths.forEach { path ->
                if (!path.deleteRecursivelyNoFollow()) {
                    deleted = false
                }
            }
            val hasRemainingFiles = paths.any { path -> path.exists() }
            if (!deleted || hasRemainingFiles) {
                analytics.logException(
                    IOException("Some $modelId model files could not be deleted"),
                    "Failed to delete $modelId model",
                )
            }
            deleted && !hasRemainingFiles
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            analytics.logException(error, "Failed to delete $modelId model")
            false
        }
    }

    private fun kokoroLegacyPaths(): List<File> = listOf(
        File(context.filesDir, "tts-models/kokoro-int8-en-v0_19"),
        File(context.filesDir, "tts-models/kokoro-int8-en-v0_19.staging"),
        File(context.cacheDir, "kokoro-int8-en-v0_19.tar.bz2"),
        File(context.cacheDir, "kokoro-int8-en-v0_19.tar.bz2.part"),
    )

    private fun supertonicLegacyPaths(): List<File> = listOf(
        File(context.filesDir, "tts-models/sherpa-onnx-supertonic-3-tts-int8-2026-05-11"),
        File(context.filesDir, "tts-models/sherpa-onnx-supertonic-3-tts-int8-2026-05-11.staging"),
        File(context.cacheDir, "sherpa-onnx-supertonic-3-tts-int8-2026-05-11.tar.bz2"),
        File(context.cacheDir, "sherpa-onnx-supertonic-3-tts-int8-2026-05-11.tar.bz2.part"),
    )

    private fun loadManifestEntry(modelId: String): TtsModelManifestEntry? {
        val manifest = fetchManifest() ?: return null
        return manifest.trustedModel(modelId)
    }

    /** Drops a manifest entry whose paths or URLs fail validation. */
    private fun TtsModelManifest.trustedModel(modelId: String): TtsModelManifestEntry? {
        val entry = model(modelId) ?: return null
        val violation = TtsModelManifestValidator.violation(entry) ?: return entry
        Log.w(TAG, "Rejected manifest entry for $modelId: $violation")
        return null
    }

    private fun fetchManifest(): TtsModelManifest? {
        val connection = try {
            openTrustedConnection(MANIFEST_URL) {}
        } catch (error: IOException) {
            Log.w(TAG, "Manifest fetch failed", error)
            return cachedManifest()
        }
        try {
            if (connection.responseCode !in HTTP_SUCCESS_RANGE) {
                Log.w(TAG, "Manifest fetch failed with HTTP ${connection.responseCode}")
                return cachedManifest()
            }
            val body = connection.inputStream.bufferedReader().use { reader -> reader.readText() }
            val manifest = TtsModelManifest.parse(body)
            if (manifest == null) {
                Log.w(TAG, "Manifest could not be parsed")
                return cachedManifest()
            }
            manifestInMemory = manifest
            cacheManifest(body)
            return manifest
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            Log.w(TAG, "Manifest fetch failed", error)
            return cachedManifest()
        } finally {
            connection.disconnect()
        }
    }

    /**
     * Follows redirects by hand so every hop must be https on a GitHub host;
     * automatic following would let a redirect hand us any server's bytes.
     */
    private fun openTrustedConnection(
        url: String,
        configure: HttpURLConnection.() -> Unit,
    ): HttpURLConnection {
        var current = URL(url)
        repeat(MAX_REDIRECTS + 1) {
            if (current.protocol != "https" ||
                !TtsModelManifestValidator.isTrustedRedirectHost(current.host)
            ) {
                throw IOException("Untrusted download location: ${current.host}")
            }
            val connection = (current.openConnection() as HttpURLConnection).apply {
                connectTimeout = CONNECT_TIMEOUT_MS
                readTimeout = READ_TIMEOUT_MS
                instanceFollowRedirects = false
                configure()
            }
            val location = try {
                connection
                    .takeIf { redirect -> redirect.responseCode in HTTP_REDIRECT_CODES }
                    ?.getHeaderField("Location")
            } catch (error: IOException) {
                connection.disconnect()
                throw error
            } ?: return connection
            connection.disconnect()
            current = URL(current, location)
        }
        throw IOException("Too many redirects for $url")
    }

    private fun cacheManifest(body: String) {
        try {
            val parent = manifestCacheFile.parentFile
            parent?.mkdirs()
            manifestCacheFile.writeText(body)
        } catch (error: Exception) {
            Log.w(TAG, "Failed to cache manifest", error)
        }
    }

    private fun cachedManifest(): TtsModelManifest? {
        manifestInMemory?.let { cached -> return cached }
        val body = manifestCacheFile
            .takeIf { cache -> cache.isFile }
            ?.readText()
            ?: return null
        return TtsModelManifest.parse(body)?.also { manifest -> manifestInMemory = manifest }
    }

    @Volatile
    private var manifestInMemory: TtsModelManifest? = null

    private suspend fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(BUFFER_SIZE)
            while (true) {
                currentCoroutineContext().ensureActive()
                val read = input.read(buffer)
                if (read < 0) break
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString(separator = "") { byte ->
            "%02x".format(byte.toInt() and 0xFF)
        }
    }

    private fun kokoroModelFiles(targetDir: File): KokoroModelFiles = KokoroModelFiles(
        model = File(targetDir, "model.int8.onnx"),
        voices = File(targetDir, "voices.bin"),
        tokens = File(targetDir, "tokens.txt"),
        dataDir = File(targetDir, "espeak-ng-data"),
    )

    private fun supertonicModelFiles(targetDir: File): SupertonicModelFiles =
        SupertonicModelFiles(
            durationPredictor = File(targetDir, "duration_predictor.int8.onnx"),
            textEncoder = File(targetDir, "text_encoder.int8.onnx"),
            vectorEstimator = File(targetDir, "vector_estimator.int8.onnx"),
            vocoder = File(targetDir, "vocoder.int8.onnx"),
            ttsJson = File(targetDir, "tts.json"),
            unicodeIndexer = File(targetDir, "unicode_indexer.bin"),
            voiceStyle = File(targetDir, "voice.bin"),
        )

    private fun isComplete(files: KokoroModelFiles): Boolean =
        files.model.isFile && files.model.length() >= KOKORO_MIN_MODEL_BYTES &&
                files.voices.isFile && files.voices.length() > 0 &&
                files.tokens.isFile && files.dataDir.isDirectory

    private fun isComplete(files: SupertonicModelFiles): Boolean =
        files.durationPredictor.hasAtLeast(SUPERTONIC_MIN_DURATION_PREDICTOR_BYTES) &&
                files.textEncoder.hasAtLeast(SUPERTONIC_MIN_TEXT_ENCODER_BYTES) &&
                files.vectorEstimator.hasAtLeast(SUPERTONIC_MIN_VECTOR_ESTIMATOR_BYTES) &&
                files.vocoder.hasAtLeast(SUPERTONIC_MIN_VOCODER_BYTES) &&
                files.ttsJson.hasContent() &&
                files.unicodeIndexer.hasContent() &&
                files.voiceStyle.hasContent()

    private fun File.hasAtLeast(minimumBytes: Long): Boolean =
        isFile && length() >= minimumBytes

    private fun File.hasContent(): Boolean = isFile && length() > 0

    private fun String.isSafeVersionName(): Boolean =
        TtsModelManifestValidator.isSafeFolderName(this)

    private class ProgressReporter(
        private val totalBytes: Long,
        private val onProgress: ((TtsPreparationProgress) -> Unit)?,
    ) {

        private var lastPercentage = -1

        fun report(downloadedBytes: Long) {
            val clamped = downloadedBytes.coerceAtMost(totalBytes)
            val percentage = ((clamped * 100) / totalBytes.coerceAtLeast(1L)).toInt()
            if (percentage != lastPercentage) {
                lastPercentage = percentage
                onProgress?.invoke(TtsPreparationProgress.Downloading(clamped, totalBytes))
            }
        }
    }

    companion object {
        const val MANIFEST_URL =
            "https://github.com/RetRo99/tts-models/releases/latest/download/manifest.json"

        private const val KOKORO_MODEL_ID = "kokoro"
        private const val SUPERTONIC_MODEL_ID = "supertonic"
        private const val MODELS_DIR_NAME = "tts-models"
        private const val MANIFEST_CACHE_FILE_NAME = "manifest.json"
        private const val ACTIVE_MARKER_NAME = ".active"
        private const val KOKORO_MIN_MODEL_BYTES = 100L * 1024L * 1024L
        private const val SUPERTONIC_MIN_DURATION_PREDICTOR_BYTES = 3L * 1024L * 1024L
        private const val SUPERTONIC_MIN_TEXT_ENCODER_BYTES = 30L * 1024L * 1024L
        private const val SUPERTONIC_MIN_VECTOR_ESTIMATOR_BYTES = 70L * 1024L * 1024L
        private const val SUPERTONIC_MIN_VOCODER_BYTES = 20L * 1024L * 1024L
        private const val MAX_DOWNLOAD_ATTEMPTS = 3
        private const val RETRY_BACKOFF_MS = 1_500L
        private const val MANIFEST_FETCH_TIMEOUT_MS = 5_000L
        private const val MANIFEST_REFRESH_INTERVAL_MS = 24L * 60L * 60L * 1000L
        private const val CONNECT_TIMEOUT_MS = 30_000
        private const val READ_TIMEOUT_MS = 60_000
        private const val BUFFER_SIZE = 1 shl 16
        private const val DISK_MARGIN_BYTES = 64L * 1024L * 1024L
        private const val HTTP_PARTIAL_CONTENT = 206
        private const val HTTP_RANGE_NOT_SATISFIABLE = 416
        private const val TAG = "TtsModelManager"
        private val HTTP_SUCCESS_RANGE = 200..299
        private val HTTP_REDIRECT_CODES = setOf(301, 302, 303, 307, 308)
        private const val MAX_REDIRECTS = 5
    }
}
