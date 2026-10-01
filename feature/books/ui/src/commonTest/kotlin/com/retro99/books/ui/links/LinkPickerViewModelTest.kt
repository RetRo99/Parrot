package com.retro99.books.ui.links

import com.retro99.books.domain.model.links.CopyKey
import com.retro99.books.domain.model.links.CopySource
import com.retro99.books.domain.model.links.sameSourceLinkError
import com.retro99.books.domain.usecase.GetBooksUseCase
import com.retro99.books.domain.usecase.LinkBooksUseCase
import com.retro99.books.domain.usecase.ObserveBookLinksUseCase
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
import kotlin.test.assertFalse
import kotlin.test.assertNull

@OptIn(ExperimentalCoroutinesApi::class)
class LinkPickerViewModelTest {

    private val links = FakeBookLinksRepository()
    private var backCount = 0

    @Test
    fun `the list excludes the current book's source`() = runViewModelTest {
        // Given
        val classUnderTest = viewModel()

        // When
        advanceUntilIdle()

        // Then
        val state = classUnderTest.currentViewState()
        assertFalse(state.isLoading)
        assertEquals(listOf("a1", "b1"), state.books.map { book -> book.uuid })
    }

    @Test
    fun `search narrows the list by title and author`() = runViewModelTest {
        // Given
        val classUnderTest = viewModel()
        advanceUntilIdle()

        // When
        classUnderTest.onIntent(LinkPickerIntent.OnSearchQueryChanged("tolkien"))

        // Then
        assertEquals(
            listOf("b1"),
            classUnderTest.currentViewState().filteredBooks.map { book -> book.uuid },
        )
    }

    @Test
    fun `picking a book links both copies and goes back`() = runViewModelTest {
        // Given
        val classUnderTest = viewModel()
        advanceUntilIdle()

        // When
        classUnderTest.onIntent(LinkPickerIntent.OnBookPicked(serverId = "audiobookshelf", "a1"))
        advanceUntilIdle()

        // Then
        assertEquals(
            listOf(
                CopyKey(CopySource.Storyteller, "s1") to CopyKey(CopySource.Audiobookshelf, "a1"),
            ),
            links.linked,
        )
        assertEquals(1, backCount)
        assertNull(classUnderTest.currentViewState().sameSourceError)
    }

    @Test
    fun `a same-source error is shown and the picker stays open`() = runViewModelTest {
        // Given
        links.linkError = sameSourceLinkError(CopySource.Library)
        val classUnderTest = viewModel()
        advanceUntilIdle()

        // When
        classUnderTest.onIntent(LinkPickerIntent.OnBookPicked(serverId = "audiobookshelf", "a1"))
        advanceUntilIdle()

        // Then
        assertEquals(CopySource.Library, classUnderTest.currentViewState().sameSourceError)
        assertEquals(0, backCount)

        // When
        classUnderTest.onIntent(LinkPickerIntent.OnErrorDismissed)

        // Then
        assertNull(classUnderTest.currentViewState().sameSourceError)
    }

    private fun viewModel(): LinkPickerViewModel {
        val provider = fakeRepositoryProvider(
            fakeServerBook("s1", ServerType.Storyteller, title = "The Hobbit"),
            fakeServerBook("s2", ServerType.Storyteller, title = "Dune"),
            fakeServerBook("a1", ServerType.Audiobookshelf, title = "Dune"),
            fakeServerBook("b1", ServerType.Local, title = "Hobbit", author = "J. R. R. Tolkien"),
        )
        return LinkPickerViewModel(
            serverId = "storyteller",
            bookUuid = "s1",
            onBack = { backCount++ },
            getBooksUseCase = GetBooksUseCase(provider),
            observeBookLinksUseCase = ObserveBookLinksUseCase(links),
            linkBooksUseCase = LinkBooksUseCase(links),
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
