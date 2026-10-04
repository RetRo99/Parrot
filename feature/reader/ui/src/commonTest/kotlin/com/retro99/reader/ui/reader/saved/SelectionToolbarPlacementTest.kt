package com.retro99.reader.ui.reader.saved

import kotlin.test.Test
import kotlin.test.assertEquals

class SelectionToolbarPlacementTest {
    private fun place(top: Int, bottom: Int, floor: Int = 760, ceiling: Int = 0) =
        selectionToolbarY(top, bottom, 116, ceiling, floor, 12, 32)

    @Test fun topSelectionClearsHandlesBelow() {
        assertEquals(132, place(60, 100))
    }

    @Test fun bottomSelectionPlacesTwelveAboveFirstLine() {
        assertEquals(552, place(680, 720))
    }

    @Test fun panelOrKeyboardForcesAbove() {
        assertEquals(332, place(460, 540, floor = 620))
    }

    @Test fun fullPageSelectionPinsAboveProgress() {
        assertEquals(644, place(0, 760))
    }

    @Test fun topBarCannotBeCovered() {
        assertEquals(72, place(10, 40, ceiling = 65))
    }

    @Test fun obscuredSelectionCannotPutToolbarInsidePanel() {
        assertEquals(384, place(700, 740, floor = 500))
    }

    @Test fun draggedSelectionRepositions() {
        assertEquals(312, place(240, 280))
        assertEquals(552, place(680, 720))
    }
}
