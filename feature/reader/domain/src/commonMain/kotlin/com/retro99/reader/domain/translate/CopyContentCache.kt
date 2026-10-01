package com.retro99.reader.domain.translate

import com.github.michaelbull.result.get
import com.retro99.epub.api.EpubChapterText
import com.retro99.epub.api.EpubTextReader
import com.retro99.epub.api.ReadaloudTiming
import com.retro99.epub.api.ReadaloudTimingReader
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.koin.core.annotation.Provided
import org.koin.core.annotation.Single

/**
 * Chapter text and SMIL timing per file, kept in memory up to about 20 MB of text so repeated
 * translations don't re-read the EPUB. The least recently used files are dropped first.
 */
@Single
class CopyContentCache(
    @Provided private val textReader: EpubTextReader,
    @Provided private val timingReader: ReadaloudTimingReader,
) {
    private val mutex = Mutex()
    // Insertion order is recency order: a hit is moved to the end.
    private val chapters = LinkedHashMap<String, List<EpubChapterText>>()
    private val timings = LinkedHashMap<String, ReadaloudTiming>()

    suspend fun chapters(path: String): List<EpubChapterText>? {
        mutex.withLock { chapters.touch(path) }?.let { cached -> return cached }
        val read = textReader.readChapters(path).get() ?: return null
        mutex.withLock {
            chapters[path] = read
            trim()
        }
        return read
    }

    suspend fun timing(path: String): ReadaloudTiming? {
        mutex.withLock { timings.touch(path) }?.let { cached -> return cached }
        val read = timingReader.readTiming(path).get() ?: return null
        mutex.withLock {
            timings[path] = read
            while (timings.size > MAX_TIMINGS) timings.remove(timings.keys.first())
        }
        return read
    }

    private fun trim() {
        while (chapters.size > 1 && textChars() > MAX_TEXT_CHARS) {
            chapters.remove(chapters.keys.first())
        }
    }

    private fun textChars(): Long = chapters.values.sumOf { book ->
        book.sumOf { chapter -> chapter.text.length.toLong() }
    }

    private companion object {
        /** About 20 MB: Kotlin strings use two bytes per character. */
        const val MAX_TEXT_CHARS = 10_000_000L
        const val MAX_TIMINGS = 8
    }
}

/** Translations by (source copy, source observation time, target copy). */
@Single
class TranslationCache {
    private val mutex = Mutex()
    private val entries = LinkedHashMap<String, TranslatedPosition>()

    suspend fun get(key: String): TranslatedPosition? = mutex.withLock { entries.touch(key) }

    suspend fun put(key: String, value: TranslatedPosition) = mutex.withLock {
        entries[key] = value
        while (entries.size > MAX_ENTRIES) entries.remove(entries.keys.first())
    }

    private companion object {
        const val MAX_ENTRIES = 200
    }
}

/** The value for [key], moved to the most recently used end. */
private fun <V> LinkedHashMap<String, V>.touch(key: String): V? {
    val value = remove(key) ?: return null
    put(key, value)
    return value
}
