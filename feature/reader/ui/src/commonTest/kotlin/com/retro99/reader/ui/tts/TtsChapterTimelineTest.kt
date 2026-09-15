package com.retro99.reader.ui.tts

import kotlin.test.Test
import kotlin.test.assertEquals

class TtsChapterTimelineTest {

    @Test
    fun `chapter position continues across sentence boundaries`() {
        // Given
        val timeline = TtsChapterTimeline.estimate(
            sentences = listOf(
                sentence(index = 0, text = "1234567890"),
                sentence(index = 1, text = "1234567890"),
            ),
            speechRate = 1f,
        )

        // When
        val firstSentenceEnd = timeline.chapterPositionMs(
            sentenceIndex = 0,
            sentencePositionMs = 2_000L,
            sentenceDurationMs = 2_000L,
        )
        val secondSentenceStart = timeline.chapterPositionMs(
            sentenceIndex = 1,
            sentencePositionMs = 0L,
            sentenceDurationMs = 2_000L,
        )

        // Then
        assertEquals(firstSentenceEnd, secondSentenceStart)
        assertEquals(1_500L, timeline.durationMs)
    }

    @Test
    fun `locate maps chapter position back to sentence progress`() {
        // Given
        val timeline = TtsChapterTimeline.estimate(
            sentences = listOf(
                sentence(index = 0, text = "1234567890"),
                sentence(index = 1, text = "1234567890"),
            ),
            speechRate = 1f,
        )

        // When
        val position = timeline.locate(1_125L)

        // Then
        assertEquals(1, position.sentenceIndex)
        assertEquals(0.5, position.sentenceProgress)
    }

    @Test
    fun `faster speech rate shortens estimated chapter duration`() {
        // Given
        val sentences = listOf(sentence(index = 0, text = "a".repeat(100)))

        // When
        val normalDurationMs = TtsChapterTimeline.estimate(sentences, 1f).durationMs
        val fasterDurationMs = TtsChapterTimeline.estimate(sentences, 2f).durationMs

        // Then
        assertEquals(normalDurationMs / 2, fasterDurationMs)
    }

    @Test
    fun `actual sentence duration updates following sentence position`() {
        // Given
        val timeline = TtsChapterTimeline.estimate(
            sentences = listOf(
                sentence(index = 0, text = "1234567890"),
                sentence(index = 1, text = "1234567890"),
            ),
            speechRate = 1f,
        )

        // When
        val updatedTimeline = timeline.withSentenceDuration(
            sentenceIndex = 0,
            durationMs = 2_000L,
        )

        // Then
        assertEquals(2_750L, updatedTimeline.durationMs)
        assertEquals(
            2_000L,
            updatedTimeline.chapterPositionMs(
                sentenceIndex = 1,
                sentencePositionMs = 0L,
                sentenceDurationMs = 750L,
            ),
        )
    }

    @Test
    fun `timeline uses the shared neural speech rate range`() {
        // Given
        val sentences = listOf(sentence(index = 0, text = "a".repeat(100)))

        // When
        val belowMinimum = TtsChapterTimeline.estimate(sentences, 0.25f)
        val atMinimum = TtsChapterTimeline.estimate(sentences, TtsSpeechRate.MIN)
        val aboveMaximum = TtsChapterTimeline.estimate(sentences, 4f)
        val atMaximum = TtsChapterTimeline.estimate(sentences, TtsSpeechRate.MAX)

        // Then
        assertEquals(atMinimum.durationMs, belowMinimum.durationMs)
        assertEquals(atMaximum.durationMs, aboveMaximum.durationMs)
    }

    private fun sentence(index: Int, text: String): TtsSentence {
        return TtsSentence(
            index = index,
            elementId = null,
            text = text,
        )
    }
}
