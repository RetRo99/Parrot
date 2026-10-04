package com.retro99.reader.ui.reader

import com.retro99.reader.domain.usecase.GetReaderSettingsUseCase
import com.retro99.reader.ui.di.ReaderScope
import com.retro99.reader.ui.model.ChapterReadingTimeInfo
import com.retro99.reader.ui.navigator.BookController
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import org.koin.core.annotation.Scope
import org.koin.core.annotation.Scoped

/**
 * Feeds the navigator's page turns into [ReadingSpeedEstimator] and exposes its results.
 *
 * - Observes book location changes and settings automatically
 * - Exposes a Flow of reading time info that updates on each page turn
 * - Exposes the established reading speed for persistence and the session's pages read
 *
 * Reading time is only measured for regular ebooks: books driven by narration (media
 * overlays) only report pages read, and while any narration or device TTS plays its auto
 * page turns are not counted at all ([setListening]).
 */
@Scope(ReaderScope::class)
@Scoped
class ReadingSpeedTracker(
    private val bookController: BookController,
    private val getReaderSettingsUseCase: GetReaderSettingsUseCase,
) {

    private val estimator = ReadingSpeedEstimator()

    /** Emits the established (confident) reading speed for persistence. */
    val establishedReadingSpeedWpm: StateFlow<Int?> = estimator.establishedReadingSpeedWpm

    /**
     * Flow of reading time information that updates on each page turn.
     *
     * Emits null when reading time display is disabled, when this is an audio-driven
     * (ReadAloud) book, or when no plausible estimate exists.
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    val readingTimeInfo: Flow<ChapterReadingTimeInfo?> = getReaderSettingsUseCase()
        .map { settings ->
            ReadingTimeSettings(
                showReadingTime = settings.showReadingTime,
                readingSpeedWpm = settings.readingSpeedWpm,
            )
        }
        .distinctUntilChanged()
        .flatMapLatest { settings ->
            bookController.currentLocator.map { locator ->
                estimator.onLocator(
                    chapterHref = locator.href,
                    progression = locator.progression,
                    chapterInfo = locator.chapterInfo,
                    fallbackWpm = settings.readingSpeedWpm,
                    // Pages are counted either way; only plain ebooks get a speed
                    // measurement (ReadAloud books have audio-based progress tracking).
                    trackSpeed = settings.showReadingTime && !bookController.hasMediaOverlays,
                )
            }
        }

    /** Pages read in this session: single-page forward turns with a real reading dwell. */
    fun sessionPagesRead(): Int = estimator.sessionPagesRead()

    /** While narration or TTS plays, its auto page turns are not pages the reader read. */
    fun setListening(isListening: Boolean) = estimator.setListening(isListening)
}

/**
 * Settings relevant to reading time calculation.
 */
private data class ReadingTimeSettings(
    val showReadingTime: Boolean,
    val readingSpeedWpm: Int,
)
