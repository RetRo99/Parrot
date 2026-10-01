package com.retro99.reader.domain.translate

import com.retro99.epub.api.EpubChapterText
import com.retro99.epub.api.ReadaloudTiming
import com.retro99.epub.api.TimedClip
import com.retro99.epub.api.globalBeginMs
import com.retro99.epub.api.globalEndMs

/** A read-aloud on this device: its text and its SMIL timing. */
data class ReadaloudContent(
    val chapters: List<EpubChapterText>,
    val timing: ReadaloudTiming,
)

/**
 * Maps between text and audio time through a read-aloud's SMIL (§1.5, strategy 3), and
 * between two audio timelines whose total durations agree (P6b).
 */
class SmilBridge {

    /** The global audio time of the clip covering [point] in the read-aloud's text. */
    fun textToAudioMs(readaloud: ReadaloudContent, point: TextPoint): Long? {
        val chapter = readaloud.chapters.getOrNull(point.chapterIndex) ?: return null
        val chapterPath = normalizeHref(chapter.href)
        val clips = readaloud.timing.clips.filter { clip ->
            normalizeHref(clip.textHref) == chapterPath
        }
        if (clips.isEmpty()) return null
        val starts = clips.map { clip -> clip to clipStart(chapter, clip) }
        val covering = starts.lastOrNull { (_, start) -> start != null && start <= point.offset }
            ?: starts.first()
        return readaloud.timing.globalBeginMs(covering.first)
    }

    /** Where in the read-aloud's text the clip playing at [globalMs] is. */
    fun audioToText(readaloud: ReadaloudContent, globalMs: Long): TextPoint? {
        val clips = readaloud.timing.clips
        if (clips.isEmpty()) return null
        val time = globalMs.coerceIn(0L, readaloud.timing.totalDurationMs)
        val clip = clips.firstOrNull { candidate ->
            readaloud.timing.globalBeginMs(candidate) <= time &&
                time < readaloud.timing.globalEndMs(candidate)
        } ?: clips.lastOrNull { candidate -> readaloud.timing.globalBeginMs(candidate) <= time }
            ?: clips.first()
        val chapterIndex = readaloud.chapters.indexOfHref(clip.textHref)
        if (chapterIndex < 0) return null
        val offset = clipStart(readaloud.chapters[chapterIndex], clip) ?: 0
        return TextPoint(chapterIndex, offset)
    }

    /** Maps a time between two timelines when their lengths agree within 1%, else null. */
    fun mapTime(timeMs: Long, sourceDurationMs: Long?, targetDurationMs: Long?): Long? {
        if (sourceDurationMs == null || targetDurationMs == null) return null
        if (!durationsMatch(sourceDurationMs, targetDurationMs)) return null
        return timeMs.coerceIn(0L, targetDurationMs)
    }

    private fun clipStart(chapter: EpubChapterText, clip: TimedClip): Int? {
        val fragment = clip.fragmentId ?: return 0
        return chapter.elementOffsets.firstOrNull { element -> element.elementId == fragment }
            ?.startOffset
    }
}
