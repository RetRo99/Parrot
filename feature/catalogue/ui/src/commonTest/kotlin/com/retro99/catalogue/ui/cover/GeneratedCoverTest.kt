package com.retro99.catalogue.ui.cover

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class GeneratedCoverTest {
    @Test fun colourIsStableAndBoundedEvenForNegativeHashes() {
        listOf("Treasure Island", "Frankenstein", "Pride and Prejudice", "Moby Dick", "", "𐐀").forEach { title ->
            assertEquals(generatedCoverColourIndex(title, 4), generatedCoverColourIndex(title, 4))
            assertTrue(generatedCoverColourIndex(title, 4) in 0..3)
            assertEquals(0, generatedCoverColourIndex(title, 1))
        }
    }
}
