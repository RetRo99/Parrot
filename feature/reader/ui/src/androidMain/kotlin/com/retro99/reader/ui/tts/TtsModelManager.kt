package com.retro99.reader.ui.tts

import android.content.Context
import android.util.Log
import com.retro99.analytics.api.Analytics
import com.retro99.analytics.api.ReaderAnalyticsEvent
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.compressors.bzip2.BZip2CompressorInputStream
import org.koin.core.annotation.Provided
import org.koin.core.annotation.Single
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

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

@Single
class TtsModelManager(
    @Provided private val context: Context,
    @Provided private val analytics: Analytics,
) {

    fun isKokoroModelDownloaded(): Boolean {
        val targetDir = File(context.filesDir, KOKORO_MODEL.directory)
        return isComplete(kokoroModelFiles(targetDir))
    }

    fun isSupertonicModelDownloaded(): Boolean {
        val targetDir = File(context.filesDir, SUPERTONIC_MODEL.directory)
        return isComplete(supertonicModelFiles(targetDir))
    }

    suspend fun deleteKokoroModel(): Boolean = deleteModel(KOKORO_MODEL)

    suspend fun deleteSupertonicModel(): Boolean = deleteModel(SUPERTONIC_MODEL)

    private suspend fun deleteModel(specification: ModelSpecification): Boolean =
        withContext(Dispatchers.IO) {
            val paths = listOf(
                File(context.filesDir, specification.directory),
                File(context.filesDir, "${specification.directory}.staging"),
                File(context.cacheDir, specification.archiveName),
                File(context.cacheDir, "${specification.archiveName}.part"),
            )
            try {
                var deleted = true
                paths.forEach { path ->
                    if (path.exists() && !path.deleteRecursively()) {
                        deleted = false
                    }
                }
                val hasRemainingFiles = paths.any { path -> path.exists() }
                if (!deleted || hasRemainingFiles) {
                    analytics.logException(
                        IOException("Some ${specification.name} model files could not be deleted"),
                        "Failed to delete ${specification.name} model",
                    )
                }
                deleted && !hasRemainingFiles
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                analytics.logException(error, "Failed to delete ${specification.name} model")
                false
            }
        }

    suspend fun ensureKokoroModel(
        onProgress: ((TtsPreparationProgress) -> Unit)? = null,
    ): KokoroModelFiles? {
        return ensureModel(
            specification = KOKORO_MODEL,
            files = ::kokoroModelFiles,
            isComplete = ::isComplete,
            onProgress = onProgress,
        )
    }

    suspend fun ensureSupertonicModel(
        onProgress: ((TtsPreparationProgress) -> Unit)? = null,
    ): SupertonicModelFiles? {
        return ensureModel(
            specification = SUPERTONIC_MODEL,
            files = ::supertonicModelFiles,
            isComplete = ::isComplete,
            onProgress = onProgress,
        )
    }

    private suspend fun <T> ensureModel(
        specification: ModelSpecification,
        files: (File) -> T,
        isComplete: (T) -> Boolean,
        onProgress: ((TtsPreparationProgress) -> Unit)?,
    ): T? {
        val startedAt = System.currentTimeMillis()
        val result = loadModel(
            specification = specification,
            files = files,
            isComplete = isComplete,
            onProgress = onProgress,
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
        specification: ModelSpecification,
        files: (File) -> T,
        isComplete: (T) -> Boolean,
        onProgress: ((TtsPreparationProgress) -> Unit)?,
    ): T? {
        val targetDir = File(context.filesDir, specification.directory)
        val modelFiles = files(targetDir)
        if (isComplete(modelFiles)) return modelFiles

        return withContext(Dispatchers.IO) {
            val archive = File(context.cacheDir, specification.archiveName)
            val stagingDir = File(context.filesDir, "${specification.directory}.staging")
            try {
                if (!archive.exists() || archive.length() < specification.minimumArchiveBytes) {
                    Log.i(TAG, "Downloading ${specification.name} model")
                    downloadAtomically(
                        destination = archive,
                        url = specification.url,
                        onProgress = onProgress,
                    )
                } else {
                    Log.i(
                        TAG,
                        "Reusing cached ${specification.name} archive " +
                                "(${archive.length()} bytes)",
                    )
                }

                stagingDir.deleteRecursively()
                stagingDir.mkdirs()
                Log.i(TAG, "Extracting ${specification.name} model")
                extract(archive, stagingDir, onProgress)
                Log.i(TAG, "Extraction finished")
                onProgress?.invoke(TtsPreparationProgress.Finalizing)

                targetDir.deleteRecursively()
                targetDir.parentFile?.mkdirs()
                if (!stagingDir.renameTo(targetDir)) {
                    stagingDir.copyRecursively(targetDir, overwrite = true)
                    stagingDir.deleteRecursively()
                }
                archive.delete()
            } catch (error: CancellationException) {
                archive.delete()
                stagingDir.deleteRecursively()
                throw error
            } catch (error: Exception) {
                archive.delete()
                stagingDir.deleteRecursively()
                analytics.logException(error, "Failed to prepare ${specification.name} model")
                return@withContext null
            }

            if (isComplete(modelFiles)) modelFiles else null
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

    private suspend fun downloadAtomically(
        destination: File,
        url: String,
        onProgress: ((TtsPreparationProgress) -> Unit)?,
    ) {
        val partial = File(destination.parentFile, "${destination.name}.part")
        partial.delete()
        try {
            download(partial, url, onProgress)
            if (!partial.renameTo(destination)) {
                partial.copyTo(destination, overwrite = true)
                partial.delete()
            }
        } catch (error: Exception) {
            partial.delete()
            throw error
        }
    }

    private suspend fun download(
        destination: File,
        url: String,
        onProgress: ((TtsPreparationProgress) -> Unit)?,
    ) {
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = CONNECT_TIMEOUT_MS
            readTimeout = READ_TIMEOUT_MS
            instanceFollowRedirects = true
        }
        connection.connect()
        try {
            if (connection.responseCode !in HTTP_SUCCESS_RANGE) {
                throw IOException("Model download failed with HTTP ${connection.responseCode}")
            }
            val total = connection.contentLengthLong.takeIf { length -> length > 0 } ?: -1L
            connection.inputStream.use { input ->
                destination.outputStream().use { output ->
                    val buffer = ByteArray(BUFFER_SIZE)
                    var downloaded = 0L
                    var lastLoggedPercent = -1
                    var lastReportedPercent = -1
                    var lastReportedBytes = 0L
                    while (true) {
                        currentCoroutineContext().ensureActive()
                        val read = input.read(buffer)
                        if (read < 0) break
                        output.write(buffer, 0, read)
                        downloaded += read
                        if (total > 0) {
                            val percent = ((downloaded * 100) / total).toInt()
                            if (percent != lastReportedPercent) {
                                lastReportedPercent = percent
                                onProgress?.invoke(
                                    TtsPreparationProgress.Downloading(downloaded, total),
                                )
                            }
                            if (percent != lastLoggedPercent && percent % 10 == 0) {
                                lastLoggedPercent = percent
                                Log.i(TAG, "Model download: $percent%")
                            }
                        } else if (downloaded - lastReportedBytes >= PROGRESS_INTERVAL_BYTES) {
                            lastReportedBytes = downloaded
                            onProgress?.invoke(
                                TtsPreparationProgress.Downloading(downloaded, null),
                            )
                        }
                    }
                    if (total <= 0 && downloaded != lastReportedBytes) {
                        onProgress?.invoke(
                            TtsPreparationProgress.Downloading(downloaded, null),
                        )
                    }
                    if (total > 0 && downloaded != total) {
                        throw IOException("Incomplete model download: $downloaded of $total bytes")
                    }
                }
            }
        } finally {
            connection.disconnect()
        }
    }

    private suspend fun extract(
        archive: File,
        targetDir: File,
        onProgress: ((TtsPreparationProgress) -> Unit)?,
    ) {
        val canonicalTargetDir = targetDir.canonicalFile
        val canonicalTargetPrefix = canonicalTargetDir.path + File.separator
        val totalBytes = archive.length().coerceAtLeast(1L)
        var lastReportedPercent = -1
        onProgress?.invoke(
            TtsPreparationProgress.Preparing(
                preparedBytes = 0L,
                totalBytes = totalBytes,
            ),
        )
        archive.inputStream().use { fileInput ->
            BZip2CompressorInputStream(fileInput).use { bzipInput ->
                TarArchiveInputStream(bzipInput).use { tar ->
                    val buffer = ByteArray(BUFFER_SIZE)
                    var entry = tar.nextEntry
                    while (entry != null) {
                        val relativeName = entry.name.substringAfter('/', "")
                        if (relativeName.isNotEmpty()) {
                            val output = File(canonicalTargetDir, relativeName).canonicalFile
                            if (!output.path.startsWith(canonicalTargetPrefix)) {
                                throw IOException(
                                    "Archive entry escapes target directory: ${entry.name}",
                                )
                            }
                            if (entry.isDirectory) {
                                output.mkdirs()
                            } else if (entry.isFile) {
                                output.parentFile?.mkdirs()
                                output.outputStream().use { fileOutput ->
                                    while (true) {
                                        currentCoroutineContext().ensureActive()
                                        val read = tar.read(buffer)
                                        if (read < 0) break
                                        fileOutput.write(buffer, 0, read)
                                        val preparedBytes = bzipInput.compressedCount
                                            .coerceAtMost(totalBytes)
                                        val percent = (
                                                (preparedBytes * 100) / totalBytes
                                                ).toInt()
                                        if (percent != lastReportedPercent) {
                                            lastReportedPercent = percent
                                            onProgress?.invoke(
                                                TtsPreparationProgress.Preparing(
                                                    preparedBytes = preparedBytes,
                                                    totalBytes = totalBytes,
                                                ),
                                            )
                                        }
                                    }
                                }
                            }
                        }
                        entry = tar.nextEntry
                    }
                }
            }
        }
        onProgress?.invoke(
            TtsPreparationProgress.Preparing(
                preparedBytes = totalBytes,
                totalBytes = totalBytes,
            ),
        )
    }

    private companion object {
        val KOKORO_MODEL = ModelSpecification(
            name = "Kokoro",
            url = "https://github.com/k2-fsa/sherpa-onnx/releases/download/tts-models/" +
                    "kokoro-int8-en-v0_19.tar.bz2",
            directory = "tts-models/kokoro-int8-en-v0_19",
            archiveName = "kokoro-int8-en-v0_19.tar.bz2",
            minimumArchiveBytes = 100L * 1024L * 1024L,
        )
        val SUPERTONIC_MODEL = ModelSpecification(
            name = "Supertonic 3",
            url = "https://github.com/k2-fsa/sherpa-onnx/releases/download/tts-models/" +
                    "sherpa-onnx-supertonic-3-tts-int8-2026-05-11.tar.bz2",
            directory = "tts-models/sherpa-onnx-supertonic-3-tts-int8-2026-05-11",
            archiveName = "sherpa-onnx-supertonic-3-tts-int8-2026-05-11.tar.bz2",
            minimumArchiveBytes = 120L * 1024L * 1024L,
        )
        const val KOKORO_MIN_MODEL_BYTES = 100L * 1024L * 1024L
        const val SUPERTONIC_MIN_DURATION_PREDICTOR_BYTES = 3L * 1024L * 1024L
        const val SUPERTONIC_MIN_TEXT_ENCODER_BYTES = 30L * 1024L * 1024L
        const val SUPERTONIC_MIN_VECTOR_ESTIMATOR_BYTES = 70L * 1024L * 1024L
        const val SUPERTONIC_MIN_VOCODER_BYTES = 20L * 1024L * 1024L
        const val CONNECT_TIMEOUT_MS = 30_000
        const val READ_TIMEOUT_MS = 60_000
        const val BUFFER_SIZE = 1 shl 16
        const val PROGRESS_INTERVAL_BYTES = 1_000_000L
        const val TAG = "TtsModelManager"
        val HTTP_SUCCESS_RANGE = 200..299
    }
}

private data class ModelSpecification(
    val name: String,
    val url: String,
    val directory: String,
    val archiveName: String,
    val minimumArchiveBytes: Long,
)
