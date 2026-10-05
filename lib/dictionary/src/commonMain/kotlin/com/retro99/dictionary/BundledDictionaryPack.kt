package com.retro99.dictionary

import com.retro99.dictionary.resources.Res
import com.retro99.packs.PackFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.withContext
import okio.ByteString.Companion.toByteString
import okio.FileSystem
import okio.Path

/** SQLite needs a real file. Materialise the app resource atomically, without HTTP. */
internal class BundledDictionaryPack(
    private val root: Path,
    private val availableBytes: () -> Long,
    private val file: PackFile = ENGLISH_PACK.files.single(),
    private val readBytes: suspend () -> ByteArray = { Res.readBytes("files/english.sqlite") },
    private val fs: FileSystem = FileSystem.SYSTEM,
) {
    suspend fun file(): Path = withContext(Dispatchers.IO) {
        // Separate from downloaded versions, so removing an update cannot remove the base.
        val directory = root / "bundled-dictionary" / ENGLISH_PACK.version
        val destination = directory / file.path
        if (fs.metadataOrNull(destination)?.size == file.size) return@withContext destination
        check(availableBytes() >= file.size + 4 * 1024 * 1024) {
            "Not enough storage. Free some space to open the included dictionary."
        }
        val bytes = readBytes()
        check(bytes.size.toLong() == file.size && bytes.toByteString().sha256().hex() == file.sha256) {
            "The included dictionary is invalid. Reinstall the app."
        }
        fs.createDirectories(directory)
        val temporary = directory / "${file.path}.tmp"
        try {
            fs.write(temporary) { write(bytes) }
            fs.atomicMove(temporary, destination)
        } finally {
            fs.delete(temporary, mustExist = false)
        }
        destination
    }
}
