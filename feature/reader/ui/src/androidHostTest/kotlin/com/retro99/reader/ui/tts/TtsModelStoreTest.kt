package com.retro99.reader.ui.tts

import com.retro99.analytics.api.Analytics
import com.retro99.analytics.api.AnalyticsEvent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
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

    // region TTS-F04: the manifest wait

    @Test
    fun `a manifest host that never answers releases the caller inside about five seconds`() =
        runTest {
            // Given a cached manifest from yesterday and a host that accepts and goes quiet
            seedStaleCachedManifest(VERSION_1)
            val silent = TtsModelStoreServer.SilentHost()
            val store = newStore(manifestUrl = silent.address)

            try {
                // When
                val startedAt = System.nanoTime()
                store.refreshManifestIfStale()
                val elapsedMs = (System.nanoTime() - startedAt) / 1_000_000L

                // Then
                assertTrue(
                    elapsedMs <= MANIFEST_WAIT_BUDGET_MS,
                    "refreshManifestIfStale took ${elapsedMs}ms, " +
                        "expected at most ${MANIFEST_WAIT_BUDGET_MS}ms",
                )
                assertEquals(VERSION_1, store.cachedManifest()?.model(MODEL_ID)?.version)
            } finally {
                silent.stop()
            }
        }

    @Test
    fun `a manifest that answers inside the limit is used`() = runTest {
        // Given
        seedStaleCachedManifest(VERSION_1)
        publishVersion(VERSION_2, modelBytes = MODEL_V2)
        server.manifestDelayMs = 2_000L
        val store = newStore()

        // When
        val startedAt = System.nanoTime()
        store.refreshManifestIfStale()
        val elapsedMs = (System.nanoTime() - startedAt) / 1_000_000L

        // Then
        assertTrue(elapsedMs >= 2_000L, "the answer was not waited for: ${elapsedMs}ms")
        assertEquals(VERSION_2, store.cachedManifest()?.model(MODEL_ID)?.version)
    }

    @Test
    fun `an answer that arrives after the deadline does not replace the cached manifest`() =
        runTest {
            // Given a host that answers later than the caller is willing to wait
            seedStaleCachedManifest(VERSION_1)
            publishVersion(VERSION_2, modelBytes = MODEL_V2)
            server.manifestDelayMs = 1_500L
            val store = newStore(manifestFetchTimeoutMs = 400L)

            // When
            store.refreshManifestIfStale()
            val versionOnReturn = store.cachedManifest()?.model(MODEL_ID)?.version

            // Then the caller keeps the cached manifest, and the late answer is dropped
            assertEquals(VERSION_1, versionOnReturn)
            Thread.sleep(2_500L)
            assertEquals(VERSION_1, store.cachedManifest()?.model(MODEL_ID)?.version)
            assertEquals(VERSION_1, newStore().cachedManifest()?.model(MODEL_ID)?.version)
        }

    // endregion

    // region TTS-F05: what an abandoned download leaves behind

    @Test
    fun `after three failed attempts the partial is kept and a later call resumes from it`() =
        runTest {
            // Given a host that cuts every answer short
            publishVersion(VERSION_1, modelBytes = MODEL_V1)
            server.truncateEveryAnswer(VERSION_1, MODEL_NAME, afterBytes = MODEL_V1.size / 4)
            val store = newStore()

            // When all three attempts fail
            assertNull(store.ensureModel(MODEL_ID, ::testFiles, ::isComplete, null, false))

            // Then the bytes are still there to resume from
            assertEquals(listOf("$MODEL_NAME.part"), partialFiles(VERSION_1))
            val keptBytes = File(versionDir(VERSION_1), "$MODEL_NAME.part").length()
            assertTrue(keptBytes > 0L)

            // And a later call resumes rather than starting over
            server.serveEveryAnswerWhole()
            assertNotNull(store.ensureModel(MODEL_ID, ::testFiles, ::isComplete, null, false))
            val lastAttempt = server.requestsFor(VERSION_1, MODEL_NAME).last()
            assertEquals(keptBytes.toInt(), resumeOffset(lastAttempt.range))
            assertEquals(VERSION_1, store.activeVersion(MODEL_ID))
        }

    @Test
    fun `an incomplete folder of a version that is not current is deleted on a refresh`() =
        runTest {
            // Given leftovers from a version the manifest has moved on from
            publishVersion(VERSION_1, modelBytes = MODEL_V1)
            writePartial(OLD_VERSION, MODEL_NAME, ageMs = 0L)
            val store = newStore()

            // When
            store.refreshManifestIfStale()

            // Then
            assertFalse(versionDir(OLD_VERSION).exists(), "the abandoned folder is still there")
        }

    @Test
    fun `a complete folder that is not current is kept, an incomplete one is not`() = runTest {
        // Given the active version, the previous one the update logic keeps, and a wreck
        publishVersion(VERSION_2, modelBytes = MODEL_V2)
        writeCompleteVersion(VERSION_1)
        writeCompleteVersion(VERSION_2)
        markActive(VERSION_2)
        writePartial(OLD_VERSION, MODEL_NAME, ageMs = 0L)
        val store = newStore()

        // When
        store.refreshManifestIfStale()

        // Then
        assertFalse(versionDir(OLD_VERSION).exists())
        assertBytes(MODEL_V1, File(versionDir(VERSION_1), MODEL_NAME))
        assertBytes(MODEL_V1, File(versionDir(VERSION_2), MODEL_NAME))
        assertEquals(VERSION_2, store.activeVersion(MODEL_ID))
    }

    @Test
    fun `a current version partial is kept for a week and no longer`() = runTest {
        // Given two partials of the version the manifest is on
        publishVersion(VERSION_1, modelBytes = MODEL_V1)
        writePartial(VERSION_1, MODEL_NAME, ageMs = EIGHT_DAYS_MS)
        writePartial(VERSION_1, TOKENS_NAME, ageMs = SIX_DAYS_MS)
        val store = newStore()

        // When
        store.refreshManifestIfStale()

        // Then
        assertEquals(listOf("$TOKENS_NAME.part"), partialFiles(VERSION_1))
    }

    @Test
    fun `the active version and the version kept after an update survive the clean-up`() =
        runTest {
            // Given an update, so both versions are on disk and one is active
            publishVersion(VERSION_1, modelBytes = MODEL_V1)
            val store = newStore()
            assertNotNull(store.ensureModel(MODEL_ID, ::testFiles, ::isComplete, null, false))
            publishVersion(VERSION_2, modelBytes = MODEL_V2)
            assertNotNull(store.ensureModel(MODEL_ID, ::testFiles, ::isComplete, null, true))
            // And a manifest that has moved on past both of them
            publishVersion(VERSION_3, modelBytes = MODEL_V2)

            // When
            store.refreshManifestIfStale()

            // Then
            assertBytes(MODEL_V1, File(versionDir(VERSION_1), MODEL_NAME))
            assertBytes(TOKENS, File(versionDir(VERSION_1), TOKENS_NAME))
            assertBytes(MODEL_V2, File(versionDir(VERSION_2), MODEL_NAME))
            assertBytes(TOKENS, File(versionDir(VERSION_2), TOKENS_NAME))
            assertEquals(VERSION_2, store.activeVersion(MODEL_ID))
            assertNotNull(store.activeModelFiles(MODEL_ID, ::testFiles, ::isComplete))
        }

    @Test
    fun `a clean-up during an install leaves the install's own files alone`() = runTest {
        // Given an install of the current version, served slowly
        publishVersion(VERSION_1, modelBytes = MODEL_V1)
        server.throttle(VERSION_1, MODEL_NAME, millisPerBlock = 700L)
        val store = newStore()
        val transferStarted = CountDownLatch(1)
        val install = CoroutineScope(Dispatchers.IO).async {
            store.ensureModel(MODEL_ID, ::testFiles, ::isComplete, { update ->
                val downloaded = (update as? TtsPreparationProgress.Downloading)?.downloadedBytes
                if (downloaded != null && downloaded > 0L) transferStarted.countDown()
            }, false)
        }
        assertTrue(transferStarted.await(20, TimeUnit.SECONDS), "the transfer never started")

        // When the manifest moves on and a refresh sweeps while that install is running
        publishVersion(VERSION_2, modelBytes = MODEL_V2)
        File(File(root, TtsModelStore.MODELS_DIR_NAME), "manifest.json").delete()
        store.refreshManifestIfStale()
        assertTrue(install.isActive, "the install was already over, the case proves nothing")

        // Then the install finishes and owns its directory
        val files = install.await()
        assertNotNull(files)
        assertBytes(MODEL_V1, files.model)
        assertEquals(VERSION_1, store.activeVersion(MODEL_ID))
    }

    // endregion

    // region TTS-F20: deleting a pack while it downloads

    @Test
    fun `deleting a pack while it downloads ends not installed with nothing running`() = runTest {
        // Given an install of the pack, served slowly
        publishVersion(VERSION_1, modelBytes = MODEL_V1)
        server.throttle(VERSION_1, MODEL_NAME, millisPerBlock = 700L)
        val store = newStore()
        val transferStarted = CountDownLatch(1)
        val install = CoroutineScope(Dispatchers.IO).async {
            store.ensureModel(MODEL_ID, ::testFiles, ::isComplete, { update ->
                val downloaded = (update as? TtsPreparationProgress.Downloading)?.downloadedBytes
                if (downloaded != null && downloaded > 0L) transferStarted.countDown()
            }, false)
        }
        assertTrue(transferStarted.await(20, TimeUnit.SECONDS), "the transfer never started")

        // When the same pack is deleted mid-download
        assertTrue(install.isActive, "the install was already over, the case proves nothing")
        val deleted = store.deleteModel(MODEL_ID) { emptyList() }
        val installOutcome = runCatching { install.await() }

        // Then the pack is cleanly not installed and the install did not report success
        assertTrue(deleted)
        assertNull(
            installOutcome.getOrNull(),
            "the install reported success after the pack was deleted",
        )
        assertNull(store.activeVersion(MODEL_ID))
        assertNull(store.activeModelFiles(MODEL_ID, ::testFiles, ::isComplete))
        assertEquals(emptyList<String>(), filesLeftUnderModelRoot())
    }

    // endregion

    // region harness

    private fun filesLeftUnderModelRoot(): List<String> =
        modelRoot().walkTopDown()
            .filter { child -> child.isFile }
            .map { child -> child.toRelativeString(modelRoot()) }
            .sorted()
            .toList()

    private fun writeCompleteVersion(version: String) {
        val dir = versionDir(version).apply { mkdirs() }
        File(dir, MODEL_NAME).writeBytes(MODEL_V1)
        File(dir, TOKENS_NAME).writeBytes(TOKENS)
    }

    private fun markActive(version: String) {
        File(modelRoot().apply { mkdirs() }, TtsModelStore.ACTIVE_MARKER_NAME).writeText(version)
    }

    private fun writePartial(version: String, name: String, ageMs: Long) {
        val dir = versionDir(version).apply { mkdirs() }
        val partial = File(dir, "$name.part")
        partial.writeBytes(MODEL_V1.copyOfRange(0, 1024))
        partial.setLastModified(nowMs - ageMs)
    }

    /** A cached manifest for [version], last written a day and an hour ago. */
    private fun seedStaleCachedManifest(version: String) {
        val cacheFile = File(File(root, TtsModelStore.MODELS_DIR_NAME), "manifest.json")
        cacheFile.parentFile?.mkdirs()
        cacheFile.writeText(
            manifestBody(
                version = version,
                files = listOf(manifestFile(version, MODEL_NAME, MODEL_V1, null)),
            ),
        )
        cacheFile.setLastModified(nowMs - 25L * 60L * 60L * 1_000L)
    }

    internal class TestFiles(val dir: File) {
        val model: File get() = File(dir, MODEL_NAME)
        val tokens: File get() = File(dir, TOKENS_NAME)
    }

    private fun testFiles(dir: File): TestFiles = TestFiles(dir)

    private fun isComplete(files: TestFiles): Boolean =
        files.model.isFile && files.model.length() > 0L &&
            files.tokens.isFile && files.tokens.length() > 0L

    private fun newStore(
        manifestUrl: String = server.manifestAddress,
        manifestFetchTimeoutMs: Long = TtsModelStore.MANIFEST_FETCH_TIMEOUT_MS,
    ): TtsModelStore = TtsModelStore(
        rootDirectory = root,
        manifestUrl = manifestUrl,
        openConnection = ::openLocalConnection,
        now = { nowMs },
        usableSpaceBytes = { PLENTY_OF_SPACE },
        analytics = analytics,
        hardLink = { source, destination ->
            runCatching { Files.createLink(destination.toPath(), source.toPath()) }.isSuccess
        },
        logInfo = {},
        logWarning = { _, _ -> },
        manifestFetchTimeoutMs = manifestFetchTimeoutMs,
        extraPacks = listOf(TtsPackRules(MODEL_ID) { dir -> isComplete(testFiles(dir)) }),
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
        private const val VERSION_3 = "3.0.0"
        private const val OLD_VERSION = "0.9.0"
        private const val SIX_DAYS_MS = 6L * 24L * 60L * 60L * 1_000L
        private const val EIGHT_DAYS_MS = 8L * 24L * 60L * 60L * 1_000L
        private const val PLENTY_OF_SPACE = 8L * 1024L * 1024L * 1024L
        /** The owner's rule: about five seconds, with a second of slack for the machine. */
        private const val MANIFEST_WAIT_BUDGET_MS = 6_000L
        private const val PRODUCTION_CONNECT_TIMEOUT_MS = 30_000
        private const val PRODUCTION_READ_TIMEOUT_MS = 60_000
        private const val WRONG_SHA256 =
            "0000000000000000000000000000000000000000000000000000000000000000"

        private val MODEL_V1 = ByteArray(96 * 1024) { index -> (index % 251).toByte() }
        private val MODEL_V2 = ByteArray(96 * 1024) { index -> (index % 241).toByte() }
        private val TOKENS = "a b c\n".toByteArray()
    }
}
