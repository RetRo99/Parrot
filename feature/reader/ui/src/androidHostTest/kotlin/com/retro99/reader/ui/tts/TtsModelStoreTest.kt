package com.retro99.reader.ui.tts

import com.retro99.analytics.api.Analytics
import com.retro99.analytics.api.AnalyticsEvent
import kotlinx.coroutines.test.runTest
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Pins what the voice pack store does today: a clean install, a repeated call, a checksum
 * that does not match, a transfer cut short and resumed, an update installed side by side,
 * an update that fails, and a delete. Written green against the code after the run 4 seam
 * ([TtsModelStore]) and not edited afterwards.
 *
 * The pack is a made-up one ("testpack") with two small files, because the real Kokoro and
 * Supertonic completeness rules demand a 100 MB model file. The manifest's addresses are
 * real trusted GitHub asset addresses — [TtsModelManifestValidator] still passes judgement
 * on them — and only the connection opener sends them to 127.0.0.1 instead.
 */
class TtsModelStoreTest {

    private val root: File = Files.createTempDirectory("tts-model-store").toFile().canonicalFile
    private val server = TtsModelStoreServer()
    private val analytics = RecordingAnalytics()

    private var nowMs: Long = System.currentTimeMillis()

    @AfterTest
    fun tearDown() {
        server.stop()
        root.deleteRecursively()
    }

    // region characterization

    @Test
    fun `a clean install writes every file and marks the version active`() = runTest {
        // Given
        publishVersion(VERSION_1, modelBytes = MODEL_V1)
        val store = newStore()

        // When
        val files = store.ensureModel(MODEL_ID, ::testFiles, ::isComplete, null, false)

        // Then
        assertNotNull(files)
        assertBytes(MODEL_V1, files.model)
        assertBytes(TOKENS, files.tokens)
        assertEquals(VERSION_1, store.activeVersion(MODEL_ID))
        assertEquals(emptyList<String>(), partialFiles(VERSION_1))
    }

    @Test
    fun `a second ensure call downloads nothing and asks for no manifest`() = runTest {
        // Given
        publishVersion(VERSION_1, modelBytes = MODEL_V1)
        val store = newStore()
        assertNotNull(store.ensureModel(MODEL_ID, ::testFiles, ::isComplete, null, false))
        val requestsAfterInstall = server.requests.size

        // When
        val files = store.ensureModel(MODEL_ID, ::testFiles, ::isComplete, null, false)

        // Then
        assertNotNull(files)
        assertEquals(requestsAfterInstall, server.requests.size)
    }

    @Test
    fun `a file whose checksum does not match is not installed and the call fails`() = runTest {
        // Given
        publishVersion(VERSION_1, modelBytes = MODEL_V1, modelSha256 = WRONG_SHA256)
        val store = newStore()

        // When
        val files = store.ensureModel(MODEL_ID, ::testFiles, ::isComplete, null, false)

        // Then
        assertNull(files)
        assertNull(store.activeVersion(MODEL_ID))
        assertFalse(File(versionDir(VERSION_1), MODEL_NAME).exists())
        assertEquals(emptyList<String>(), partialFiles(VERSION_1))
        assertEquals(
            TtsModelStore.MAX_DOWNLOAD_ATTEMPTS,
            server.requestsFor(VERSION_1, MODEL_NAME).size,
        )
    }

    @Test
    fun `a transfer cut short is resumed from the partial file and verified`() = runTest {
        // Given
        publishVersion(VERSION_1, modelBytes = MODEL_V1)
        server.truncateNextAnswer(VERSION_1, MODEL_NAME, afterBytes = MODEL_V1.size / 2)
        val store = newStore()

        // When
        val files = store.ensureModel(MODEL_ID, ::testFiles, ::isComplete, null, false)

        // Then
        assertNotNull(files)
        assertBytes(MODEL_V1, files.model)
        val attempts = server.requestsFor(VERSION_1, MODEL_NAME)
        assertEquals(2, attempts.size)
        assertNull(attempts[0].range)
        val resumedFrom = resumeOffset(attempts[1].range)
        assertTrue(
            resumedFrom in 1 until MODEL_V1.size,
            "resumed from $resumedFrom, expected inside 1..${MODEL_V1.size - 1}",
        )
        assertEquals(VERSION_1, store.activeVersion(MODEL_ID))
    }

    @Test
    fun `an update installs beside the old version, reuses files and then moves the marker`() =
        runTest {
            // Given
            publishVersion(VERSION_1, modelBytes = MODEL_V1)
            val store = newStore()
            assertNotNull(store.ensureModel(MODEL_ID, ::testFiles, ::isComplete, null, false))
            publishVersion(VERSION_2, modelBytes = MODEL_V2)
            val markerWhileDownloading = mutableListOf<String?>()

            // When
            val files = store.ensureModel(
                MODEL_ID,
                ::testFiles,
                ::isComplete,
                { markerWhileDownloading += store.activeVersion(MODEL_ID) },
                true,
            )

            // Then
            assertNotNull(files)
            assertBytes(MODEL_V2, files.model)
            assertEquals(VERSION_2, store.activeVersion(MODEL_ID))
            assertTrue(
                markerWhileDownloading.isNotEmpty() &&
                    markerWhileDownloading.all { version -> version == VERSION_1 },
                "the marker moved while the new version was still downloading: " +
                    "$markerWhileDownloading",
            )
            // The old version stays complete and usable.
            assertBytes(MODEL_V1, File(versionDir(VERSION_1), MODEL_NAME))
            assertBytes(TOKENS, File(versionDir(VERSION_1), TOKENS_NAME))
            // The unchanged file is reused, not fetched again.
            assertEquals(
                emptyList<TtsModelStoreServer.Request>(),
                server.requestsFor(VERSION_2, TOKENS_NAME),
            )
            assertEquals(1, server.requestsFor(VERSION_2, MODEL_NAME).size)
        }

    @Test
    fun `a failed update leaves the old version active and usable`() = runTest {
        // Given
        publishVersion(VERSION_1, modelBytes = MODEL_V1)
        val store = newStore()
        assertNotNull(store.ensureModel(MODEL_ID, ::testFiles, ::isComplete, null, false))
        publishVersion(VERSION_2, modelBytes = MODEL_V2, modelSha256 = WRONG_SHA256)

        // When
        val files = store.ensureModel(MODEL_ID, ::testFiles, ::isComplete, null, true)

        // Then
        assertNull(files)
        assertEquals(VERSION_1, store.activeVersion(MODEL_ID))
        val stillActive = store.activeModelFiles(MODEL_ID, ::testFiles, ::isComplete)
        assertNotNull(stillActive)
        assertBytes(MODEL_V1, stillActive.model)
    }

    @Test
    fun `deleteModel removes the pack and the marker`() = runTest {
        // Given
        publishVersion(VERSION_1, modelBytes = MODEL_V1)
        val store = newStore()
        assertNotNull(store.ensureModel(MODEL_ID, ::testFiles, ::isComplete, null, false))

        // When
        val deleted = store.deleteModel(MODEL_ID) { emptyList() }

        // Then
        assertTrue(deleted)
        assertNull(store.activeVersion(MODEL_ID))
        assertNull(store.activeModelFiles(MODEL_ID, ::testFiles, ::isComplete))
        assertFalse(modelRoot().exists())
    }

    // endregion

    // region harness

    internal class TestFiles(val dir: File) {
        val model: File get() = File(dir, MODEL_NAME)
        val tokens: File get() = File(dir, TOKENS_NAME)
    }

    private fun testFiles(dir: File): TestFiles = TestFiles(dir)

    private fun isComplete(files: TestFiles): Boolean =
        files.model.isFile && files.model.length() > 0L &&
            files.tokens.isFile && files.tokens.length() > 0L

    private fun newStore(): TtsModelStore = TtsModelStore(
        rootDirectory = root,
        manifestUrl = server.manifestAddress,
        openConnection = ::openLocalConnection,
        now = { nowMs },
        usableSpaceBytes = { PLENTY_OF_SPACE },
        analytics = analytics,
        hardLink = { source, destination ->
            runCatching { Files.createLink(destination.toPath(), source.toPath()) }.isSuccess
        },
        logInfo = {},
        logWarning = { _, _ -> },
    )

    /**
     * The test seam for the production host-trust check: the addresses are the real
     * trusted ones, this only points them at 127.0.0.1. The timeouts are production's,
     * so a test about a timeout measures the real thing.
     */
    private fun openLocalConnection(
        url: String,
        configure: HttpURLConnection.() -> Unit,
    ): HttpURLConnection =
        (URL(server.localAddressFor(url)).openConnection() as HttpURLConnection).apply {
            connectTimeout = PRODUCTION_CONNECT_TIMEOUT_MS
            readTimeout = PRODUCTION_READ_TIMEOUT_MS
            instanceFollowRedirects = false
            configure()
        }

    private fun publishVersion(
        version: String,
        modelBytes: ByteArray,
        modelSha256: String? = null,
    ) {
        server.putAsset(version, MODEL_NAME, modelBytes)
        server.putAsset(version, TOKENS_NAME, TOKENS)
        server.manifestBody = manifestBody(
            version = version,
            files = listOf(
                manifestFile(version, MODEL_NAME, modelBytes, modelSha256),
                manifestFile(version, TOKENS_NAME, TOKENS, null),
            ),
        )
    }

    private fun manifestFile(
        version: String,
        name: String,
        bytes: ByteArray,
        sha256Override: String?,
    ): String = """
        {"url":"${server.assetUrl(version, name)}","path":"$name",
        "size":${bytes.size},"sha256":"${sha256Override ?: TtsModelStoreServer.sha256(bytes)}"}
    """.trimIndent().replace("\n", "")

    private fun manifestBody(version: String, files: List<String>): String =
        """{"schemaVersion":1,"models":[{"id":"$MODEL_ID","version":"$version",""" +
            """"files":[${files.joinToString(",")}]}]}"""

    private fun modelRoot(): File = File(File(root, TtsModelStore.MODELS_DIR_NAME), MODEL_ID)

    private fun versionDir(version: String): File = File(modelRoot(), version)

    private fun partialFiles(version: String): List<String> =
        versionDir(version).listFiles()
            ?.filter { child -> child.name.endsWith(".part") }
            ?.map { child -> child.name }
            ?.sorted()
            ?: emptyList()

    private fun resumeOffset(range: String?): Int {
        assertNotNull(range, "no Range header on the second attempt")
        return Regex("bytes=([0-9]+)-").find(range)?.groupValues?.get(1)?.toInt()
            ?: error("unparsable Range header: $range")
    }

    private fun assertBytes(expected: ByteArray, actual: File) {
        assertTrue(actual.isFile, "${actual.name} is missing")
        assertTrue(
            expected.contentEquals(actual.readBytes()),
            "${actual.name} holds different bytes",
        )
    }

    internal class RecordingAnalytics : Analytics {
        val events = mutableListOf<AnalyticsEvent>()
        val exceptions = mutableListOf<Throwable>()

        override fun logException(throwable: Throwable, message: String?) {
            exceptions += throwable
        }

        override fun logEvent(event: AnalyticsEvent) {
            events += event
        }

        override fun setUserId(userId: String?) = Unit
    }

    // endregion

    companion object {
        private const val MODEL_ID = "testpack"
        private const val MODEL_NAME = "model.bin"
        private const val TOKENS_NAME = "tokens.txt"
        private const val VERSION_1 = "1.0.0"
        private const val VERSION_2 = "2.0.0"
        private const val PLENTY_OF_SPACE = 8L * 1024L * 1024L * 1024L
        private const val PRODUCTION_CONNECT_TIMEOUT_MS = 30_000
        private const val PRODUCTION_READ_TIMEOUT_MS = 60_000
        private const val WRONG_SHA256 =
            "0000000000000000000000000000000000000000000000000000000000000000"

        private val MODEL_V1 = ByteArray(96 * 1024) { index -> (index % 251).toByte() }
        private val MODEL_V2 = ByteArray(96 * 1024) { index -> (index % 241).toByte() }
        private val TOKENS = "a b c\n".toByteArray()
    }
}
