package com.retro99.packs

import io.ktor.client.HttpClient
import io.ktor.client.request.prepareGet
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsChannel
import io.ktor.utils.io.readAvailable
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import okio.FileSystem
import okio.HashingSource
import okio.Path
import okio.buffer

@Serializable
data class PackFile(val path: String, val url: String, val size: Long, val sha256: String)

@Serializable
data class PackManifest(val id: String, val version: String, val files: List<PackFile>) {
    fun validate() {
        require(id.matches(SAFE_COMPONENT) && version.matches(SAFE_COMPONENT))
        require(files.isNotEmpty() && files.map { it.path }.distinct().size == files.size)
        files.forEach {
            require(it.path.matches(SAFE_COMPONENT) && it.size in 1..2_000_000_000L)
            require(it.sha256.matches(Regex("[a-fA-F0-9]{64}")))
            require(it.url.startsWith("https://github.com/RetRo99/tts-models/releases/download/"))
        }
    }
    companion object { private val SAFE_COMPONENT = Regex("[a-zA-Z0-9][a-zA-Z0-9._-]{0,100}") }
}

/** Shared download/verification primitive. Used by voice packs and dictionary packs. */
class PackDownloader(private val client: HttpClient, private val fs: FileSystem = FileSystem.SYSTEM) {
    suspend fun download(file: PackFile, partial: Path, progress: (Long) -> Unit = {}) = withContext(Dispatchers.IO) {
        var offset = fs.metadataOrNull(partial)?.size ?: 0L
        if (offset > file.size) { fs.delete(partial); offset = 0 }
        if (offset == file.size && verify(file, partial)) return@withContext
        if (offset == file.size) { fs.delete(partial); offset = 0 }
        client.prepareGet(file.url) {
            header("Accept-Encoding", "identity")
            if (offset > 0) header("Range", "bytes=$offset-")
        }.execute { response ->
            if (response.status.value == 416) fs.delete(partial, mustExist = false)
            offset = packResumeOffset(response.status.value, response.headers["Content-Range"], offset, file.size)
            val channel = response.bodyAsChannel()
            if (offset == 0L) fs.delete(partial, mustExist = false)
            val sink = fs.appendingSink(partial).buffer()
            try {
                copyPackBytes(file.size, offset,
                    read = { buffer -> channel.readAvailable(buffer, 0, buffer.size) },
                    write = { buffer, count -> sink.write(buffer, 0, count) }, onBytes = progress)
            } finally { sink.close() }
        }
        check(verify(file, partial)) { "Pack verification failed. Try again." }
    }

    suspend fun verify(file: PackFile, path: Path): Boolean = withContext(Dispatchers.IO) {
        if (fs.metadataOrNull(path)?.size != file.size) return@withContext false
        val hashing = HashingSource.sha256(fs.source(path))
        val source = hashing.buffer()
        try { while (!source.exhausted()) {
            currentCoroutineContext().ensureActive()
            source.skip(minOf(64 * 1024L, source.buffer.size))
        } }
        finally { source.close() }
        hashing.hash.hex().equals(file.sha256, ignoreCase = true)
    }
}

/** Side-by-side versions; only a verified install can move the active marker. */
class PackManager(
    private val root: Path,
    private val downloader: PackDownloader,
    private val availableBytes: () -> Long,
    private val fs: FileSystem = FileSystem.SYSTEM,
) {
    fun activeVersion(id: String): String? = runCatching {
        fs.read(root / id / ".active") { readUtf8().trim() }.takeIf { it.matches(Regex("[a-zA-Z0-9][a-zA-Z0-9._-]{0,100}")) }
    }.getOrNull()

    fun activeFile(id: String, path: String): Path? = activeVersion(id)?.let { version ->
        (root / id / version / path).takeIf { fs.exists(it) }
    }

    suspend fun install(manifest: PackManifest, onProgress: (Int) -> Unit = {}, validateInstalled: suspend (Path) -> Unit = {}) = withContext(Dispatchers.IO) {
        manifest.validate()
        val previous = activeVersion(manifest.id)
        val directory = root / manifest.id / manifest.version
        fs.createDirectories(directory)
        val missing = manifest.files.filterNot { downloader.verify(it, directory / it.path) }
        val required = missing.sumOf { it.size }
        val remaining = missing.sumOf { file -> file.size - (fs.metadataOrNull(directory / "${file.path}.part")?.size ?: 0L).coerceIn(0, file.size) }
        check(availableBytes() >= remaining + 16 * 1024 * 1024) { "Not enough storage. Free some space and try again." }
        val total = manifest.files.sumOf { it.size }
        var complete = total - required
        missing.forEach { file ->
            val partial = directory / "${file.path}.part"
            downloader.download(file, partial) { bytes -> onProgress(((complete + bytes) * 100 / total).toInt()) }
            fs.atomicMove(partial, directory / file.path)
            complete += file.size
        }
        validateInstalled(directory)
        val marker = root / manifest.id / ".active"
        fs.write(root / manifest.id / ".active.tmp") { writeUtf8(manifest.version) }
        fs.atomicMove(root / manifest.id / ".active.tmp", marker)
        fs.list(root / manifest.id).filter { fs.metadataOrNull(it)?.isDirectory == true && it.name !in setOf(previous, manifest.version) }
            .forEach { fs.deleteRecursively(it) }
        onProgress(100)
    }

    suspend fun remove(id: String) = withContext(Dispatchers.IO) {
        if (fs.exists(root / id)) fs.deleteRecursively(root / id)
    }
}
