package com.retro99.books.ui.links

import androidx.lifecycle.viewModelScope
import com.github.michaelbull.result.onFailure
import com.retro99.base.ui.BaseViewModel
import com.retro99.books.domain.model.links.LinkDecisionType
import com.retro99.books.domain.model.links.LinkSuggestion
import com.retro99.books.domain.model.links.repeatedLinkSource
import com.retro99.books.domain.usecase.DecideLinkUseCase
import com.retro99.books.domain.usecase.LinkBooksUseCase
import com.retro99.books.domain.usecase.ObserveLinkSuggestionsUseCase
import com.retro99.books.ui.model.toUiModel
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import org.koin.core.annotation.InjectedParam
import org.koin.core.annotation.KoinViewModel
import org.koin.core.annotation.Provided

/**
 * "Same book?": the user reviews suggested pairs. Nothing is linked unless they choose
 * Link, or "Link all confident matches".
 */
@KoinViewModel
class LinkReviewViewModel(
    @InjectedParam private val onBack: () -> Unit,
    @Provided private val observeLinkSuggestionsUseCase: ObserveLinkSuggestionsUseCase,
    @Provided private val linkBooksUseCase: LinkBooksUseCase,
    @Provided private val decideLinkUseCase: DecideLinkUseCase,
) : BaseViewModel<LinkReviewViewState, LinkReviewIntent>(
    LinkReviewViewState(),
) {
    private var suggestions: List<LinkSuggestion> = emptyList()

    init {
        observeLinkSuggestionsUseCase()
            .onEach { current ->
                suggestions = current
                updateState { state ->
                    state.copy(
                        suggestions = current.map { suggestion -> suggestion.toUiModel() },
                        isLoading = false,
                    )
                }
            }
            .launchIn(viewModelScope)
    }

    override fun onIntent(intent: LinkReviewIntent) {
        when (intent) {
            LinkReviewIntent.OnBackClicked -> onBack()
            is LinkReviewIntent.OnLinkClicked -> link(intent.pairKey)
            is LinkReviewIntent.OnNotSameBookClicked ->
                decide(intent.pairKey, LinkDecisionType.Never)
            is LinkReviewIntent.OnSkipClicked -> decide(intent.pairKey, LinkDecisionType.Skip)
            LinkReviewIntent.OnLinkAllConfidentClicked -> linkAllConfident()
            LinkReviewIntent.OnMessageDismissed -> updateState { state ->
                state.copy(linkedCount = null, sameSourceError = null, error = null)
            }
        }
    }

    private fun link(pairKey: String) {
        val suggestion = suggestion(pairKey) ?: return
        viewModelScope.launch {
            linkBooksUseCase(suggestion.first.key, suggestion.second.key).onFailure { error ->
                val source = error.repeatedLinkSource()
                updateState { state ->
                    state.copy(sameSourceError = source, error = error.takeIf { source == null })
                }
            }
        }
    }

    private fun decide(pairKey: String, decision: LinkDecisionType) {
        val suggestion = suggestion(pairKey) ?: return
        viewModelScope.launch {
            decideLinkUseCase(suggestion.first.key, suggestion.second.key, decision)
                .onFailure { error -> updateState { state -> state.copy(error = error) } }
        }
    }

    /** Links every suggestion scoring 90 or more; the rest stay for one-by-one review. */
    private fun linkAllConfident() {
        val confident = suggestions.filter { suggestion -> suggestion.isConfident }
        if (confident.isEmpty()) return
        viewModelScope.launch {
            // One at a time: an earlier link can make a later one invalid (a source would
            // repeat), and that one is then simply not counted.
            val linkedCount = confident.count { suggestion ->
                linkBooksUseCase(suggestion.first.key, suggestion.second.key).isOk
            }
            updateState { state -> state.copy(linkedCount = linkedCount) }
        }
    }

    private fun suggestion(pairKey: String): LinkSuggestion? =
        suggestions.firstOrNull { suggestion -> suggestion.pairKey == pairKey }
}
