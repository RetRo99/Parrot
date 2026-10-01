package com.retro99.reader.ui.media.smil

import com.retro99.epub.api.SmilClip
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.koin.core.annotation.Single

/**
 * Information about the currently playing book.
 * Used for reconnection detection when re-entering the reader.
 */
data class PlayingBookInfo(
    val bookUuid: String,
    val serverId: String,
    val title: String,
    val currentChapterHref: String,
    val coverArtwork: ByteArray? = null,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other == null || this::class != other::class) return false
        other as PlayingBookInfo
        return bookUuid == other.bookUuid && serverId == other.serverId
    }

    override fun hashCode(): Int {
        var result = bookUuid.hashCode()
        result = 31 * result + serverId.hashCode()
        return result
    }
}

/**
 * Global repository for SMIL clips, accessible by both ReaderScope and Service.
 *
 * This singleton stores clips for the currently playing book, allowing:
 * - The service to access clips for position tracking and Android Auto
 * - The UI to reconnect to ongoing playback when re-entering a book
 * - Clips to survive ReaderScope destruction
 *
 * Follows Storyteller's BookService pattern: single book, globally accessible.
 *
 * Thread-safe: all operations use mutex synchronization.
 */
@Single
class SmilClipRepository {

    private val mutex = Mutex()

    // Currently playing book info
    private val _currentBook = MutableStateFlow<PlayingBookInfo?>(null)
    val currentBook: StateFlow<PlayingBookInfo?> = _currentBook.asStateFlow()

    // Clips by chapter href for the current book
    // Key: normalized chapter href, Value: list of clips
    private val clipsByChapter = mutableMapOf<String, List<SmilClip>>()

    // Track which chapters are currently being parsed (to avoid duplicate work)
    private val parsingInProgress = mutableSetOf<String>()

    /**
     * Sets the currently playing book.
     * Clears any existing clips if switching to a different book.
     *
     * @param info The book info, or null to clear
     */
    suspend fun setCurrentBook(info: PlayingBookInfo?) {
        mutex.withLock {
            val previousBook = _currentBook.value
            if (previousBook?.bookUuid != info?.bookUuid) {
                // Different book - clear existing clips
                clipsByChapter.clear()
                parsingInProgress.clear()
            }
            _currentBook.value = info
        }
    }

    /**
     * Updates the current chapter href for the playing book.
     *
     * @param chapterHref The new current chapter href
     */
    suspend fun updateCurrentChapter(chapterHref: String) {
        mutex.withLock {
            _currentBook.value?.let { book ->
                _currentBook.value = book.copy(currentChapterHref = chapterHref)
            }
        }
    }

    /**
     * Checks if a book is currently loaded in the repository.
     *
     * @param bookUuid The book UUID to check
     * @return true if this book is currently loaded
     */
    fun isBookLoaded(bookUuid: String): Boolean {
        return _currentBook.value?.bookUuid == bookUuid
    }

    /**
     * Stores parsed clips for a chapter.
     *
     * @param chapterHref Normalized chapter href
     * @param clips The parsed clips
     */
    suspend fun storeClips(chapterHref: String, clips: List<SmilClip>) {
        mutex.withLock {
            clipsByChapter[chapterHref] = clips
            parsingInProgress.remove(chapterHref)
        }
    }

    /**
     * Gets clips for a chapter, if available.
     *
     * @param chapterHref Normalized chapter href
     * @return The clips, or null if not yet parsed
     */
    suspend fun getClips(chapterHref: String): List<SmilClip>? {
        return mutex.withLock { clipsByChapter[chapterHref] }
    }

    /**
     * Checks if clips are available for a chapter.
     *
     * @param chapterHref Normalized chapter href
     * @return true if clips are stored for this chapter
     */
    suspend fun hasClips(chapterHref: String): Boolean {
        return mutex.withLock { clipsByChapter.containsKey(chapterHref) }
    }

    /**
     * Attempts to claim parsing work for a chapter.
     * Returns true if this call claimed the work, false if already being parsed.
     *
     * @param chapterHref Normalized chapter href
     * @return true if parsing should proceed, false if already in progress
     */
    suspend fun tryClaimParsing(chapterHref: String): Boolean {
        return mutex.withLock {
            if (clipsByChapter.containsKey(chapterHref) || parsingInProgress.contains(chapterHref)) {
                false
            } else {
                parsingInProgress.add(chapterHref)
                true
            }
        }
    }

    /**
     * Gets all stored clips across all chapters.
     *
     * @return All clips, sorted by chapter
     */
    suspend fun getAllClips(): List<SmilClip> {
        return mutex.withLock {
            clipsByChapter.values.flatten()
        }
    }
}

