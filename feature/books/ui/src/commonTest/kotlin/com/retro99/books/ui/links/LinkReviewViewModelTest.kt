package com.retro99.books.ui.links

import com.retro99.books.domain.model.links.CopyKey
import com.retro99.books.domain.model.links.CopySource
import com.retro99.books.domain.model.links.LinkDecisionType
import com.retro99.books.domain.usecase.DecideLinkUseCase
import com.retro99.books.domain.usecase.GetBooksUseCase
import com.retro99.books.domain.usecase.LinkBooksUseCase
import com.retro99.books.domain.usecase.ObserveLinkSuggestionsUseCase
import com.retro99.server.api.ServerType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

@OptIn(ExperimentalCoroutinesApi::class)
class LinkReviewViewModelTest {

    private val hobbitStoryteller = CopyKey(CopySource.Storyteller, "s1")
    private val hobbitLibrary = CopyKey(CopySource.Library, "b1")
    private val duneStoryteller = CopyKey(CopySource.Storyteller, "s2")
    private val duneAudiobookshelf = CopyKey(CopySource.Audiobookshelf, "a2")

    private val links = FakeBookLinksRepository()
    private val analytics = RecordingLinkAnalytics()

    @Test
    fun `the screen lists each suggestion with both books and its reason`() = runViewModelTest {
        // Given
        val classUnderTest = viewModel()

        // When
        advanceUntilIdle()

        // Then
        val state = classUnderTest.currentViewState()
        assertEquals(
            listOf(
                Triple("audiobookshelf:a2|storyteller:s2", true, "Dune"),
                Triple("library:b1|storyteller:s1", true, "Hobbit"),
                Triple("audiobookshelf:a3|storyteller:s3", false, "Emma"),
            ),
            state.suggestions.map { suggestion ->
                Triple(suggestion.pairKey, suggestion.isConfident, suggestion.first.title)
            },
        )
        assertEquals(2, state.confidentCount)
    }

    @Test
    fun `Link links both copies of that suggestion`() = runViewModelTest {
        // Given
        val classUnderTest = viewModel()
        advanceUntilIdle()

        // When
        classUnderTest.onIntent(LinkReviewIntent.OnLinkClicked("library:b1|storyteller:s1"))
        advanceUntilIdle()

        // Then
        assertEquals(listOf(hobbitLibrary to hobbitStoryteller), links.linked)
        assertEquals(emptyList(), links.decided)
        assertEquals(listOf("started", "succeeded"), analytics.events.map { it.parameters["outcome"] })
    }

    @Test
    fun `Not the same book records a never decision`() = runViewModelTest {
        // Given
        val classUnderTest = viewModel()
        advanceUntilIdle()

        // When
        classUnderTest.onIntent(
            LinkReviewIntent.OnNotSameBookClicked("library:b1|storyteller:s1"),
        )
        advanceUntilIdle()

        // Then
        assertEquals(
            listOf(Triple(hobbitLibrary, hobbitStoryteller, LinkDecisionType.Never)),
            links.decided,
        )
        assertEquals(emptyList(), links.linked)
    }

    @Test
    fun `Skip records a skip decision`() = runViewModelTest {
        // Given
        val classUnderTest = viewModel()
        advanceUntilIdle()

        // When
        classUnderTest.onIntent(LinkReviewIntent.OnSkipClicked("library:b1|storyteller:s1"))
        advanceUntilIdle()

        // Then
        assertEquals(
            listOf(Triple(hobbitLibrary, hobbitStoryteller, LinkDecisionType.Skip)),
            links.decided,
        )
    }

    @Test
    fun `Link all confident links only scores of 90 or more and reports the count`() =
        runViewModelTest {
            // Given: one of the two confident links fails
            links.failingLinks = setOf(duneAudiobookshelf to duneStoryteller)
            val classUnderTest = viewModel()
            advanceUntilIdle()
            assertNull(classUnderTest.currentViewState().linkedCount)

            // When
            classUnderTest.onIntent(LinkReviewIntent.OnLinkAllConfidentClicked)
            advanceUntilIdle()

            // Then: Emma (same title, different author) is review-only and is not linked
            assertEquals(
                listOf(
                    duneAudiobookshelf to duneStoryteller,
                    hobbitLibrary to hobbitStoryteller,
                ),
                links.linked,
            )
            assertEquals(1, classUnderTest.currentViewState().linkedCount)
            assertEquals(listOf("started", "partial"), analytics.events.map { it.parameters["outcome"] })
            assertEquals(listOf("bulk_link", "bulk_link"), analytics.events.map { it.parameters["usage_action"] })

            // When
            classUnderTest.onIntent(LinkReviewIntent.OnMessageDismissed)

            // Then
            assertNull(classUnderTest.currentViewState().linkedCount)
        }

    private fun TestScope.viewModel(): LinkReviewViewModel {
        val provider = fakeRepositoryProvider(
            fakeServerBook("s1", ServerType.Storyteller, "The Hobbit", isbn = "9780261102217"),
            fakeServerBook("b1", ServerType.Local, "Hobbit", isbn = "0-261-10221-4"),
            fakeServerBook("s2", ServerType.Storyteller, "Dune", author = "Frank Herbert"),
            fakeServerBook("a2", ServerType.Audiobookshelf, "Dune", author = "Frank Herbert"),
            fakeServerBook("s3", ServerType.Storyteller, "Emma", author = "Jane Austen"),
            fakeServerBook("a3", ServerType.Audiobookshelf, "Emma", author = "Someone Else"),
        )
        val suggestions = ObserveLinkSuggestionsUseCase(
            getBooksUseCase = GetBooksUseCase(provider, links),
            bookLinksRepository = links,
        ).also { useCase -> useCase.dispatcher = StandardTestDispatcher(testScheduler) }
        return LinkReviewViewModel(
            onBack = {},
            observeLinkSuggestionsUseCase = suggestions,
            linkBooksUseCase = LinkBooksUseCase(links),
            decideLinkUseCase = DecideLinkUseCase(links),
            analytics = analytics,
        )
    }

    private fun runViewModelTest(block: suspend TestScope.() -> Unit) = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        try {
            block()
        } finally {
            Dispatchers.resetMain()
        }
    }
}
