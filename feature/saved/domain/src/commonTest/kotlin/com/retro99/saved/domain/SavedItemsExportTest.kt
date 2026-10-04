package com.retro99.saved.domain

import com.retro99.saved.domain.model.SavedItemType
import kotlin.test.Test
import kotlin.test.assertEquals

class SavedItemsExportTest {
    private val labels = SavedItemsExport.Labels(
        bookmark = "Bookmark",
        highlight = "Highlight",
        note = "Note",
        listeningFormat = "listening, %s",
        untitledChapter = "Untitled",
    )
    private val items = listOf(
        item("1", type = SavedItemType.Bookmark, quote = "They left the road.", chapter = "6 · The Hill Road", totalProgression = 0.41),
        item("2", quote = "The lanterns flickered.", note = "Back in the last chapter?"),
        item("3", type = SavedItemType.Bookmark, quote = "“It was not mine to open.”", audioMs = 15_128_000),
    )

    @Test
    fun `plain text lists items in book order under chapter headings`() {
        // When
        val text = SavedItemsExport.plainText("The Lantern Ferry", "A. Writer", items, labels)

        // Then
        assertEquals(
            """
            The Lantern Ferry — A. Writer

            6 · The Hill Road

            “They left the road.”
            Bookmark · 41%

            8. The Crossing

            “The lanterns flickered.”
            Note: Back in the last chapter?
            Highlight · 62%

            ““It was not mine to open.””
            Bookmark · listening, 4:12:08

            """.trimIndent(),
            text,
        )
    }

    @Test
    fun `markdown quotes the text and puts the note in bold`() {
        // When
        val markdown = SavedItemsExport.markdown("The Lantern Ferry", null, items.take(2), labels)

        // Then
        assertEquals(
            """
            # The Lantern Ferry

            ## 6 · The Hill Road

            > They left the road.

            *Bookmark · 41%*

            ## 8. The Crossing

            > The lanterns flickered.

            **Note:** Back in the last chapter?

            *Highlight · 62%*

            """.trimIndent(),
            markdown,
        )
    }

    @Test
    fun `audio time reads as hours minutes seconds`() {
        assertEquals("4:12:08", formatAudioTime(15_128_000))
        assertEquals("3:05", formatAudioTime(185_000))
    }
}
