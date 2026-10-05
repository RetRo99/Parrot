package com.retro99.reader.ui.reader.saved

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import com.retro99.saved.domain.model.HighlightColor
import com.retro99.saved.ui.of
import kotlin.test.Test
import kotlin.test.assertEquals

class SelectionToolbarPaletteTest {

    @Test
    fun `light page uses the light toolbar fills and subtle outline regardless of app theme`() {
        val palette = selectionToolbarHighlightPalette(isDarkPage = false)

        assertEquals(
            listOf(0xFFFBE3A6.toInt(), 0xFFF8CFCB.toInt(), 0xFFD3E6C4.toInt(), 0xFFCCE0F2.toInt()),
            HighlightColor.entries.map { palette.of(it).fill.toArgb() },
        )
        assertEquals(
            List(4) { Color.Black.copy(alpha = 0.12f) },
            HighlightColor.entries.map { palette.of(it).bar },
        )
    }

    @Test
    fun `dark page uses opaque dark toolbar fills without a light page outline`() {
        val palette = selectionToolbarHighlightPalette(isDarkPage = true)

        assertEquals(
            listOf(0xFF6B4E16.toInt(), 0xFF6A2F2B.toInt(), 0xFF34502A.toInt(), 0xFF27465F.toInt()),
            HighlightColor.entries.map { palette.of(it).fill.toArgb() },
        )
        assertEquals(
            List(4) { Color.Transparent },
            HighlightColor.entries.map { palette.of(it).bar },
        )
    }
}
