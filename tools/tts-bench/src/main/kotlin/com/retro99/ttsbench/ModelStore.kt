package com.retro99.ttsbench

import android.content.Context
import android.util.Log
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.zip.ZipInputStream
import org.json.JSONObject

data class ManifestFile(
    val url: String,
    val path: String,
    val size: Long,
    val sha256: String,
    val extractTo: String?,
)

data class ManifestEntry(
    val id: String,
    val version: String,
    val files: List<ManifestFile>,
)

class SupertonicFiles(dir: File) {
    val durationPredictor = File(dir, "duration_predictor.int8.onnx")
    val textEncoder = File(dir, "text_encoder.int8.onnx")
    val vectorEstimator = File(dir, "vector_estimator.int8.onnx")
    val vocoder = File(dir, "vocoder.int8.onnx")
    val ttsJson = File(dir, "tts.json")
    val unicodeIndexer = File(dir, "unicode_indexer.bin")
    val voiceStyle = File(dir, "voice.bin")
}

class KokoroFiles(dir: File) {
    val model = File(dir, "model.int8.onnx")
    val voices = File(dir, "voices.bin")
    val tokens = File(dir, "tokens.txt")
    val dataDir = File(dir, "espeak-ng-data")
}

/** A small copy of the app's manifest and download logic, kept independent of the app. */
class ModelStore(context: Context, private val log: (String) -> Unit) {

    private val root = File(context.filesDir, "models")

    fun fetchEntry(modelId: String): ManifestEntry {
        val connection = (URL(MANIFEST_URL).openConnection() as HttpURLConnection).apply {
            connectTimeout = TIMEOUT_MS
            readTimeout = TIMEOUT_MS
            instanceFollowRedirects = true
        }
        val body = try {
            if (connection.responseCode !in 200..299) {
                throw IOException("Manifest HTTP ${connection.responseCode}")
            }
            connection.inputStream.bufferedReader().use { reader -> reader.readText() }
        } finally {
            connection.disconnect()
        }
        val models = JSONObject(body).getJSONArray("models")
        for (index in 0 until models.length()) {
            val model = models.getJSONObject(index)
            if (model.getString("id") != modelId) continue
            val filesJson = model.getJSONArray("files")
            val files = (0 until filesJson.length()).map { fileIndex ->
                val file = filesJson.getJSONObject(fileIndex)
                ManifestFile(
                    url = file.getString("url"),
                    path = file.getString("path"),
                    size = file.getLong("size"),
                    sha256 = file.getString("sha256"),
                    extractTo = if (file.isNull("extractTo")) null else file.getString("extractTo"),
                )
            }
            return ManifestEntry(modelId, model.getString("version"), files)
        }
        throw IOException("Model $modelId not in manifest")
    }

    /** Returns the directory holding the model, downloading missing files first. */
    fun ensure(modelId: String): Pair<File, String> {
        val entry = fetchEntry(modelId)
        val dir = File(root, "$modelId/${entry.version}").apply { mkdirs() }
        for (file in entry.files) {
            if (isInstalled(file, dir)) continue
            val startedAt = System.currentTimeMillis()
            log("Downloading ${file.path} (${file.size / BYTES_PER_KB} KB)")
            download(file, dir)
            log("Downloaded ${file.path} in ${System.currentTimeMillis() - startedAt} ms")
        }
        return dir to entry.version
    }

    private fun isInstalled(file: ManifestFile, dir: File): Boolean {
        val extractTo = file.extractTo
        if (extractTo != null) return File(dir, extractTo).list()?.isNotEmpty() == true
        val target = File(dir, file.path)
        return target.isFile && target.length() == file.size
    }

    private fun download(file: ManifestFile, dir: File) {
        val partial = File(dir, "${file.path}.part")
        partial.parentFile?.mkdirs()
        val connection = (URL(file.url).openConnection() as HttpURLConnection).apply {
            connectTimeout = TIMEOUT_MS
            readTimeout = TIMEOUT_MS
            instanceFollowRedirects = true
        }
        try {
            if (connection.responseCode !in 200..299) {
                throw IOException("HTTP ${connection.responseCode} for ${file.url}")
            }
            connection.inputStream.use { input ->
                partial.outputStream().use { output -> input.copyTo(output, BUFFER_SIZE) }
            }
        } finally {
            connection.disconnect()
        }
        val actual = sha256(partial)
        if (!actual.equals(file.sha256, ignoreCase = true)) {
            partial.delete()
            throw IOException("Checksum mismatch for ${file.path}")
        }
        val extractTo = file.extractTo
        if (extractTo != null) {
            unzip(partial, File(dir, extractTo), stripPrefix = extractTo)
            partial.delete()
        } else {
            val target = File(dir, file.path)
            target.delete()
            if (!partial.renameTo(target)) throw IOException("Cannot install ${file.path}")
        }
    }

    private fun unzip(zip: File, destination: File, stripPrefix: String) {
        destination.mkdirs()
        val canonicalRoot = destination.canonicalPath + File.separator
        ZipInputStream(zip.inputStream().buffered()).use { input ->
            while (true) {
                val entry = input.nextEntry ?: break
                val relative = entry.name.removePrefix("$stripPrefix/")
                if (relative.isEmpty()) continue
                val out = File(destination, relative)
                if (!out.canonicalPath.startsWith(canonicalRoot)) {
                    throw IOException("Unsafe zip entry ${entry.name}")
                }
                if (entry.isDirectory) {
                    out.mkdirs()
                } else {
                    out.parentFile?.mkdirs()
                    out.outputStream().use { output -> input.copyTo(output, BUFFER_SIZE) }
                }
            }
        }
        Log.i(TAG, "Extracted ${zip.name} to ${destination.name}")
    }

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(BUFFER_SIZE)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString(separator = "") { byte ->
            "%02x".format(byte.toInt() and 0xFF)
        }
    }

    private companion object {
        const val MANIFEST_URL =
            "https://github.com/RetRo99/tts-models/releases/latest/download/manifest.json"
        const val TAG = "TtsBench"
        const val TIMEOUT_MS = 60_000
        const val BUFFER_SIZE = 64 * 1024
        const val BYTES_PER_KB = 1024
    }
}
