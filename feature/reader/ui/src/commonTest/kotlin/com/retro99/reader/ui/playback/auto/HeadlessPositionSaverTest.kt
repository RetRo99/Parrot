package com.retro99.reader.ui.playback.auto

import com.retro99.reader.domain.model.PositionDomainModel
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class HeadlessPositionSaverTest {

    private val saved = mutableListOf<PositionDomainModel>()
    private val propagated = mutableListOf<PositionDomainModel>()
    private val errors = mutableListOf<String>()
    private var saveSucceeds = true
    private var propagationEnabled = true

    private val saver = HeadlessPositionSaver(
        save = { position ->
            saved += position
            saveSucceeds
        },
        // Stands in for PropagateToLinkedCopiesUseCase, which checks the setting itself.
        propagate = { position -> if (propagationEnabled) propagated += position },
        observedAt = { OBSERVED_AT },
        onError = { _, message -> errors += message },
    )

    @Test
    fun `the final save propagates once, stamped with the observation time`() = runTest {
        // Given
        val position = position(audioMs = 42_000)

        // When
        saver.save(position, HeadlessSaveReason.Close)

        // Then
        assertEquals(listOf(position), saved)
        assertEquals(listOf(position.copy(observedAt = OBSERVED_AT)), propagated)
    }

    @Test
    fun `a pause propagates once`() = runTest {
        // Given
        val position = position(audioMs = 7_000)

        // When
        saver.save(position, HeadlessSaveReason.Pause)

        // Then
        assertEquals(1, saved.size)
        assertEquals(1, propagated.size)
    }

    @Test
    fun `periodic and chapter-end saves don't propagate`() = runTest {
        // Given
        val position = position(audioMs = 1_000)

        // When
        saver.save(position, HeadlessSaveReason.Periodic)
        saver.save(position, HeadlessSaveReason.ChapterEnd)

        // Then
        assertEquals(2, saved.size)
        assertTrue(propagated.isEmpty())
    }

    @Test
    fun `nothing propagates with the setting off`() = runTest {
        // Given
        propagationEnabled = false

        // When
        saver.save(position(audioMs = 1_000), HeadlessSaveReason.Pause)
        saver.save(position(audioMs = 2_000), HeadlessSaveReason.Close)

        // Then
        assertEquals(2, saved.size)
        assertTrue(propagated.isEmpty())
    }

    @Test
    fun `a failed save doesn't propagate`() = runTest {
        // Given
        saveSucceeds = false

        // When
        saver.save(position(audioMs = 1_000), HeadlessSaveReason.Close)

        // Then
        assertTrue(propagated.isEmpty())
    }

    @Test
    fun `a failing propagation is logged and never thrown`() = runTest {
        // Given
        val failing = HeadlessPositionSaver(
            save = { _ -> true },
            propagate = { _ -> error("boom") },
            observedAt = { OBSERVED_AT },
            onError = { _, message -> errors += message },
        )

        // When
        failing.save(position(audioMs = 1_000), HeadlessSaveReason.Close)

        // Then
        assertEquals(1, errors.size)
    }

    private fun position(audioMs: Long) = PositionDomainModel(
        bookUuid = "book",
        serverId = "server",
        timestamp = 1L,
        createdAt = null,
        updatedAt = null,
        locatorHref = "ch1.xhtml",
        locatorType = "application/xhtml+xml",
        locatorTitle = null,
        locatorTarget = null,
        audioTimestampMs = audioMs,
        chapterIndex = null,
        progression = null,
        totalChapters = null,
        totalDurationMs = null,
        totalProgression = null,
        position = null,
    )

    private companion object {
        const val OBSERVED_AT = "2026-10-01T10:00:00Z"
    }
}
