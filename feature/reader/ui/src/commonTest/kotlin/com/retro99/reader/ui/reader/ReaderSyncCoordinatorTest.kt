package com.retro99.reader.ui.reader

import com.retro99.reader.ui.model.LocatorState
import com.retro99.reader.ui.model.PositionUiModel
import com.retro99.reader.ui.model.ReaderSettingsUiModel
import com.retro99.reader.ui.navigator.BookController
import com.retro99.reader.ui.navigator.NarrationController
import com.retro99.reader.ui.navigator.SentenceDoubleTapEvent
import com.retro99.reader.ui.navigator.SentenceVisibilityResult
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

@OptIn(ExperimentalCoroutinesApi::class)
class ReaderSyncCoordinatorTest {

    @Test
    fun `double tap starts active narration from tapped sentence`() = runTest {
        // Given
        val bookController = FakeBookController()
        val narrationController = FakeNarrationController()
        val classUnderTest = ReaderSyncCoordinator(bookController)
        classUnderTest.startNarration(backgroundScope, narrationController)
        runCurrent()

        // When
        bookController.emitDoubleTap(
            SentenceDoubleTapEvent(
                fragmentId = "sentence-2",
                chapterHref = "chapter-1.xhtml",
            ),
        )
        runCurrent()

        // Then
        assertEquals("sentence-2", narrationController.playedFragmentId)
        assertEquals("chapter-1.xhtml", narrationController.playedChapterHref)
        classUnderTest.close()
    }

    @Test
    fun `chapter completion continues active narration in next chapter`() = runTest {
        // Given
        val bookController = FakeBookController()
        val narrationController = FakeNarrationController()
        val classUnderTest = ReaderSyncCoordinator(bookController)
        classUnderTest.startNarration(backgroundScope, narrationController)
        runCurrent()

        // When
        narrationController.emitChapterCompleted("chapter-1.xhtml")
        runCurrent()

        // Then
        assertEquals(1, bookController.nextPageCalls)
        assertEquals("chapter-2.xhtml", narrationController.startedChapterHref)
        classUnderTest.close()
    }
}

private class FakeNarrationController : NarrationController {

    override val isPlaying: Flow<Boolean> = flowOf(false)
    override val isLoading: Flow<Boolean> = flowOf(false)
    override val chapterCompleted = MutableSharedFlow<String>(extraBufferCapacity = 1)

    var playedFragmentId: String? = null
        private set
    var playedChapterHref: String? = null
        private set
    var startedChapterHref: String? = null
        private set

    fun emitChapterCompleted(chapterHref: String) {
        chapterCompleted.tryEmit(chapterHref)
    }

    override fun togglePlayback() = Unit

    override fun setPlaybackSpeed(speed: Float) = Unit

    override fun skipForward() = Unit

    override fun skipBackward() = Unit

    override fun playFromSentence(fragmentId: String, chapterHref: String?) {
        playedFragmentId = fragmentId
        playedChapterHref = chapterHref
    }

    override fun playFromChapterStart(chapterHref: String) {
        startedChapterHref = chapterHref
    }

    override fun close() = Unit
}

private class FakeBookController : BookController {

    override val hasMediaOverlays: Boolean = false
    override val currentLocator = MutableStateFlow(locator("chapter-1.xhtml"))
    override val sentenceDoubleTapEvents = MutableSharedFlow<SentenceDoubleTapEvent>(
        extraBufferCapacity = 1,
    )

    var nextPageCalls: Int = 0
        private set

    fun emitDoubleTap(event: SentenceDoubleTapEvent) {
        sentenceDoubleTapEvents.tryEmit(event)
    }

    override fun goToNextPage() {
        nextPageCalls++
        currentLocator.value = locator("chapter-2.xhtml")
    }

    override fun goToPreviousPage() = Unit

    override fun goToChapter(href: String) = Unit

    override fun goToLocator(locator: LocatorState) = Unit

    override fun setSettings(settings: ReaderSettingsUiModel) = Unit

    override fun goToPosition(position: PositionUiModel) = Unit

    override suspend fun applyHighlightWithPageTurn(
        locator: LocatorState,
        sentenceDurationMs: Long,
    ) = Unit

    override suspend fun checkSentenceVisibility(elementId: String): SentenceVisibilityResult {
        return SentenceVisibilityResult.FULLY_VISIBLE
    }

    override suspend fun getChapterPageInfo() = null

    override suspend fun getVisibleSentenceId() = null

    override fun close() = Unit

    private companion object {
        fun locator(href: String) = LocatorState(
            href = href,
            type = "application/xhtml+xml",
            title = null,
            progression = null,
            position = null,
            totalProgression = null,
            fragments = null,
        )
    }
}
