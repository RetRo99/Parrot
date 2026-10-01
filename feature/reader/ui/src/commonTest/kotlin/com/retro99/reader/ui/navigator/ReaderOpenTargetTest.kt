package com.retro99.reader.ui.navigator

import com.retro99.reader.ui.model.PositionUiModel
import kotlin.test.Test
import kotlin.test.assertEquals

class ReaderOpenTargetTest {

    private val readingOrder = listOf("OEBPS/ch01.xhtml", "OEBPS/ch02.xhtml")

    @Test
    fun `an href in the reading order opens at its locator`() {
        // Given
        val hrefs = listOf("OEBPS/ch02.xhtml", "OEBPS/ch02.xhtml#p3", "/OEBPS/ch02.xhtml")

        hrefs.forEach { href ->
            // When
            val target = position(href, totalProgression = 0.4).openTarget(readingOrder)

            // Then
            assertEquals(ReaderOpenTarget.Locator, target, "for $href")
        }
    }

    @Test
    fun `an href the publication doesn't have opens at the total progression`() {
        // Given: a CFI, a JSON locator and a missing chapter, all with 40% read.
        val hrefs = listOf(
            "epubcfi(/6/4!/4/2/8:0)",
            """{"href":"OEBPS/ch02.xhtml"}""",
            "OEBPS/missing.xhtml",
            "",
        )

        hrefs.forEach { href ->
            // When
            val target = position(href, totalProgression = 0.4).openTarget(readingOrder)

            // Then
            assertEquals(ReaderOpenTarget.TotalProgression(0.4), target, "for $href")
        }
    }

    @Test
    fun `a bad href without a total progression opens at the start`() {
        // When
        val target = position("epubcfi(/6/4!/4)", totalProgression = null)
            .openTarget(readingOrder)

        // Then
        assertEquals(ReaderOpenTarget.Start, target)
    }

    @Test
    fun `an unknown reading order keeps the locator`() {
        // When
        val target = position("OEBPS/ch02.xhtml", totalProgression = 0.4).openTarget(emptyList())

        // Then
        assertEquals(ReaderOpenTarget.Locator, target)
    }

    private fun position(href: String, totalProgression: Double?) = PositionUiModel(
        createdAt = null,
        href = href,
        type = "application/xhtml+xml",
        title = null,
        progression = 0.1,
        position = null,
        totalProgression = totalProgression,
        chapterIndex = null,
        totalChapters = null,
    )
}
