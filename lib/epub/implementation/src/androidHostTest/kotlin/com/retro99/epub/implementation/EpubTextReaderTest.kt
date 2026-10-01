package com.retro99.epub.implementation

import com.github.michaelbull.result.get
import com.github.michaelbull.result.getError
import com.retro99.epub.api.EpubChapterText
import kotlinx.coroutines.test.runTest
import java.io.File
import java.nio.file.Files
import java.util.zip.ZipFile
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class EpubTextReaderTest {

    private val directory: File = Files.createTempDirectory("epub-text").toFile()
    private val reader = EpubTextReaderImpl(SilentAnalytics)

    @AfterTest
    fun tearDown() {
        directory.deleteRecursively()
    }

    @Test
    fun `reads chapters in spine order, leaving out non-linear items`() = runTest {
        // Given
        val epub = FixtureEpubs.plain(directory)

        // When
        val chapters = assertNotNull(reader.readChapters(epub.path).get())

        // Then
        assertEquals(
            listOf("OEBPS/chapter 1.xhtml", "OEBPS/chapter2.xhtml", "OEBPS/chapter3.xhtml"),
            chapters.map { chapter -> chapter.href },
        )
        assertEquals(
            listOf("Chapter 1", "Chapter 2", "Chapter 3"),
            chapters.map { chapter -> chapter.title },
        )
    }

    @Test
    fun `the spine keeps a non-linear cover first, as cfis count it`() = runTest {
        // Given: the fixture's first itemref (the nav document) is linear="no".
        val epub = FixtureEpubs.plain(directory)

        // When
        val spine = assertNotNull(reader.readSpineHrefs(epub.path).get())

        // Then
        assertEquals(
            listOf(
                "OEBPS/nav.xhtml",
                "OEBPS/chapter 1.xhtml",
                "OEBPS/chapter2.xhtml",
                "OEBPS/chapter3.xhtml",
            ),
            spine,
        )
        assertEquals(1, spine.indexOf("OEBPS/chapter 1.xhtml"))
    }

    @Test
    fun `without non-linear items the spine is the reading order`() = runTest {
        // Given
        val epub = FixtureEpubs.readaloud(directory)

        // When
        val spine = assertNotNull(reader.readSpineHrefs(epub.path).get())
        val chapters = assertNotNull(reader.readChapters(epub.path).get())

        // Then
        assertEquals(chapters.map { chapter -> chapter.href }, spine)
    }

    @Test
    fun `chapter text collapses whitespace, decodes entities and breaks blocks`() = runTest {
        // Given
        val epub = FixtureEpubs.plain(directory)

        // When
        val chapters = assertNotNull(reader.readChapters(epub.path).get())

        // Then
        assertEquals(
            "Chapter 2\n" +
                "“My dear Mr. Bennet,” said his lady to him one day, “have " +
                "you heard that Netherfield Park is let at last?”\n" +
                "Mr. Bennet replied that he had not.",
            chapters[1].text,
        )
        assertTrue(chapters[0].text.startsWith("Chapter 1\nIt is a truth universally"))
    }

    @Test
    fun `element offsets point at each element's text`() = runTest {
        // Given
        val epub = FixtureEpubs.plain(directory)

        // When
        val chapters = assertNotNull(reader.readChapters(epub.path).get())

        // Then
        assertEquals("Mr. Bennet replied that he had not.", chapters[1].textOf("p4"))
        assertEquals("Mr. Bennet made no answer.", chapters[2].textOf("p6"))
        assertEquals("Chapter 1", chapters[0].textOf("h1"))
        assertEquals(
            listOf("h1", "p1", "p2"),
            chapters[0].elementOffsets.map { element -> element.elementId },
        )
    }

    @Test
    fun `reads the read-aloud's four chapters with sentence offsets`() = runTest {
        // Given
        val epub = FixtureEpubs.readaloud(directory)

        // When
        val chapters = assertNotNull(reader.readChapters(epub.path).get())

        // Then
        assertEquals(4, chapters.size)
        assertEquals("Part two", chapters[1].title)
        assertEquals(
            "\"have you heard that Netherfield Park is let at last?\"",
            chapters[1].textOf("r2-s3"),
        )
    }

    @Test
    fun `never reads the audio entries of a large read-aloud`() = runTest {
        // Given
        val epub = FixtureEpubs.readaloud(directory, audioBytes = AUDIO_BYTES)
        val audioRange = ZipFile(epub).use { zip ->
            val entry = zip.getEntry(FixtureEpubs.AUDIO_ENTRY)
            // The stored data sits somewhere after the entry's local header.
            assertEquals(AUDIO_BYTES.toLong(), entry.size)
            dataRangeOf(epub, FixtureEpubs.AUDIO_ENTRY)
        }
        val source = RecordingSource(epub.path)
        reader.openSource = { _ -> source }

        // When
        val chapters = reader.readChapters(epub.path).get()

        // Then
        assertEquals(4, chapters?.size)
        assertTrue(source.reads.none { range -> range.overlaps(audioRange) })
        assertTrue(source.reads.sumOf { range -> range.last - range.first + 1 } < 64 * 1024)
    }

    @Test
    fun `a missing file is an error`() = runTest {
        // When
        val error = reader.readChapters(File(directory, "missing.epub").path).getError()

        // Then
        assertNotNull(error)
    }

    private fun EpubChapterText.textOf(id: String): String {
        val offset = elementOffsets.first { element -> element.elementId == id }
        return text.substring(offset.startOffset, offset.endOffset)
    }

    private fun LongRange.overlaps(other: LongRange): Boolean =
        first <= other.last && other.first <= last

    companion object {
        const val AUDIO_BYTES = 8 * 1024 * 1024
    }
}

/** Where an entry's data lies in the file: after its local header, name and extra field. */
internal fun dataRangeOf(epub: File, name: String): LongRange {
    val bytes = java.io.RandomAccessFile(epub, "r").use { file ->
        val all = ByteArray(file.length().toInt())
        file.readFully(all)
        all
    }
    val nameBytes = name.toByteArray()
    var index = 0
    while (index < bytes.size - 30) {
        val isHeader = bytes[index] == 0x50.toByte() && bytes[index + 1] == 0x4b.toByte() &&
            bytes[index + 2] == 0x03.toByte() && bytes[index + 3] == 0x04.toByte()
        if (isHeader) {
            val nameLength = (bytes[index + 26].toInt() and 0xFF) or
                ((bytes[index + 27].toInt() and 0xFF) shl 8)
            val extraLength = (bytes[index + 28].toInt() and 0xFF) or
                ((bytes[index + 29].toInt() and 0xFF) shl 8)
            val entryName = bytes.copyOfRange(index + 30, index + 30 + nameLength)
            if (entryName.contentEquals(nameBytes)) {
                val start = index + 30L + nameLength + extraLength
                return start until start + EpubTextReaderTest.AUDIO_BYTES
            }
        }
        index++
    }
    error("entry $name not found")
}
