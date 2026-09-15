package com.retro99.reader.ui.tts

internal data class TtsChapterPosition(
    val sentenceIndex: Int,
    val sentenceProgress: Double,
)

internal class TtsChapterTimeline private constructor(
    private val sentenceDurationsMs: List<Long>,
) {
    private val sentenceStartPositionsMs =
        sentenceDurationsMs.runningFold(0L) { accumulatedDurationMs, sentenceDurationMs ->
            accumulatedDurationMs + sentenceDurationMs
        }

    val durationMs: Long = sentenceStartPositionsMs.lastOrNull() ?: 0L

    val isEmpty: Boolean
        get() = sentenceDurationsMs.isEmpty()

    fun withSentenceDuration(sentenceIndex: Int, durationMs: Long): TtsChapterTimeline {
        if (
            sentenceIndex !in sentenceDurationsMs.indices ||
            durationMs <= 0L ||
            sentenceDurationsMs[sentenceIndex] == durationMs
        ) {
            return this
        }

        return TtsChapterTimeline(
            sentenceDurationsMs.toMutableList().apply {
                this[sentenceIndex] = durationMs
            },
        )
    }

    fun chapterPositionMs(
        sentenceIndex: Int,
        sentencePositionMs: Long,
        sentenceDurationMs: Long,
    ): Long {
        if (sentenceDurationsMs.isEmpty()) return 0L

        val safeIndex = sentenceIndex.coerceIn(0, sentenceDurationsMs.lastIndex)
        val sentenceStartMs = sentenceStartPositionsMs[safeIndex]
        val estimatedDurationMs = sentenceDurationsMs[safeIndex]
        val sentenceProgress = if (sentenceDurationMs > 0L) {
            sentencePositionMs.toDouble() / sentenceDurationMs
        } else {
            0.0
        }
        return sentenceStartMs + (estimatedDurationMs * sentenceProgress)
            .toLong()
            .coerceIn(0L, estimatedDurationMs)
    }

    fun locate(chapterPositionMs: Long): TtsChapterPosition {
        if (sentenceDurationsMs.isEmpty()) {
            return TtsChapterPosition(sentenceIndex = 0, sentenceProgress = 0.0)
        }

        val safePositionMs = chapterPositionMs.coerceIn(0L, durationMs)
        var sentenceStartMs = 0L
        sentenceDurationsMs.forEachIndexed { index, sentenceDurationMs ->
            val sentenceEndMs = sentenceStartMs + sentenceDurationMs
            if (safePositionMs < sentenceEndMs) {
                return TtsChapterPosition(
                    sentenceIndex = index,
                    sentenceProgress = (safePositionMs - sentenceStartMs).toDouble() /
                            sentenceDurationMs,
                )
            }
            sentenceStartMs = sentenceEndMs
        }

        return TtsChapterPosition(
            sentenceIndex = sentenceDurationsMs.lastIndex,
            sentenceProgress = 1.0,
        )
    }

    companion object {
        val EMPTY = TtsChapterTimeline(emptyList())

        fun estimate(
            sentences: List<TtsSentence>,
            speechRate: Float,
        ): TtsChapterTimeline {
            val safeSpeechRate = TtsSpeechRate.coerce(speechRate)
            val durationsMs = sentences.map { sentence ->
                val characterCount = sentence.text.count { character ->
                    !character.isWhitespace()
                }.coerceAtLeast(1)
                (characterCount * CHARACTER_DURATION_MS_AT_NORMAL_RATE / safeSpeechRate)
                    .toLong()
                    .coerceAtLeast(MIN_SENTENCE_DURATION_MS)
            }
            return TtsChapterTimeline(durationsMs)
        }

        private const val CHARACTER_DURATION_MS_AT_NORMAL_RATE = 65L
        private const val MIN_SENTENCE_DURATION_MS = 750L
    }
}
