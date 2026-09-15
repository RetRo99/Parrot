package com.retro99.reader.ui.playback

import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.ForwardingPlayer
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import com.retro99.reader.ui.tts.TtsChapterTimeline

internal data class TtsSessionTimeline(
    val chapterTimeline: TtsChapterTimeline,
    val sentenceIndex: Int,
)

@OptIn(UnstableApi::class)
internal class TtsSessionPlayer(
    player: Player,
    private val timelineProvider: () -> TtsSessionTimeline?,
    private val seekRequester: (Long) -> Unit,
) : ForwardingPlayer(player) {

    override fun getDuration(): Long {
        return timelineProvider()?.chapterTimeline?.durationMs ?: super.getDuration()
    }

    override fun getContentDuration(): Long {
        return timelineProvider()?.chapterTimeline?.durationMs ?: super.getContentDuration()
    }

    override fun getCurrentPosition(): Long {
        return chapterPositionMs(
            sentencePositionMs = super.getCurrentPosition(),
            sentenceDurationMs = super.getDuration(),
        ) ?: super.getCurrentPosition()
    }

    override fun getContentPosition(): Long {
        return chapterPositionMs(
            sentencePositionMs = super.getContentPosition(),
            sentenceDurationMs = super.getContentDuration(),
        ) ?: super.getContentPosition()
    }

    override fun getBufferedPosition(): Long {
        return chapterPositionMs(
            sentencePositionMs = super.getBufferedPosition(),
            sentenceDurationMs = super.getDuration(),
        ) ?: super.getBufferedPosition()
    }

    override fun getContentBufferedPosition(): Long {
        return chapterPositionMs(
            sentencePositionMs = super.getContentBufferedPosition(),
            sentenceDurationMs = super.getContentDuration(),
        ) ?: super.getContentBufferedPosition()
    }

    override fun getBufferedPercentage(): Int {
        val durationMs = duration
        if (durationMs <= 0L || durationMs == C.TIME_UNSET) {
            return 0
        }
        return (bufferedPosition * 100 / durationMs).toInt().coerceIn(0, 100)
    }

    override fun getTotalBufferedDuration(): Long {
        if (timelineProvider() == null) return super.getTotalBufferedDuration()
        return (bufferedPosition - currentPosition).coerceAtLeast(0L)
    }

    override fun seekTo(positionMs: Long) {
        if (!requestChapterSeek(positionMs)) {
            super.seekTo(positionMs)
        }
    }

    override fun seekTo(mediaItemIndex: Int, positionMs: Long) {
        if (!requestChapterSeek(positionMs)) {
            super.seekTo(mediaItemIndex, positionMs)
        }
    }

    override fun seekToDefaultPosition() {
        if (!requestChapterSeek(0L)) {
            super.seekToDefaultPosition()
        }
    }

    override fun seekToDefaultPosition(mediaItemIndex: Int) {
        if (!requestChapterSeek(0L)) {
            super.seekToDefaultPosition(mediaItemIndex)
        }
    }

    override fun seekBack() {
        if (!requestChapterSeek(currentPosition - seekBackIncrement)) {
            super.seekBack()
        }
    }

    override fun seekForward() {
        if (!requestChapterSeek(currentPosition + seekForwardIncrement)) {
            super.seekForward()
        }
    }

    private fun chapterPositionMs(
        sentencePositionMs: Long,
        sentenceDurationMs: Long,
    ): Long? {
        val timeline = timelineProvider() ?: return null
        return timeline.chapterTimeline.chapterPositionMs(
            sentenceIndex = timeline.sentenceIndex,
            sentencePositionMs = sentencePositionMs,
            sentenceDurationMs = sentenceDurationMs,
        )
    }

    private fun requestChapterSeek(positionMs: Long): Boolean {
        val timeline = timelineProvider() ?: return false
        seekRequester(positionMs.coerceIn(0L, timeline.chapterTimeline.durationMs))
        return true
    }
}
