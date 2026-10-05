package com.retro99.books.ui.links

import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.runtime.snapshotFlow
import androidx.lifecycle.viewModelScope
import com.retro99.analytics.api.Analytics
import com.retro99.analytics.api.ProductOutcome
import com.retro99.analytics.api.UsageOperation
import com.retro99.analytics.api.UsageAction
import com.retro99.analytics.api.trackUsageOperation
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
import kotlinx.coroutines.Job
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
    @Provided private val analytics: Analytics,
) : BaseViewModel<LinkPickerViewState, LinkPickerIntent>(
    LinkPickerViewState(),
) {
    val searchFieldState = TextFieldState()

    private var currentBook: BookDomainModel? = null
    private var candidates: List<BookDomainModel> = emptyList()
    private var catalogueJob: Job? = null

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

            is LinkPickerIntent.OnBookPicked -> {
                val picked = candidates.firstOrNull { it.serverId == intent.serverId && it.uuid == intent.bookUuid }
                updateState { state -> state.copy(pendingBook = picked?.toUiModel(), error = null, sameSourceError = null, linkFailureMessage = null) }
            }

            LinkPickerIntent.OnConfirmLink -> viewState.value.pendingBook?.let { picked ->
                if (!viewState.value.isLinking) link(picked.serverId, picked.uuid)
            }
            LinkPickerIntent.OnDismissConfirmation -> if (!viewState.value.isLinking) {
                updateState { state -> state.copy(pendingBook = null, error = null, sameSourceError = null, linkFailureMessage = null) }
            }

            LinkPickerIntent.OnErrorDismissed ->
                updateState { state -> state.copy(sameSourceError = null, error = null) }
            LinkPickerIntent.OnRetry -> observeCandidates()
        }
    }

    private fun observeCandidates() {
        catalogueJob?.cancel()
        catalogueJob = combine(getBooksUseCase.observeCatalogue(), observeBookLinksUseCase()) { snapshot, links ->
            val books = snapshot.books
            val book = books.firstOrNull { candidate ->
                candidate.serverId == serverId && candidate.uuid == bookUuid
            }
            currentBook = book
            candidates = if (book == null) {
                emptyList()
            } else {
                linkPickerCandidates(book, books, links)
            }
            updateState { state -> state.copy(catalogueFailures = snapshot.failures) }
            candidates
        }
            .onEach { books ->
                updateState { state ->
                    state.copy(books = books.map { book -> book.toUiModel() }, currentBook = currentBook?.toUiModel(), isLoading = false)
                }
            }
            .launchIn(viewModelScope)
    }

    private fun link(pickedServerId: String, pickedUuid: String) {
        val book = currentBook
        val picked = candidates.firstOrNull { candidate ->
            candidate.serverId == pickedServerId && candidate.uuid == pickedUuid
        }
        if (book == null || picked == null) {
            updateState { state -> state.copy(linkFailureMessage = "These versions changed and can’t be linked now. Close this sheet and review the list.") }
            return
        }
        updateState { state -> state.copy(isLinking = true, error = null, sameSourceError = null) }
        viewModelScope.launch {
            analytics.trackUsageOperation(
                UsageOperation.Link, UsageAction.ManualLink, "link_picker",
                outcome = { if (it.isOk) ProductOutcome.Succeeded else ProductOutcome.Failed },
            ) { linkBooksUseCase(book.copyKey(), picked.copyKey()) }
                .onSuccess { onBack() }
                .onFailure { error ->
                    val source = error.repeatedLinkSource()
                    updateState { state ->
                        state.copy(
                            sameSourceError = source,
                            isLinking = false,
                            error = error.takeIf { source == null },
                        )
                    }
                }
        }
    }
}
