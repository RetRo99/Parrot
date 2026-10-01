package com.retro99.epub.implementation

import com.retro99.analytics.api.Analytics
import com.retro99.analytics.api.AnalyticsEvent
import com.retro99.epub.implementation.zip.RandomAccessSource
import java.io.File
import java.io.RandomAccessFile
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** Zips the fixture folders in test resources into EPUB files, the way a real EPUB is laid out. */
object FixtureEpubs {

    private val plainFiles = listOf(
        "META-INF/container.xml",
        "OEBPS/content.opf",
        "OEBPS/nav.xhtml",
        "OEBPS/chapter 1.xhtml",
        "OEBPS/chapter2.xhtml",
        "OEBPS/chapter3.xhtml",
    )

    private val readaloudFiles = listOf(
        "META-INF/container.xml",
        "OEBPS/content.opf",
        "OEBPS/text/r1.xhtml",
        "OEBPS/text/r2.xhtml",
        "OEBPS/text/r3.xhtml",
        "OEBPS/text/r4.xhtml",
        "OEBPS/smil/r1.smil",
        "OEBPS/smil/r2.smil",
        "OEBPS/smil/r3.smil",
        "OEBPS/smil/r4.smil",
    )

    const val AUDIO_ENTRY = "OEBPS/audio/part1.mp3"

    fun plain(directory: File): File = zip(directory, "plain", plainFiles, audioBytes = 0)

    /**
     * The read-aloud fixture. Its first audio entry is [audioBytes] long (stored, like real
     * read-aloud audio); the second one is empty, because the timing comes from SMIL.
     */
    fun readaloud(directory: File, audioBytes: Int = 0): File =
        zip(directory, "readaloud", readaloudFiles, audioBytes)

    private fun zip(directory: File, name: String, files: List<String>, audioBytes: Int): File {
        val target = File(directory, "$name.epub")
        ZipOutputStream(target.outputStream()).use { output ->
            // The mimetype entry comes first and is stored, as the EPUB spec asks.
            output.putStored("mimetype", "application/epub+zip".toByteArray())
            files.forEach { path ->
                output.putNextEntry(ZipEntry(path))
                output.write(resource("fixtures/$name/$path"))
                output.closeEntry()
            }
            if (name == "readaloud") {
                output.putStored(AUDIO_ENTRY, ByteArray(audioBytes))
                output.putStored("OEBPS/audio/part2.mp3", ByteArray(0))
            }
        }
        return target
    }

    private fun ZipOutputStream.putStored(path: String, bytes: ByteArray) {
        val entry = ZipEntry(path)
        entry.method = ZipEntry.STORED
        entry.size = bytes.size.toLong()
        entry.compressedSize = bytes.size.toLong()
        entry.crc = CRC32().apply { update(bytes) }.value
        putNextEntry(entry)
        write(bytes)
        closeEntry()
    }

    private fun resource(path: String): ByteArray {
        val stream = requireNotNull(javaClass.classLoader.getResourceAsStream(path)) {
            "missing fixture $path"
        }
        return stream.use { input -> input.readBytes() }
    }
}

/** Records every byte range read, so tests can prove which parts of a file were touched. */
class RecordingSource(path: String) : RandomAccessSource {
    private val file = RandomAccessFile(path, "r")
    val reads = mutableListOf<LongRange>()

    override val size: Long = file.length()

    override fun readFully(position: Long, length: Int): ByteArray {
        if (length > 0) reads += position until position + length
        val bytes = ByteArray(length)
        file.seek(position)
        file.readFully(bytes)
        return bytes
    }

    override fun close() = file.close()
}

object SilentAnalytics : Analytics {
    override fun logException(throwable: Throwable, message: String?) = Unit

    override fun logEvent(event: AnalyticsEvent) = Unit

    override fun setUserId(userId: String?) = Unit
}
