package com.retro99.reader.ui.tts

import android.content.Context
import android.system.ErrnoException
import android.system.Os
import android.util.Log
import com.retro99.analytics.api.Analytics
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

/**
 * Downloads TTS model assets described by [TtsModelManifest].
 *
 * Each model version installs into its own directory (`<modelId>/<version>/`) and a
 * `.active` marker records which version the engines load from. Updates download
 * side-by-side and the marker only moves once every file is verified, so a failed
 * update never leaves the previous, working version unusable. The previous version is
 * kept until the next update succeeds (unchanged files are hard-linked, so keeping it
 * costs almost no extra space).
 *
 * All of that logic lives in [TtsModelStore]; this class only knows the Android values
 * it needs — the files directory, the manifest address, the trusted connection opener,
 * the clock and the free-space check.
 */
@Single
class TtsModelManager(
    @Provided private val context: Context,
    @Provided private val analytics: Analytics,
) {

    internal val store: TtsModelStore by lazy {
        TtsModelStore(
            rootDirectory = context.filesDir,
            manifestUrl = MANIFEST_URL,
            openConnection = { url, configure -> openTrustedConnection(url, configure) },
            now = { System.currentTimeMillis() },
            usableSpaceBytes = { context.filesDir.usableSpace },
            analytics = analytics,
            hardLink = ::hardLink,
            logInfo = { message -> Log.i(TAG, message) },
            logWarning = { message, error -> Log.w(TAG, message, error) },
        )
    }

    fun isKokoroModelDownloaded(): Boolean = store.isKokoroModelDownloaded()

    fun isSupertonicModelDownloaded(): Boolean = store.isSupertonicModelDownloaded()

    fun kokoroDownloadSizeBytes(): Long? = store.kokoroDownloadSizeBytes()

    fun supertonicDownloadSizeBytes(): Long? = store.supertonicDownloadSizeBytes()

    fun kokoroUpdateSizeBytes(): Long? = store.kokoroUpdateSizeBytes()

    fun supertonicUpdateSizeBytes(): Long? = store.supertonicUpdateSizeBytes()

    fun activeKokoroVersion(): String? = store.activeKokoroVersion()

    fun activeSupertonicVersion(): String? = store.activeSupertonicVersion()

    fun isKokoroUpdateAvailable(): Boolean = store.isKokoroUpdateAvailable()

    fun isSupertonicUpdateAvailable(): Boolean = store.isSupertonicUpdateAvailable()

    suspend fun refreshManifestIfStale() = store.refreshManifestIfStale()

    suspend fun deleteKokoroModel(): Boolean =
        store.deleteModel(TtsModelStore.KOKORO_MODEL_ID, ::kokoroLegacyPaths)

    suspend fun deleteSupertonicModel(): Boolean =
        store.deleteModel(TtsModelStore.SUPERTONIC_MODEL_ID, ::supertonicLegacyPaths)

    suspend fun ensureKokoroModel(
        onProgress: ((TtsPreparationProgress) -> Unit)? = null,
        updateToLatest: Boolean = false,
    ): KokoroModelFiles? = store.ensureKokoroModel(onProgress, updateToLatest)

    suspend fun ensureSupertonicModel(
        onProgress: ((TtsPreparationProgress) -> Unit)? = null,
        updateToLatest: Boolean = false,
    ): SupertonicModelFiles? = store.ensureSupertonicModel(onProgress, updateToLatest)

    fun kokoroModelFilesIfPresent(): KokoroModelFiles? = store.kokoroModelFilesIfPresent()

    fun supertonicModelFilesIfPresent(): SupertonicModelFiles? =
        store.supertonicModelFilesIfPresent()

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

    /** A hard link, so keeping the previous version after an update is nearly free. */
    private fun hardLink(source: File, destination: File): Boolean = try {
        Os.link(source.absolutePath, destination.absolutePath)
        true
    } catch (error: ErrnoException) {
        false
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

    companion object {
        const val MANIFEST_URL =
            "https://github.com/RetRo99/tts-models/releases/latest/download/manifest.json"

        private const val TAG = "TtsModelManager"
        private const val CONNECT_TIMEOUT_MS = 30_000
        private const val READ_TIMEOUT_MS = 60_000
        private val HTTP_REDIRECT_CODES = setOf(301, 302, 303, 307, 308)
        private const val MAX_REDIRECTS = 5
    }
}
