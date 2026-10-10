package com.retro99.reader.ui.tts

import java.io.File
import java.nio.file.Files
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class TtsPreparedChapterArchiveTest {
    private val root = Files.createTempDirectory("prepared-archive").toFile()
    private val store = TtsPreparedStore(File(root, "store"), { 1_000_000L })
    private val archive = TtsPreparedChapterArchive()
    private val id = PreparedChapterId("book-1", "local", "chapter-1.xhtml")
    private val settings = PreparedVoiceSettings("system", null, 1f, 1f)
    private val keys = TtsAudioCacheStore(root).let { cache ->
        listOf("One sentence.", "Another sentence.").map { cache.key("system", null, 1f, 1f, it) }
    }
    private val out = File(root, "out").apply { mkdirs() }

    @AfterTest fun cleanup() { root.deleteRecursively() }

    private fun audio(size: Int, fill: Byte = 7) =
        File(root, "audio-${size}-$fill.m4a").apply { writeBytes(ByteArray(size) { fill }) }

    /** A complete, valid chapter on disk, exactly as the preparation job leaves it. */
    private fun prepareComplete(): File {
        store.begin(id, settings, keys)
        store.add(id, keys[0], audio(1_200, 1), 1_500)
        store.add(id, keys[1], audio(1_800, 2), 2_000)
        store.markComplete(id)
        return store.chapterDirectory(id)
    }

    private fun packed(): PreparedArchiveResult.Packed {
        val result = archive.pack(prepareComplete(), File(out, "chapter.zip"))
        return assertIs<PreparedArchiveResult.Packed>(result)
    }

    // -----------------------------------------------------------------------
    // The name
    // -----------------------------------------------------------------------

    @Test fun `the relative path is two hashes and carries no title or href`() {
        val path = archive.relativePath("Chapter One: The Beginning.xhtml", settings)
        assertTrue(
            path.matches(Regex("tts-prepared/[0-9a-f]{64}/[0-9a-f]{64}\\.zip")),
            "server constraint requires two hashes, got $path",
        )
        assertFalse(path.contains("Chapter"), "the href must not appear in the path")
        assertFalse(path.contains("Beginning"))
    }

    @Test fun `the same chapter with other settings is a different path`() {
        val slower = settings.copy(rate = 1.5f)
        assertEquals(
            archive.relativePath("chapter-1.xhtml", settings).substringBeforeLast('/'),
            archive.relativePath("chapter-1.xhtml", slower).substringBeforeLast('/'),
            "the chapter part is the chapter href only",
        )
        assertFalse(
            archive.relativePath("chapter-1.xhtml", settings) ==
                archive.relativePath("chapter-1.xhtml", slower),
            "other settings must be a different file",
        )
    }

    // -----------------------------------------------------------------------
    // Packing
    // -----------------------------------------------------------------------

    @Test fun `a complete chapter packs the manifest and one file per sentence`() {
        val result = packed()
        assertTrue(result.file.isFile && result.sizeBytes > 0)
        assertEquals(result.file.length(), result.sizeBytes)
        assertEquals(setOf("manifest.json", "${keys[0]}.m4a", "${keys[1]}.m4a"), entryNames(result.file))
    }

    @Test fun `the audio is stored, never recompressed`() {
        val result = packed()
        val sizes = entrySizes(result.file)
        assertEquals(1_200L, sizes["${keys[0]}.m4a"])
        assertEquals(1_800L, sizes["${keys[1]}.m4a"])
        assertTrue(
            result.sizeBytes > 3_000,
            "a stored archive is at least the audio payload, got ${result.sizeBytes}",
        )
    }

    @Test fun `an incomplete chapter is never packed`() {
        store.begin(id, settings, keys)
        store.add(id, keys[0], audio(1_200, 1), 1_500)
        val result = archive.pack(store.chapterDirectory(id), File(out, "partial.zip"))
        assertEquals(
            PreparedArchiveResult.Rejected(PreparedArchiveRejection.CHAPTER_INCOMPLETE),
            result,
        )
        assertFalse(File(out, "partial.zip").exists(), "nothing is written for a partial chapter")
    }

    @Test fun `packing never includes staging or stray files`() {
        val folder = prepareComplete()
        File(folder, "audio-99.part").writeBytes(ByteArray(10))
        File(folder, "notes.txt").writeBytes(ByteArray(10))
        val result = assertIs<PreparedArchiveResult.Packed>(
            archive.pack(folder, File(out, "clean.zip")),
        )
        assertEquals(setOf("manifest.json", "${keys[0]}.m4a", "${keys[1]}.m4a"), entryNames(result.file))
    }

    @Test fun `a duplicate key shares one audio file in the archive`() {
        store.begin(id, settings, listOf(keys[0], keys[1], keys[0]))
        store.add(id, keys[0], audio(1_200, 1), 1_500)
        store.add(id, keys[1], audio(1_800, 2), 2_000)
        store.markComplete(id)
        val result = assertIs<PreparedArchiveResult.Packed>(
            archive.pack(store.chapterDirectory(id), File(out, "dup.zip")),
        )
        assertEquals(setOf("manifest.json", "${keys[0]}.m4a", "${keys[1]}.m4a"), entryNames(result.file))
    }

    // -----------------------------------------------------------------------
    // The round trip
    // -----------------------------------------------------------------------

    @Test fun `a packed chapter unpacks into a playable chapter`() {
        val result = packed()
        val target = File(root, "installed/chapter")
        val installed = assertIs<PreparedArchiveResult.Installed>(
            archive.unpack(result.file, id, target),
        )
        assertEquals(keys, installed.manifest.sentences.map { it.key })
        assertTrue(installed.manifest.complete)
        assertEquals(1_200L, File(target, "${keys[0]}.m4a").length())
        assertEquals(1_800L, File(target, "${keys[1]}.m4a").length())
        assertEquals(
            listOf(1_500L, 2_000L),
            installed.manifest.sentences.map { it.durationMs },
            "durations survive, so the chapter timeline is right",
        )
    }

    @Test fun `an unpacked chapter is read back by the store it was installed into`() {
        val result = packed()
        val second = TtsPreparedStore(File(root, "store-2"), { 2_000_000L })
        val target = second.chapterDirectory(id)
        assertIs<PreparedArchiveResult.Installed>(archive.unpack(result.file, id, target))
        assertEquals(PreparedChapterState.Ready(3_000), second.state(id, settings))
        val audio = second.lookup(id, keys[0])
        assertEquals(1_500L, audio?.durationMs)
        assertEquals(1_200L, audio?.file?.length())
    }

    // -----------------------------------------------------------------------
    // Every check on unpack
    // -----------------------------------------------------------------------

    @Test fun `an entry that escapes the target folder is refused`() {
        val file = zip(
            "manifest.json" to manifestBytes(),
            "../escaped.m4a" to ByteArray(1_200) { 1 },
        )
        assertRejected(PreparedArchiveRejection.UNSAFE_ENTRY, file)
    }

    @Test fun `an absolute or nested entry name is refused`() {
        assertRejected(
            PreparedArchiveRejection.UNSAFE_ENTRY,
            zip("manifest.json" to manifestBytes(), "/etc/passwd" to ByteArray(4)),
        )
        assertRejected(
            PreparedArchiveRejection.UNSAFE_ENTRY,
            zip("manifest.json" to manifestBytes(), "nested/${keys[0]}.m4a" to ByteArray(4)),
        )
    }

    @Test fun `a file the manifest does not list is refused`() {
        val extra = TtsAudioCacheStore(root).key("system", null, 1f, 1f, "Not in this chapter.")
        val file = zip(
            "manifest.json" to manifestBytes(),
            "${keys[0]}.m4a" to ByteArray(1_200) { 1 },
            "${keys[1]}.m4a" to ByteArray(1_800) { 2 },
            "$extra.m4a" to ByteArray(10),
        )
        assertRejected(PreparedArchiveRejection.UNLISTED_ENTRY, file)
    }

    @Test fun `a file whose size does not match the manifest is refused`() {
        val file = zip(
            "manifest.json" to manifestBytes(),
            "${keys[0]}.m4a" to ByteArray(1_199) { 1 },
            "${keys[1]}.m4a" to ByteArray(1_800) { 2 },
        )
        assertRejected(PreparedArchiveRejection.SIZE_MISMATCH, file)
    }

    @Test fun `a chapter missing one of its sentences is refused`() {
        val file = zip(
            "manifest.json" to manifestBytes(),
            "${keys[0]}.m4a" to ByteArray(1_200) { 1 },
        )
        assertRejected(PreparedArchiveRejection.MISSING_ENTRY, file)
    }

    @Test fun `an archive over the size bound is refused`() {
        val small = TtsPreparedChapterArchive(maxBytes = 2_000)
        val result = small.unpack(packed().file, id, File(root, "bounded/chapter"))
        assertEquals(PreparedArchiveResult.Rejected(PreparedArchiveRejection.TOO_LARGE), result)
        assertFalse(File(root, "bounded/chapter").exists())
    }

    @Test fun `an unknown manifest version is refused`() {
        val file = zip("manifest.json" to manifestBytes(version = 3))
        assertRejected(PreparedArchiveRejection.UNSUPPORTED_VERSION, file)
    }

    @Test fun `a manifest that is not the first entry is refused`() {
        val file = zip(
            "${keys[0]}.m4a" to ByteArray(1_200) { 1 },
            "manifest.json" to manifestBytes(),
        )
        assertRejected(PreparedArchiveRejection.MANIFEST_NOT_FIRST, file)
    }

    @Test fun `a manifest for another book or chapter is refused`() {
        val file = zip("manifest.json" to manifestBytes(bookId = "another-book"))
        assertRejected(PreparedArchiveRejection.IDENTITY_MISMATCH, file)
        val other = zip("manifest.json" to manifestBytes(chapterHref = "chapter-9.xhtml"))
        assertRejected(PreparedArchiveRejection.IDENTITY_MISMATCH, other)
    }

    @Test fun `the same entry twice is refused`() {
        // ZipOutputStream refuses to write a duplicate name, but a real archive
        // can carry one: ZipInputStream reads local headers in order and never
        // consults the central directory. So this one is written by hand.
        val file = rawZip(
            "manifest.json" to manifestBytes(),
            "${keys[0]}.m4a" to ByteArray(1_200) { 1 },
            "${keys[0]}.m4a" to ByteArray(1_200) { 1 },
        )
        assertRejected(PreparedArchiveRejection.DUPLICATE_ENTRY, file)
    }

    @Test fun `a truncated archive leaves nothing behind`() {
        val full = packed().file.readBytes()
        val truncated = File(out, "truncated.zip").apply { writeBytes(full.copyOf(full.size / 2)) }
        val target = File(root, "truncated-target/chapter")
        val result = archive.unpack(truncated, id, target)
        assertIs<PreparedArchiveResult.Rejected>(result)
        assertFalse(target.exists(), "a truncated archive installs nothing")
        assertTrue(leftovers().isEmpty(), "and leaves no staging folder: ${leftovers()}")
    }

    @Test fun `a file that is not a zip at all leaves nothing behind`() {
        val junk = File(out, "junk.zip").apply { writeBytes(ByteArray(5_000) { 9 }) }
        val target = File(root, "junk-target/chapter")
        assertIs<PreparedArchiveResult.Rejected>(archive.unpack(junk, id, target))
        assertFalse(target.exists())
        assertTrue(leftovers().isEmpty(), "and leaves no staging folder: ${leftovers()}")
    }

    @Test fun `a rejected archive does not disturb audio already installed`() {
        val good = packed()
        val target = File(root, "keep/chapter")
        assertIs<PreparedArchiveResult.Installed>(archive.unpack(good.file, id, target))
        val before = File(target, "${keys[0]}.m4a").readBytes()

        val bad = zip(
            "manifest.json" to manifestBytes(),
            "${keys[0]}.m4a" to ByteArray(1_199) { 1 },
        )
        assertRejected(PreparedArchiveRejection.SIZE_MISMATCH, bad, target)
        assertTrue(before.contentEquals(File(target, "${keys[0]}.m4a").readBytes()))
        assertEquals(1_800L, File(target, "${keys[1]}.m4a").length())
    }

    // -----------------------------------------------------------------------
    // Helpers
    // -----------------------------------------------------------------------

    private fun assertRejected(
        reason: PreparedArchiveRejection,
        file: File,
        target: File = File(root, "rejected-${file.name}/chapter"),
    ) {
        val existedBefore = target.exists()
        assertEquals(PreparedArchiveResult.Rejected(reason), archive.unpack(file, id, target))
        if (!existedBefore) assertFalse(target.exists(), "a rejected archive installs nothing")
        assertTrue(leftovers().isEmpty(), "and leaves no staging folder: ${leftovers()}")
    }

    /** Any `.part`/staging directory the unpack forgot to remove. */
    private fun leftovers(): List<String> = root.walkTopDown()
        .filter { it.name.contains(".part") || it.name.startsWith("unpack-") }
        .map { it.toString() }
        .toList()

    private fun manifestBytes(
        version: Int = PREPARED_MANIFEST_VERSION,
        bookId: String = id.bookId,
        chapterHref: String = id.chapterHref,
    ): ByteArray {
        val sentences = listOf(
            """{"key":"${keys[0]}","durationMs":1500,"bytes":1200}""",
            """{"key":"${keys[1]}","durationMs":2000,"bytes":1800}""",
        ).joinToString(",")
        return (
            """{"formatVersion":$version,"bookId":"$bookId","serverId":"${id.serverId}",""" +
                """"chapterHref":"$chapterHref","voiceId":"system","modelVersion":null,""" +
                """"rate":1.0,"pitch":1.0,"createdTimeMs":1000000,"sentences":[$sentences],""" +
                """"complete":true,"totalBytes":3000,"lastUsedTimeMs":null}"""
            ).toByteArray()
    }

    /** Writes a stored zip with exactly the entries given, in order, duplicates included. */
    private fun zip(vararg entries: Pair<String, ByteArray>): File {
        val file = File(out, "crafted-${entries.hashCode()}.zip")
        ZipOutputStream(file.outputStream()).use { stream ->
            stream.setMethod(ZipOutputStream.STORED)
            entries.forEach { (name, bytes) ->
                val entry = ZipEntry(name).apply {
                    method = ZipEntry.STORED
                    size = bytes.size.toLong()
                    compressedSize = bytes.size.toLong()
                    crc = CRC32().apply { update(bytes) }.value
                }
                stream.putNextEntry(entry)
                stream.write(bytes)
                stream.closeEntry()
            }
        }
        return file
    }

    /**
     * A stream of stored local file headers and their data, nothing else. That
     * is all [java.util.zip.ZipInputStream] reads, and writing it by hand lets
     * a test express shapes `ZipOutputStream` will not produce.
     */
    private fun rawZip(vararg entries: Pair<String, ByteArray>): File {
        val file = File(out, "raw-${entries.map { it.first }.hashCode()}.zip")
        file.outputStream().buffered().use { sink ->
            fun short(value: Int) { sink.write(value and 0xff); sink.write((value ushr 8) and 0xff) }
            fun int(value: Long) {
                repeat(4) { index -> sink.write(((value ushr (8 * index)) and 0xff).toInt()) }
            }
            entries.forEach { (name, bytes) ->
                val nameBytes = name.toByteArray()
                int(0x04034b50)                                       // local file header
                short(20)                                             // version needed
                short(0)                                              // flags
                short(0)                                              // method: stored
                short(0); short(0)                                    // mod time, mod date
                int(CRC32().apply { update(bytes) }.value)
                int(bytes.size.toLong())                              // compressed size
                int(bytes.size.toLong())                              // uncompressed size
                short(nameBytes.size)
                short(0)                                              // extra length
                sink.write(nameBytes)
                sink.write(bytes)
            }
        }
        return file
    }

    private fun entryNames(file: File): Set<String> = buildSet {
        java.util.zip.ZipInputStream(file.inputStream()).use { stream ->
            while (true) add((stream.nextEntry ?: break).name)
        }
    }

    private fun entrySizes(file: File): Map<String, Long> = buildMap {
        java.util.zip.ZipInputStream(file.inputStream()).use { stream ->
            while (true) {
                val entry = stream.nextEntry ?: break
                put(entry.name, stream.readBytes().size.toLong())
            }
        }
    }
}
