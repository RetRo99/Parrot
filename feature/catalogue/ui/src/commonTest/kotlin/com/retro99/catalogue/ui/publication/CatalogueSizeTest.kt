package com.retro99.catalogue.ui.publication

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * A 23 KB book read "Download · EPUB · 0.0 MB" on the first iPhone run
 * (docs/opds-ios-check-report.md). MB wording stays from a megabyte up; below that the size is
 * written in kilobytes, and below a kilobyte in bytes.
 */
class CatalogueSizeTest {

    @Test
    fun sizesBelowAMegabyteAreNotWrittenInMegabytes() {
        val cases = listOf(
            -1L to "0 bytes",
            0L to "0 bytes",
            1L to "1 byte",
            2L to "2 bytes",
            999L to "999 bytes",
            1_000L to "1.0 KB",
            1_536L to "1.5 KB",
            // The local test book of the iPhone run.
            23_330L to "23.3 KB",
            780_000L to "780.0 KB",
            999_949L to "999.9 KB",
            // Rounds to a megabyte, so it is one: never "1000.0 KB".
            999_950L to "1.0 MB",
            1_000_000L to "1.0 MB",
            24_800_000L to "24.8 MB",
            46_093_367L to "46.1 MB",
            536_870_912L to "536.9 MB",
        )
        cases.forEach { (bytes, expected) ->
            assertEquals(expected, catalogueSize(bytes), "catalogueSize($bytes)")
        }
    }

    @Test
    fun theSoFarHalfOfProgressIsWrittenInTheTotalsUnit() {
        // Read as "<so far> of <total>": the unit belongs to the total alone.
        val cases = listOf(
            Triple(0L, 24_800_000L, "0.0"),
            Triple(12_400_000L, 24_800_000L, "12.4"),
            Triple(0L, 23_330L, "0.0"),
            Triple(500L, 23_330L, "0.5"),
            Triple(11_665L, 23_330L, "11.7"),
            Triple(0L, 800L, "0"),
            Triple(1L, 800L, "1"),
            Triple(500L, 800L, "500"),
            Triple(0L, 0L, "0"),
        )
        cases.forEach { (bytes, total, expected) ->
            assertEquals(expected, catalogueSizeSoFar(bytes, total), "catalogueSizeSoFar($bytes, $total)")
        }
    }
}
