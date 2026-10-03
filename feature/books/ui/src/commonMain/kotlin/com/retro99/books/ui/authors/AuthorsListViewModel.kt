package com.retro99.books.ui.authors

import androidx.lifecycle.viewModelScope
import com.github.michaelbull.result.onFailure
import com.github.michaelbull.result.onSuccess
import com.retro99.analytics.api.Analytics
import com.retro99.analytics.api.FeatureUsageAnalyticsEvent
import com.retro99.analytics.api.DiscoveryRoute
import com.retro99.analytics.api.DiscoveryDestination
import com.retro99.analytics.api.logFeatureUsage
import com.retro99.analytics.api.ProductAnalyticsEvent
import com.retro99.analytics.api.UsageFeature
import com.retro99.base.result.log
import com.retro99.base.ui.BaseViewModel
import com.retro99.books.domain.usecase.GetAuthorsUseCase
import com.retro99.books.ui.authors.model.AuthorListUiModel
import com.retro99.books.ui.authors.model.toListUiModel
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.onStart
import org.koin.core.annotation.InjectedParam
import org.koin.core.annotation.KoinViewModel
import org.koin.core.annotation.Provided

@KoinViewModel
class AuthorsListViewModel(
    @InjectedParam private val onNavigateToAuthorDetail: (author: AuthorListUiModel) -> Unit,
    @Provided private val getAuthorsUseCase: GetAuthorsUseCase,
    @Provided private val analytics: Analytics,
) : BaseViewModel<AuthorsListViewState, AuthorsListIntent>(AuthorsListViewState()) {

    init {
        observeAuthors()
    }

    override fun onIntent(intent: AuthorsListIntent) {
        when (intent) {
            AuthorsListIntent.OnScreenVisible -> analytics.logFeatureUsage(
                ProductAnalyticsEvent.FeatureExposed(UsageFeature.AuthorBrowsing, "authors", true),
            )
            AuthorsListIntent.OnRefresh -> observeAuthors()
            is AuthorsListIntent.OnAuthorClicked -> {
                analytics.logFeatureUsage(FeatureUsageAnalyticsEvent.DiscoverySelected(DiscoveryRoute.Author, DiscoveryDestination.Author))
                onNavigateToAuthorDetail(intent.author)
            }
        }
    }

    private fun observeAuthors() {
        getAuthorsUseCase()
            .onStart {
                updateState { it.copy(isLoading = true, error = null) }
            }
            .onEach { result ->
                result
                    .onSuccess { authors ->
                        updateState {
                            it.copy(
                                authors = authors.map { author -> author.toListUiModel() },
                                isLoading = false,
                                isRefreshing = false,
                                error = null,
                            )
                        }
                    }
                    .onFailure { error ->
                        error.log(analytics, "AuthorsListViewModel: Failed to load authors")
                        updateState {
                            it.copy(
                                isLoading = false,
                                isRefreshing = false,
                                error = error,
                            )
                        }
                    }
            }
            .launchIn(viewModelScope)
    }
}
