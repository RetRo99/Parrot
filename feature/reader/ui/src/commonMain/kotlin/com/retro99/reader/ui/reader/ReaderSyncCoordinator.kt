package com.retro99.reader.ui.reader

import com.retro99.reader.ui.di.ReaderScope
import com.retro99.reader.ui.navigator.AudioController
import com.retro99.reader.ui.navigator.BookController
import com.retro99.reader.ui.navigator.NarrationController
import com.retro99.reader.ui.navigator.SentenceDoubleTapEvent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import org.koin.core.annotation.Scope
import org.koin.core.annotation.Scoped

/**
 * Coordinates synchronization between book navigation and audio playback.
 *
 * Keeps the ViewModel focused on UI state by centralizing cross-controller wiring:
 * - Book locator changes -> audio chapter preparation
 * - Audio locator changes -> book highlight updates
 * - Sentence double-tap events -> active sentence playback implementation
 * - Narration chapter completion -> auto-play next chapter
 */
@Scope(ReaderScope::class)
@Scoped
class ReaderSyncCoordinator(
    private val bookController: BookController,
) : AutoCloseable {

    private var bookToAudioJob: Job? = null
    private var audioToBookJob: Job? = null
    private var sentencePlaybackJob: Job? = null
    private var chapterCompletionJob: Job? = null

    fun start(
        scope: CoroutineScope,
        audioController: AudioController,
    ) {
        if (bookToAudioJob != null || audioToBookJob != null) return

        bookToAudioJob = bookController.currentLocator
            .onEach { locator ->
                val visibleSentenceId = bookController.getVisibleSentenceId()
                audioController.onBookLocationChanged(locator, visibleSentenceId)
            }
            .launchIn(scope)

        audioToBookJob = audioController.currentAudioLocator
            .filterNotNull()
            .onEach { audioLocator ->
                bookController.applyHighlightWithPageTurn(
                    locator = audioLocator.locator,
                    sentenceDurationMs = audioLocator.sentenceDurationMs,
                )
            }
            .launchIn(scope)

        startNarration(scope, audioController)
    }

    fun startNarration(
        scope: CoroutineScope,
        narrationController: NarrationController,
        playFromSentence: ((SentenceDoubleTapEvent) -> Unit)? = null,
    ) {
        sentencePlaybackJob?.cancel()
        sentencePlaybackJob = bookController.sentenceDoubleTapEvents
            .onEach { event ->
                if (playFromSentence != null) {
                    playFromSentence(event)
                } else {
                    narrationController.playFromSentence(event.fragmentId, event.chapterHref)
                }
            }
            .launchIn(scope)

        chapterCompletionJob?.cancel()
        chapterCompletionJob = narrationController.chapterCompleted
            .onEach { completedChapterHref ->
                onChapterCompleted(narrationController, completedChapterHref)
            }
            .launchIn(scope)
    }

    fun stopNarration() {
        sentencePlaybackJob?.cancel()
        chapterCompletionJob?.cancel()
        sentencePlaybackJob = null
        chapterCompletionJob = null
    }

    /**
     * Handles chapter audio completion by navigating to the next chapter
     * and starting playback from the beginning.
     */
    private suspend fun onChapterCompleted(
        narrationController: NarrationController,
        completedChapterHref: String,
    ) {
        // Navigate forward - this will move to the next chapter
        bookController.goToNextPage()

        // Wait for the locator to change to a different chapter
        val newLocator = bookController.currentLocator
            .first { locator -> locator.href != completedChapterHref }

        // Start playback from the beginning of the new chapter
        narrationController.playFromChapterStart(newLocator.href)
    }

    override fun close() {
        bookToAudioJob?.cancel()
        audioToBookJob?.cancel()
        sentencePlaybackJob?.cancel()
        chapterCompletionJob?.cancel()
        bookToAudioJob = null
        audioToBookJob = null
        sentencePlaybackJob = null
        chapterCompletionJob = null
    }
}
