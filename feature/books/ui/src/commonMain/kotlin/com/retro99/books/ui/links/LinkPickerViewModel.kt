package com.retro99.books.ui.links

import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.runtime.snapshotFlow
import androidx.lifecycle.viewModelScope
import com.github.michaelbull.result.getOrElse
import com.github.michaelbull.result.onFailure
import com.github.michaelbull.result.onSuccess
import com.retro99.base.ui.BaseViewModel
import com.retro99.books.domain.model.BookDomainModel
import com.retro99.books.domain.model.links.copyKey
import com.retro99.books.domain.model.links.linkPickerCandidates
import com.retro99.books.domain.model.links.repeatedLinkSource
import com.retro99.books.domain.usecase.GetBooksUseCase
import com.retro99.books.domain.usecase.LinkBooksUseCase
import com.retro99.books.domain.usecase.ObserveBookLinksUseCase
import com.retro99.books.ui.model.toUiModel
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import org.koin.core.annotation.InjectedParam
import org.koin.core.annotation.KoinViewModel
import org.koin.core.annotation.Provided

/** "Same book as…": picks a book on another source to link the current book to. */
@KoinViewModel
class LinkPickerViewModel(
    @InjectedParam private val serverId: String,
    @InjectedParam private val bookUuid: String,
    @InjectedParam private val onBack: () -> Unit,
    @Provided private val getBooksUseCase: GetBooksUseCase,
    @Provided private val observeBookLinksUseCase: ObserveBookLinksUseCase,
    @Provided private val linkBooksUseCase: LinkBooksUseCase,
) : BaseViewModel<LinkPickerViewState, LinkPickerIntent>(
    LinkPickerViewState(),
) {
    val searchFieldState = TextFieldState()

    private var currentBook: BookDomainModel? = null
    private var candidates: List<BookDomainModel> = emptyList()

    init {
        observeCandidates()
        snapshotFlow { searchFieldState.text.toString() }
            .onEach { query -> onIntent(LinkPickerIntent.OnSearchQueryChanged(query)) }
            .launchIn(viewModelScope)
    }

    override fun onIntent(intent: LinkPickerIntent) {
        when (intent) {
            LinkPickerIntent.OnBackClicked -> onBack()

            is LinkPickerIntent.OnSearchQueryChanged ->
                updateState { state -> state.copy(searchQuery = intent.query) }

            is LinkPickerIntent.OnBookPicked -> link(intent.serverId, intent.bookUuid)

            LinkPickerIntent.OnErrorDismissed ->
                updateState { state -> state.copy(sameSourceError = null, error = null) }
        }
    }

    private fun observeCandidates() {
        combine(getBooksUseCase(), observeBookLinksUseCase()) { booksResult, links ->
            val books = booksResult.getOrElse { emptyList() }
            val book = books.firstOrNull { candidate ->
                candidate.serverId == serverId && candidate.uuid == bookUuid
            }
            currentBook = book
            candidates = if (book == null) emptyList() else linkPickerCandidates(book, books, links)
            candidates
        }
            .onEach { books ->
                updateState { state ->
                    state.copy(books = books.map { book -> book.toUiModel() }, isLoading = false)
                }
            }
            .launchIn(viewModelScope)
    }

    private fun link(pickedServerId: String, pickedUuid: String) {
        val book = currentBook ?: return
        val picked = candidates.firstOrNull { candidate ->
            candidate.serverId == pickedServerId && candidate.uuid == pickedUuid
        } ?: return
        viewModelScope.launch {
            linkBooksUseCase(book.copyKey(), picked.copyKey())
                .onSuccess { onBack() }
                .onFailure { error ->
                    val source = error.repeatedLinkSource()
                    updateState { state ->
                        state.copy(
                            sameSourceError = source,
                            error = error.takeIf { source == null },
                        )
                    }
                }
        }
    }
}
