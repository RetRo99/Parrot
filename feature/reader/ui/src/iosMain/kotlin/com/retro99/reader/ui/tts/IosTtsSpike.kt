@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.retro99.reader.ui.tts

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okio.Path.Companion.toPath
import platform.Foundation.NSDate
import platform.Foundation.timeIntervalSinceNow
import platform.Foundation.NSDocumentDirectory
import platform.Foundation.NSFileManager
import platform.Foundation.NSUserDomainMask

/**
 * Throwaway spike seam: Swift implements this interface and registers it at startup,
 * exactly like `EpubReaderBridgeRegistry` / `ReadiumEpubReaderBridge`.
 *
 * Proof target for the plan's synthesis seam: a call starts in Kotlin `iosMain`,
 * crosses into Swift, drives sherpa-onnx's C API, and comes back with a real WAV path.
 */
interface IosTtsSpikeBridge {
    /** Loads the Kokoro engine from [modelDir]; returns "sampleRate=.. speakers=.." or an error. */
    fun loadEngine(modelDir: String, onError: (String) -> Unit): String?

    /** Synthesises [text] to [outputPath]; returns a stats string with peak/rms, or an error. */
    fun synthesize(
        text: String,
        speakerId: Int,
        speed: Float,
        outputPath: String,
        onError: (String) -> Unit,
    ): String?

    fun peakMemoryBytes(): Long

    /** AVQueuePlayer probe over the given WAVs; returns a report string. */
    fun probeQueuePlayback(wavPaths: List<String>): String

    fun pitchCapabilities(): String
}

object IosTtsSpikeBridgeRegistry {
    var bridge: IosTtsSpikeBridge? = null

    fun register(bridge: IosTtsSpikeBridge) {
        this.bridge = bridge
    }
}

/**
 * Drives the spike: engine load, cold and warm synthesis with timings and memory,
 * then the queue-player probe. Call [run] once from Swift and read [lastReport].
 */
object IosTtsSpike {

    var lastReport: String = ""
        private set

    suspend fun run(modelDir: String): String {
        val bridge = IosTtsSpikeBridgeRegistry.bridge ?: return "spike bridge not registered"
        val outDir = documentsPath()
        val coldOut = "$outDir/spike-cold.wav"
        val warmOut = "$outDir/spike-warm.wav"
        val text =
            "The parrot was sitting on its perch, turning the pages of a small book " +
                "with one claw, and reading aloud to anyone who would listen."

        val lines = mutableListOf<String>()
        lines += "modelDir=$modelDir"

        val memBefore = bridge.peakMemoryBytes()
        val loadStart = NSDate()
        var loadError: String? = null
        val loadInfo = bridge.loadEngine(modelDir) { loadError = it }
        val loadMs = (-loadStart.timeIntervalSinceNow() * 1000).toLong()
        val memAfterLoad = bridge.peakMemoryBytes()
        if (loadInfo == null) {
            lines += "load FAILED after ${loadMs}ms: $loadError"
            lastReport = lines.joinToString("\n")
            return lastReport
        }
        lines += "load: $loadInfo in ${loadMs}ms"

        val coldStart = NSDate()
        var coldError: String? = null
        val coldStats = bridge.synthesize(
            text = text,
            speakerId = 0,
            speed = 1.0f,
            outputPath = coldOut,
        ) { coldError = it }
        val coldMs = (-coldStart.timeIntervalSinceNow() * 1000).toLong()
        val memAfterCold = bridge.peakMemoryBytes()
        if (coldStats == null) {
            lines += "cold FAILED after ${coldMs}ms: $coldError"
            lastReport = lines.joinToString("\n")
            return lastReport
        }
        lines += "cold: ${coldMs}ms $coldStats"
        lines += "coldWav=$coldOut bytes=${fileSize(coldOut)}"

        val warmText = "It had learned the story by heart, but it liked the sound of the words."
        val warmStart = NSDate()
        var warmError: String? = null
        val warmStats = bridge.synthesize(
            text = warmText,
            speakerId = 0,
            speed = 1.0f,
            outputPath = warmOut,
        ) { warmError = it }
        val warmMs = (-warmStart.timeIntervalSinceNow() * 1000).toLong()
        val memAfterWarm = bridge.peakMemoryBytes()
        if (warmStats == null) {
            lines += "warm FAILED after ${warmMs}ms: $warmError"
            lastReport = lines.joinToString("\n")
            return lastReport
        }
        lines += "warm: ${warmMs}ms $warmStats"
        lines += "warmWav=$warmOut bytes=${fileSize(warmOut)}"

        lines += "peakMem: before=${memBefore}B afterLoad=${memAfterLoad}B " +
            "afterCold=${memAfterCold}B afterWarm=${memAfterWarm}B"

        lines += "--- pack download (lib/packs PackDownloader, ktor-darwin) ---"
        lines += probePackDownload(outDir)

        lines += "--- queue probe ---"
        lines += withContext(Dispatchers.Main) {
            bridge.probeQueuePlayback(listOf(coldOut, warmOut))
        }

        lines += "--- pitch ---"
        lines += bridge.pitchCapabilities()

        lastReport = lines.joinToString("\n")
        return lastReport
    }

    /**
     * Proves the multiplatform lib/packs path end to end from Kotlin on iOS:
     * downloads one real pack file (tokens.txt, 1078 bytes) with ktor-darwin, then
     * sha256-verifies it with okio HashingSource — the same code Android uses.
     */
    private suspend fun probePackDownload(outDir: String): String {
        return runCatching {
            val file = com.retro99.packs.PackFile(
                path = "tokens.txt",
                url = "https://github.com/RetRo99/tts-models/releases/download/" +
                    "models-20260928123911-4/kokoro-tokens.txt",
                size = 1078L,
                sha256 = "4f31c71282d14af4e926cd12462078fe9d20d00c589e63fe2750a8f56d6d7f7b",
            )
            val client = io.ktor.client.HttpClient(io.ktor.client.engine.darwin.Darwin)
            val downloader = com.retro99.packs.PackDownloader(client)
            val target = "$outDir/spike-pack-tokens.txt".toPath()
            okio.FileSystem.SYSTEM.delete(target, mustExist = false)
            val start = NSDate()
            downloader.download(file, target)
            val ms = (-start.timeIntervalSinceNow() * 1000).toLong()
            val verified = downloader.verify(file, target)
            client.close()
            "download: ${ms}ms verified=$verified " +
                "bytes=${okio.FileSystem.SYSTEM.metadataOrNull(target)?.size}"
        }.getOrElse { "download FAILED: ${it::class.simpleName}: ${it.message}" }
    }

    private fun documentsPath(): String {
        val urls = NSFileManager.defaultManager.URLsForDirectory(
            NSDocumentDirectory,
            NSUserDomainMask,
        )
        return urls.first().let { (it as platform.Foundation.NSURL).path!! }
    }

    private fun fileSize(path: String): Long {
        val attrs = NSFileManager.defaultManager.attributesOfItemAtPath(path, null)
        return (attrs?.get(platform.Foundation.NSFileSize) as? Number)?.toLong() ?: -1L
    }
}
