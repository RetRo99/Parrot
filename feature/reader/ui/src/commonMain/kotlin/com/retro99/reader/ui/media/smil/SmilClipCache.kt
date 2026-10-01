package com.retro99.reader.ui.media.smil

import com.retro99.epub.api.SmilClip
import com.retro99.reader.ui.di.ReaderScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.koin.core.annotation.Scope
import org.koin.core.annotation.Scoped

/**
 * Represents a cached set of clips for a single chapter.
 *
 * @property clips The parsed clips for this chapter
 * @property isParsed Whether parsing has completed (to avoid duplicate work)
 */
data class ChapterClipData(
    val clips: List<SmilClip>,
    val isParsed: Boolean = true,
)

/**
 * Thread-safe, no-eviction cache for parsed SMIL clips.
 *
 * Stores clips by normalized chapter href. Once a chapter's clips are parsed,
 * they remain in memory for the session to avoid re-parsing.
 *
 * This cache is designed to be shared across the reader session and should
 * be cleared when the reader is closed.
 */
@Scope(ReaderScope::class)
@Scoped
class SmilClipCache {

    private val mutex = Mutex()
    private val cache = mutableMapOf<String, ChapterClipData>()

    /**
     * Checks if a chapter's clips have already been parsed.
     *
     * @param chapterHref Normalized chapter href (without fragment)
     * @return true if clips are cached and parsing is complete
     */
    suspend fun isParsed(chapterHref: String): Boolean = mutex.withLock {
        cache[chapterHref]?.isParsed == true
    }

    /**
     * Gets cached clips for a chapter, if available.
     *
     * @param chapterHref Normalized chapter href (without fragment)
     * @return Cached clips, or null if not yet parsed
     */
    suspend fun getClips(chapterHref: String): List<SmilClip>? = mutex.withLock {
        cache[chapterHref]?.takeIf { it.isParsed }?.clips
    }

    /**
     * Stores parsed clips for a chapter.
     *
     * @param chapterHref Normalized chapter href (without fragment)
     * @param clips The parsed clips to cache
     */
    suspend fun putClips(chapterHref: String, clips: List<SmilClip>) {
        mutex.withLock {
            cache[chapterHref] = ChapterClipData(clips = clips, isParsed = true)
        }
    }

    /**
     * Marks a chapter as currently being parsed (to prevent duplicate work).
     * Call this before starting to parse a chapter's SMIL files.
     *
     * @param chapterHref Normalized chapter href (without fragment)
     * @return true if this call claimed the parsing work, false if already being parsed
     */
    suspend fun tryClaimParsing(chapterHref: String): Boolean = mutex.withLock {
        if (cache.containsKey(chapterHref)) {
            false // Already parsed or being parsed
        } else {
            cache[chapterHref] = ChapterClipData(clips = emptyList(), isParsed = false)
            true
        }
    }

    /**
     * Gets all cached clips across all chapters.
     * Useful for operations that need access to all parsed clips.
     *
     * @return All cached clips, sorted by chapter
     */
    suspend fun getAllClips(): List<SmilClip> = mutex.withLock {
        cache.values
            .filter { it.isParsed }
            .flatMap { it.clips }
    }

    /**
     * Gets the set of chapter hrefs that have been parsed.
     *
     * @return Set of normalized chapter hrefs
     */
    suspend fun getParsedChapters(): Set<String> = mutex.withLock {
        cache.filterValues { it.isParsed }.keys.toSet()
    }

    /**
     * Clears all cached data.
     * Should be called when the reader session ends.
     */
    fun clear() {
            cache.clear()
    }

    /**
     * Gets cache statistics for diagnostics.
     *
     * @return Pair of (parsed chapter count, total clip count)
     */
    suspend fun getStats(): Pair<Int, Int> = mutex.withLock {
        val parsedCount = cache.count { it.value.isParsed }
        val clipCount = cache.values.filter { it.isParsed }.sumOf { it.clips.size }
        Pair(parsedCount, clipCount)
    }
}

