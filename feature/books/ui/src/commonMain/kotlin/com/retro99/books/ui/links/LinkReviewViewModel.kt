package com.retro99.books.ui.links

import androidx.lifecycle.viewModelScope
import com.retro99.analytics.api.Analytics
import com.retro99.analytics.api.ProductOutcome
import com.retro99.analytics.api.UsageOperation
import com.retro99.analytics.api.UsageAction
import com.retro99.analytics.api.trackUsageOperation
import com.github.michaelbull.result.onFailure
import com.github.michaelbull.result.getError
import com.retro99.base.ui.BaseViewModel
import com.retro99.books.domain.model.links.LinkDecisionType
import com.retro99.books.domain.model.links.CopySource
import com.retro99.books.domain.model.links.LinkSuggestion
import com.retro99.books.domain.model.links.repeatedLinkSource
import com.retro99.books.domain.usecase.DecideLinkUseCase
import com.retro99.books.domain.usecase.LinkBooksUseCase
import com.retro99.books.domain.usecase.ObserveLinkSuggestionsUseCase
import com.retro99.books.ui.model.toUiModel
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import kotlinx.coroutines.Job
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
    @Provided private val analytics: Analytics,
) : BaseViewModel<LinkReviewViewState, LinkReviewIntent>(
    LinkReviewViewState(),
) {
    private var suggestions: List<LinkSuggestion> = emptyList()
    private var reviewJob: Job? = null

    init {
        observeReview()
    }

    private fun observeReview() {
        reviewJob?.cancel()
        reviewJob = observeLinkSuggestionsUseCase.observeReview()
            .onEach { snapshot ->
                val current = snapshot.suggestions
                suggestions = current
                updateState { state ->
                    state.copy(
                        suggestions = current.map { suggestion -> suggestion.toUiModel() },
                        isLoading = false,
                        catalogueFailures = snapshot.failures,
                    )
                }
            }
            .launchIn(viewModelScope)
    }

    override fun onIntent(intent: LinkReviewIntent) {
        when (intent) {
            LinkReviewIntent.OnBackClicked -> onBack()
            LinkReviewIntent.OnRetry -> observeReview()
            is LinkReviewIntent.OnLinkClicked -> link(intent.pairKey)
            is LinkReviewIntent.OnNotSameBookClicked ->
                decide(intent.pairKey, LinkDecisionType.Never)
            is LinkReviewIntent.OnSkipClicked -> decide(intent.pairKey, LinkDecisionType.Skip)
            LinkReviewIntent.OnLinkAllConfidentClicked -> linkAllConfident()
            LinkReviewIntent.OnMessageDismissed -> updateState { state ->
                state.copy(linkedCount = null, sameSourceError = null, error = null, bulkFailures = emptyList())
            }
        }
    }

    private fun link(pairKey: String) {
        val suggestion = suggestion(pairKey) ?: return
        viewModelScope.launch {
            analytics.trackUsageOperation(
                UsageOperation.Link, UsageAction.SuggestedLink, "link_review",
                outcome = { if (it.isOk) ProductOutcome.Succeeded else ProductOutcome.Failed },
            ) { linkBooksUseCase(suggestion.first.key, suggestion.second.key) }.onFailure { error ->
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
            analytics.trackUsageOperation(
                UsageOperation.Link, if (decision == LinkDecisionType.Never) UsageAction.Reject else UsageAction.Skip, "link_review",
                outcome = { if (it.isOk) ProductOutcome.Succeeded else ProductOutcome.Failed },
            ) { decideLinkUseCase(suggestion.first.key, suggestion.second.key, decision) }
                .onFailure { error -> updateState { state -> state.copy(error = error) } }
        }
    }

    /** Links verified ISBN matches sequentially, reporting every failed pair. */
    private fun linkAllConfident() {
        val confident = suggestions.filter { suggestion -> suggestion.isConfident }
        if (confident.isEmpty()) return
        viewModelScope.launch {
            val failures = mutableListOf<String>()
            // One at a time: an earlier link can make a later one invalid (a source would
            // repeat). Keep the reason for each pair rather than silently dropping it.
            val linkedCount = analytics.trackUsageOperation(
                UsageOperation.Link, UsageAction.BulkLink, "link_review", confident.size,
                outcome = { count -> when (count) {
                    confident.size -> ProductOutcome.Succeeded
                    0 -> ProductOutcome.Failed
                    else -> ProductOutcome.Partial
                } },
            ) {
                confident.count { suggestion ->
                    val result = linkBooksUseCase(suggestion.first.key, suggestion.second.key)
                    result.getError()?.let { error ->
                        val reason = error.repeatedLinkSource()?.let { source ->
                            val name = when (source) {
                                CopySource.Library -> "library"
                                CopySource.Storyteller -> "Storyteller"
                                CopySource.Audiobookshelf -> "Audiobookshelf"
                            }
                            "already linked to another $name version"
                        } ?: "couldn’t save the link; try again"
                        failures += "${suggestion.first.title} / ${suggestion.second.title}: $reason"
                    }
                    result.isOk
                }
            }
            updateState { state -> state.copy(linkedCount = linkedCount, bulkFailures = failures) }
        }
    }

    private fun suggestion(pairKey: String): LinkSuggestion? =
        suggestions.firstOrNull { suggestion -> suggestion.pairKey == pairKey }
}
