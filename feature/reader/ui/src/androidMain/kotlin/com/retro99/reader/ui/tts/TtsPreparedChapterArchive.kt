package com.retro99.reader.ui.tts

import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import kotlinx.serialization.json.Json

/** What a pack or an unpack produced. */
internal sealed interface PreparedArchiveResult {
    data class Packed(val file: File, val sizeBytes: Long, val relativePath: String) : PreparedArchiveResult
    data class Installed(val folder: File, val manifest: PreparedChapterManifest) : PreparedArchiveResult
    data class Rejected(val reason: PreparedArchiveRejection) : PreparedArchiveResult
}

/** Every way an archive can be refused. A rejected archive is never installed. */
internal enum class PreparedArchiveRejection {
    CHAPTER_INCOMPLETE,
    UNREADABLE,
    MANIFEST_NOT_FIRST,
    UNSUPPORTED_VERSION,
    UNSAFE_ENTRY,
    UNLISTED_ENTRY,
    DUPLICATE_ENTRY,
    MISSING_ENTRY,
    SIZE_MISMATCH,
    TOO_LARGE,
    IDENTITY_MISMATCH,
}

private const val MANIFEST_NAME = "manifest.json"
private val AUDIO_NAME = Regex("[0-9a-f]{64}\\.$PREPARED_AUDIO_EXTENSION")

/**
 * One prepared chapter folder as one file, and back again.
 *
 * The audio is already AAC, so the archive is a *stored* zip: nothing is
 * recompressed and the payload is byte-identical through the round trip. The
 * name carries hashes only -- no title, no href, no text -- and matches the
 * `tts-prepared/<64 hex>/<64 hex>.zip` shape the server constrains
 * `cloud_book_files.relative_path` to.
 *
 * Unpacking trusts nothing. The manifest has to come first, so what is allowed
 * is known before a single byte is written; every later entry has to be a plain
 * `<sentence key>.m4a` name the manifest lists, exactly once, at exactly the
 * length the manifest claims, inside a total the caller bounds. Everything
 * lands in a staging folder beside the target and is moved into place only
 * after all of that passed, so a rejected or truncated archive installs nothing
 * and disturbs nothing already installed.
 */
internal class TtsPreparedChapterArchive(
    private val maxBytes: Long = 64L * 1024 * 1024,
) {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    /** Hashes only: the chapter href, then the settings that decide the audio. */
    fun relativePath(chapterHref: String, settings: PreparedVoiceSettings): String =
        "tts-prepared/${hash(chapterHref)}/${hash(settingsKey(settings))}.zip"

    fun pack(chapterFolder: File, destination: File): PreparedArchiveResult {
        val manifest = readManifest(File(chapterFolder, MANIFEST_NAME))
            ?: return rejected(PreparedArchiveRejection.UNREADABLE)
        if (manifest.formatVersion != PREPARED_MANIFEST_VERSION) {
            return rejected(PreparedArchiveRejection.UNSUPPORTED_VERSION)
        }
        // A chapter that is still being prepared is never packed or uploaded.
        if (!manifest.complete || manifest.sentences.isEmpty() ||
            manifest.sentences.any { it.durationMs == null }
        ) {
            return rejected(PreparedArchiveRejection.CHAPTER_INCOMPLETE)
        }

        // Duplicate keys share one audio file; ordered positions stay in the manifest.
        val payload = manifest.sentences.distinctBy { it.key }
        for (entry in payload) {
            val audio = File(chapterFolder, "${entry.key}.$PREPARED_AUDIO_EXTENSION")
            if (Files.isSymbolicLink(audio.toPath()) || !audio.isFile || audio.length() != entry.bytes) {
                return rejected(PreparedArchiveRejection.UNREADABLE)
            }
        }

        destination.parentFile?.mkdirs()
        val staging = File(destination.parentFile, "${destination.name}.part-${token()}")
        return try {
            ZipOutputStream(staging.outputStream().buffered()).use { stream ->
                stream.setMethod(ZipOutputStream.STORED)
                // First, so an unpack knows what it is allowed to accept.
                store(stream, MANIFEST_NAME, json.encodeToString(manifest).toByteArray())
                for (entry in payload) {
                    val audio = File(chapterFolder, "${entry.key}.$PREPARED_AUDIO_EXTENSION")
                    store(stream, "${entry.key}.$PREPARED_AUDIO_EXTENSION", audio.readBytes())
                }
            }
            Files.move(
                staging.toPath(),
                destination.toPath(),
                StandardCopyOption.ATOMIC_MOVE,
                StandardCopyOption.REPLACE_EXISTING,
            )
            PreparedArchiveResult.Packed(
                file = destination,
                sizeBytes = destination.length(),
                relativePath = relativePath(manifest.chapterHref, manifest.settings()),
            )
        } catch (_: Exception) {
            rejected(PreparedArchiveRejection.UNREADABLE)
        } finally {
            staging.delete()
        }
    }

    fun unpack(
        archive: File,
        expected: PreparedChapterId,
        destination: File,
    ): PreparedArchiveResult {
        if (!archive.isFile) return rejected(PreparedArchiveRejection.UNREADABLE)
        // The archive is the uploaded file, so its own length is the bound.
        if (archive.length() > maxBytes) return rejected(PreparedArchiveRejection.TOO_LARGE)

        val parent = destination.parentFile
        parent?.mkdirs()
        val staging = File(parent, "unpack-${token()}")
        if (!staging.mkdirs()) return rejected(PreparedArchiveRejection.UNREADABLE)
        try {
            val manifest = when (val read = extractInto(archive, expected, staging)) {
                is PreparedArchiveResult.Rejected -> return read
                else -> (read as PreparedArchiveResult.Installed).manifest
            }
            // Only now does anything already installed get touched.
            if (destination.exists()) remove(destination)
            Files.move(staging.toPath(), destination.toPath(), StandardCopyOption.ATOMIC_MOVE)
            return PreparedArchiveResult.Installed(destination, manifest)
        } catch (_: Exception) {
            return rejected(PreparedArchiveRejection.UNREADABLE)
        } finally {
            if (staging.exists()) runCatching { remove(staging) }
        }
    }

    /** Writes the verified entries into [staging]; returns the manifest or why it was refused. */
    private fun extractInto(
        archive: File,
        expected: PreparedChapterId,
        staging: File,
    ): PreparedArchiveResult {
        var manifest: PreparedChapterManifest? = null
        var expectedAudio: Map<String, Long> = emptyMap()
        val seen = mutableSetOf<String>()
        var total = 0L

        ZipInputStream(archive.inputStream().buffered()).use { stream ->
            while (true) {
                val entry = stream.nextEntry ?: break
                val name = entry.name
                if (manifest == null) {
                    if (name != MANIFEST_NAME) {
                        return rejected(PreparedArchiveRejection.MANIFEST_NOT_FIRST)
                    }
                    val bytes = stream.readBytes()
                    total += bytes.size
                    if (total > maxBytes) return rejected(PreparedArchiveRejection.TOO_LARGE)
                    val parsed = runCatching { json.decodeFromString<PreparedChapterManifest>(String(bytes)) }
                        .getOrNull()
                        ?: return rejected(PreparedArchiveRejection.UNREADABLE)
                    if (parsed.formatVersion != PREPARED_MANIFEST_VERSION) {
                        return rejected(PreparedArchiveRejection.UNSUPPORTED_VERSION)
                    }
                    if (parsed.bookId != expected.bookId ||
                        parsed.serverId.orEmpty() != expected.serverId.orEmpty() ||
                        parsed.chapterHref != expected.chapterHref
                    ) {
                        return rejected(PreparedArchiveRejection.IDENTITY_MISMATCH)
                    }
                    if (!parsed.complete || parsed.sentences.isEmpty() ||
                        parsed.sentences.any { it.durationMs == null }
                    ) {
                        return rejected(PreparedArchiveRejection.CHAPTER_INCOMPLETE)
                    }
                    if (parsed.sentences.any {
                            !it.key.matches(Regex("[a-f0-9]{64}")) || it.bytes <= 0 ||
                                (it.durationMs ?: 0) <= 0
                        }
                    ) {
                        return rejected(PreparedArchiveRejection.UNREADABLE)
                    }
                    expectedAudio = parsed.sentences.distinctBy { it.key }
                        .associate { "${it.key}.$PREPARED_AUDIO_EXTENSION" to it.bytes }
                    File(staging, MANIFEST_NAME).writeBytes(bytes)
                    manifest = parsed
                    continue
                }

                // A plain, hashed file name and nothing else: that alone makes
                // escaping the target folder impossible, path traversal included.
                if (name.contains('/') || name.contains('\\') || !name.matches(AUDIO_NAME)) {
                    return rejected(PreparedArchiveRejection.UNSAFE_ENTRY)
                }
                val size = expectedAudio[name]
                    ?: return rejected(PreparedArchiveRejection.UNLISTED_ENTRY)
                if (!seen.add(name)) return rejected(PreparedArchiveRejection.DUPLICATE_ENTRY)
                if (total + size > maxBytes) return rejected(PreparedArchiveRejection.TOO_LARGE)

                val target = File(staging, name)
                var written = 0L
                target.outputStream().buffered().use { sink ->
                    val buffer = ByteArray(64 * 1024)
                    while (true) {
                        val read = stream.read(buffer)
                        if (read < 0) break
                        written += read
                        // Bounded while reading too, so a lying header cannot fill the disk.
                        if (written > size || total + written > maxBytes) return@use
                        sink.write(buffer, 0, read)
                    }
                }
                if (written > size || total + written > maxBytes) {
                    return rejected(
                        if (written > size) PreparedArchiveRejection.SIZE_MISMATCH
                        else PreparedArchiveRejection.TOO_LARGE,
                    )
                }
                if (written != size) return rejected(PreparedArchiveRejection.SIZE_MISMATCH)
                total += written
            }
        }

        val complete = manifest ?: return rejected(PreparedArchiveRejection.UNREADABLE)
        if (seen != expectedAudio.keys) return rejected(PreparedArchiveRejection.MISSING_ENTRY)
        return PreparedArchiveResult.Installed(staging, complete)
    }

    private fun readManifest(file: File): PreparedChapterManifest? = runCatching {
        require(!Files.isSymbolicLink(file.toPath()) && file.isFile && file.length() in 1..8_388_608)
        json.decodeFromString<PreparedChapterManifest>(file.readText())
    }.getOrNull()

    private fun store(stream: ZipOutputStream, name: String, bytes: ByteArray) {
        stream.putNextEntry(
            ZipEntry(name).apply {
                method = ZipEntry.STORED
                size = bytes.size.toLong()
                compressedSize = bytes.size.toLong()
                crc = CRC32().apply { update(bytes) }.value
            },
        )
        stream.write(bytes)
        stream.closeEntry()
    }

    private fun remove(file: File) {
        if (Files.isSymbolicLink(file.toPath())) {
            file.delete()
            return
        }
        if (file.isDirectory) file.listFiles()?.forEach(::remove)
        file.delete()
    }

    private fun rejected(reason: PreparedArchiveRejection) = PreparedArchiveResult.Rejected(reason)

    /** Lengths prefix every part, so no voice id can impersonate a delimiter. */
    private fun settingsKey(settings: PreparedVoiceSettings): String {
        val voice = settings.voiceId.orEmpty()
        val model = settings.modelVersion.orEmpty()
        return "${voice.length}:$voice${model.length}:$model" +
            "${(settings.rate * 100).toInt()}|${(settings.pitch * 100).toInt()}"
    }

    private fun PreparedChapterManifest.settings() =
        PreparedVoiceSettings(voiceId, modelVersion, rate, pitch)

    private fun token() = java.util.UUID.randomUUID().toString()

    private fun hash(value: String) = MessageDigest.getInstance("SHA-256")
        .digest(value.encodeToByteArray())
        .joinToString("") { "%02x".format(it.toInt() and 0xff) }
}
