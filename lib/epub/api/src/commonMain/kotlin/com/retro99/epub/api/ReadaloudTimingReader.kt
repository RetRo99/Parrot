package com.retro99.epub.api

import com.retro99.base.result.AppResult

/** Reads a read-aloud EPUB's media overlay (SMIL) timing, without reading any audio. */
interface ReadaloudTimingReader {
    /** SMIL clips in reading order, with each audio file's global start offset. */
    suspend fun readTiming(filePath: String): AppResult<ReadaloudTiming>
}

data class ReadaloudTiming(
    val clips: List<TimedClip>,
    /** Where each audio file starts on the book's single global timeline. */
    val audioFileOffsetsMs: Map<String, Long>,
    val totalDurationMs: Long,
)

/** One SMIL `<par>`: a text fragment and the stretch of one audio file that reads it. */
data class TimedClip(
    /** The chapter's path inside the EPUB. */
    val textHref: String,
    /** The fragment after '#', usually a sentence element's id. */
    val fragmentId: String?,
    /** The audio file's path inside the EPUB. */
    val audioSrc: String,
    val clipBeginMs: Long,
    val clipEndMs: Long,
)

/** A clip's start on the global timeline: its audio file's offset plus its clip begin. */
fun ReadaloudTiming.globalBeginMs(clip: TimedClip): Long =
    (audioFileOffsetsMs[clip.audioSrc] ?: 0L) + clip.clipBeginMs

fun ReadaloudTiming.globalEndMs(clip: TimedClip): Long =
    (audioFileOffsetsMs[clip.audioSrc] ?: 0L) + clip.clipEndMs
