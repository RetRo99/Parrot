package com.retro99.server.audiobookshelf.model

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AbsEbookLocationTest {

    private val readingOrder = listOf(
        "OEBPS/cover.xhtml",
        "OEBPS/ch01.xhtml",
        "OEBPS/ch02.xhtml",
    )

    @Test
    fun `each stored shape is read into our position`() = runTest {
        // Given
        val json = """{"href":"OEBPS/ch02.xhtml","type":"application/xhtml+xml",""" +
            """"locations":{"progression":0.42,"totalProgression":0.7,""" +
            """"cssSelector":"#p12"}}"""
        val cases = listOf(
            json to ParsedAbsLocation(
                shape = AbsLocationShape.ReadiumJson,
                href = "OEBPS/ch02.xhtml",
                type = "application/xhtml+xml",
                progression = 0.42,
                totalProgression = 0.7,
                cssSelector = "#p12",
                spineIndex = null,
            ),
            "epubcfi(/6/6!/4/2/8:0)" to ParsedAbsLocation(
                shape = AbsLocationShape.Cfi,
                href = "OEBPS/ch02.xhtml",
                totalProgression = 0.5,
                spineIndex = 2,
            ),
            "OEBPS/ch01.xhtml" to ParsedAbsLocation(
                shape = AbsLocationShape.Href,
                href = "OEBPS/ch01.xhtml",
                totalProgression = 0.5,
            ),
            "" to ParsedAbsLocation(shape = AbsLocationShape.Empty, totalProgression = 0.5),
            "{not json" to ParsedAbsLocation(
                shape = AbsLocationShape.Unknown,
                totalProgression = 0.5,
            ),
            "chapter two, page 3" to ParsedAbsLocation(
                shape = AbsLocationShape.Unknown,
                totalProgression = 0.5,
            ),
        )

        cases.forEach { (raw, expected) ->
            // When
            val parsed = parseAbsEbookLocation(raw, ebookProgress = 0.5) { readingOrder }

            // Then
            assertEquals(expected, parsed, "for $raw")
        }
    }

    @Test
    fun `a cfi without the book on this device keeps its spine step and the progress`() = runTest {
        // When
        val parsed = parseAbsEbookLocation("epubcfi(/6/14!/4/2/8:0)", 0.3) { null }

        // Then
        assertEquals(AbsLocationShape.Cfi, parsed.shape)
        assertNull(parsed.href)
        assertEquals(6, parsed.spineIndex)
        assertEquals(0.3, parsed.totalProgression)
    }

    @Test
    fun `a json locator without its own total progression uses ebookProgress`() = runTest {
        // When
        val parsed = parseAbsEbookLocation("""{"href":"OEBPS/ch01.xhtml"}""", 0.25) { null }

        // Then
        assertEquals("OEBPS/ch01.xhtml", parsed.href)
        assertEquals(0.25, parsed.totalProgression)
    }

    @Test
    fun `the cfi spine step is right for spine indexes 0, 1 and 9`() = runTest {
        // Given
        val cases = listOf(
            0 to "epubcfi(/6/2!/4)",
            1 to "epubcfi(/6/4!/4)",
            9 to "epubcfi(/6/20!/4)",
        )

        cases.forEach { (spineIndex, expected) ->
            // When
            val cfi = chapterStartCfi(spineIndex)

            // Then
            assertEquals(expected, cfi)
            assertEquals(
                spineIndex,
                parseAbsEbookLocation(cfi, null) { null }.spineIndex,
            )
        }
    }

    @Test
    fun `a non-linear cover first in the spine counts as spine item 0`() = runTest {
        // Given: the full spine, with the cover (linear="no") as the first itemref.
        val spine = listOf("OEBPS/cover.xhtml", "OEBPS/chapter1.xhtml", "OEBPS/chapter2.xhtml")
        val chapterOne = place().copy(href = "OEBPS/chapter1.xhtml")

        // When
        val built = buildAbsEbookLocation(chapterOne, storedRaw = null) { spine }
        val parsed = parseAbsEbookLocation("epubcfi(/6/4!/4/2/1:0)", 0.1) { spine }

        // Then
        assertEquals("epubcfi(/6/4!/4)", built)
        assertEquals("OEBPS/chapter1.xhtml", parsed.href)
    }

    @Test
    fun `a stored json locator is mirrored as json`() = runTest {
        // Given
        val stored = """{"href":"OEBPS/ch01.xhtml","locations":{"progression":0.1}}"""

        // When
        val built = buildAbsEbookLocation(place(), stored) { readingOrder }

        // Then
        val reparsed = parseAbsEbookLocation(built, null) { null }
        assertEquals(AbsLocationShape.ReadiumJson, reparsed.shape)
        assertEquals("OEBPS/ch02.xhtml", reparsed.href)
        assertEquals(0.42, reparsed.progression)
        assertEquals(0.7, reparsed.totalProgression)
        assertEquals("#p12", reparsed.cssSelector)
    }

    @Test
    fun `a stored cfi, nothing stored or an old bare href is written as a cfi`() = runTest {
        // Given
        val stored = listOf("epubcfi(/6/4!/4/2)", null, "", "OEBPS/ch01.xhtml")

        stored.forEach { raw ->
            // When
            val built = buildAbsEbookLocation(place(), raw) { readingOrder }

            // Then
            assertEquals("epubcfi(/6/6!/4)", built, "for $raw")
        }
    }

    @Test
    fun `a bare href is never written`() = runTest {
        // Given: the chapter isn't in the reading order and has no chapter index.
        val stored = listOf("epubcfi(/6/4!/4/2)", null, "OEBPS/ch01.xhtml")
        val unknown = place().copy(href = "OEBPS/missing.xhtml")

        stored.forEach { raw ->
            // When
            val built = buildAbsEbookLocation(unknown, raw) { readingOrder }

            // Then
            assertNull(built, "for $raw")
        }
    }

    @Test
    fun `the chapter index stands in for the reading order when the book isn't here`() = runTest {
        // When
        val built = buildAbsEbookLocation(place().copy(chapterIndex = 2), null) { null }

        // Then
        assertEquals("epubcfi(/6/6!/4)", built)
    }

    @Test
    fun `cfi and json values are recognised`() = runTest {
        // Then
        assertTrue(isAbsCfi("epubcfi(/6/4!/4)"))
        assertFalse(isAbsCfi("OEBPS/ch01.xhtml"))
    }

    private fun place() = AbsEbookPlace(
        href = "OEBPS/ch02.xhtml#p12",
        type = "application/xhtml+xml",
        title = "Two",
        progression = 0.42,
        totalProgression = 0.7,
        cssSelector = "#p12",
        chapterIndex = null,
    )
}
