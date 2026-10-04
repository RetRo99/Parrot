package com.retro99.saved.domain

import com.retro99.saved.domain.model.HighlightColor
import com.retro99.saved.domain.model.TextAnchor
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Instant

class HighlightMergerTest {
    private val now = Instant.parse("2026-10-04T12:00:00Z")
    private val merged = TextAnchor(before = "planks. ", quote = "Out on the water … steady.", after = " It was")

    @Test
    fun `a selection over nothing saves the new highlight in the picked colour`() {
        // When
        val result = HighlightMerger.merge(
            fresh = item("new"),
            overlapping = emptyList(),
            mergedAnchor = merged,
            mergedLocation = item("new").location,
            pickedColor = HighlightColor.Sage,
            now = now,
        )

        // Then
        assertEquals("new", result.survivor.id)
        assertEquals(HighlightColor.Sage, result.survivor.color)
        assertEquals(emptyList(), result.absorbedIds)
    }

    @Test
    fun `overlapping highlights merge into the oldest and keep every note`() {
        // Given
        val older = item("a", note = "First thought", createdAt = "2026-09-01T10:00:00Z", color = HighlightColor.Rose)
        val newer = item("b", note = "Second thought", createdAt = "2026-09-05T10:00:00Z")

        // When
        val result = HighlightMerger.merge(
            fresh = item("new"),
            overlapping = listOf(newer, older),
            mergedAnchor = merged,
            mergedLocation = item("new").location,
            pickedColor = null,
            now = now,
        )

        // Then
        assertEquals("a", result.survivor.id)
        assertEquals(merged, result.survivor.anchor)
        assertEquals(HighlightColor.Rose, result.survivor.color, "no colour picked keeps the oldest")
        assertEquals("First thought\n\nSecond thought", result.survivor.note)
        assertEquals(now, result.survivor.updatedAt)
        assertEquals(listOf("b"), result.absorbedIds)
    }

    @Test
    fun `a picked colour recolours the merged highlight and equal notes appear once`() {
        // Given
        val first = item("a", note = "Same", createdAt = "2026-09-01T10:00:00Z")
        val second = item("b", note = " Same ", createdAt = "2026-09-02T10:00:00Z")

        // When
        val result = HighlightMerger.merge(
            fresh = item("new"),
            overlapping = listOf(first, second),
            mergedAnchor = merged,
            mergedLocation = item("new").location,
            pickedColor = HighlightColor.Sky,
            now = now,
        )

        // Then
        assertEquals(HighlightColor.Sky, result.survivor.color)
        assertEquals("Same", result.survivor.note)
    }
}
