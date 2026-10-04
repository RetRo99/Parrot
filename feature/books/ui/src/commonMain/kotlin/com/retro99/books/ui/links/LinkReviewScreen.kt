package com.retro99.books.ui.links

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.retro99.base.ui.BaseScreen
import com.retro99.base.ui.IntentDispatcher
import com.retro99.base.ui.LoadingScreen
import com.retro99.base.ui.compose.EmberDialog
import com.retro99.base.ui.compose.EmberDialogAction
import com.retro99.base.ui.compose.EmberDialogActionStyle
import com.retro99.base.ui.compose.EmberTopBar
import com.retro99.books.domain.model.links.SuggestionReason
import com.retro99.books.ui.components.HomeBadge
import com.retro99.books.ui.model.LinkSuggestionUiModel
import com.retro99.books.ui.model.SuggestedBookUiModel
import com.retro99.translations.StringRes
import org.jetbrains.compose.resources.stringResource
import org.koin.compose.viewmodel.koinViewModel
import org.koin.core.parameter.parametersOf
import resources.translations.general_back
import resources.translations.general_ok
import resources.translations.link_error_same_source
import resources.translations.link_not_same_book
import resources.translations.link_reason_identifier
import resources.translations.link_reason_title_author
import resources.translations.link_review_link
import resources.translations.link_review_link_all_confident
import resources.translations.link_review_linked_count
import resources.translations.link_review_skip
import resources.translations.link_review_title

/** "Same book?": suggested pairs for the user to link, reject or skip. Plain rows for now. */
@Composable
fun LinkReviewScreen(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: LinkReviewViewModel = koinViewModel { parametersOf(onBack) },
) {
    BaseScreen(
        modifier = modifier,
        viewModel = viewModel,
    ) { viewState, intentDispatcher ->
        when {
            viewState.isLoading -> LoadingScreen()
            else -> LinkReviewScreenContent(
                viewState = viewState,
                intentDispatcher = intentDispatcher,
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun LinkReviewScreenContent(
    viewState: LinkReviewViewState,
    intentDispatcher: IntentDispatcher<LinkReviewIntent>,
    modifier: Modifier = Modifier,
) {
    val message = when {
        viewState.linkedCount != null ->
            stringResource(StringRes.link_review_linked_count, viewState.linkedCount)
        viewState.sameSourceError != null -> stringResource(
            StringRes.link_error_same_source,
            viewState.sameSourceError.label(),
        )
        viewState.error != null -> stringResource(viewState.error.toStringRes())
        else -> null
    }
    if (message != null) {
        EmberDialog(
            onDismissRequest = { intentDispatcher(LinkReviewIntent.OnMessageDismissed) },
            title = "",
            body = AnnotatedString(message),
            actions = listOf(
                EmberDialogAction(
                    label = stringResource(StringRes.general_ok),
                    style = EmberDialogActionStyle.Neutral,
                    onClick = { intentDispatcher(LinkReviewIntent.OnMessageDismissed) },
                ),
            ),
        )
    }

    Scaffold(
        topBar = {
            EmberTopBar(
                title = stringResource(StringRes.link_review_title),
                onBack = { intentDispatcher(LinkReviewIntent.OnBackClicked) },
            )
        },
        modifier = modifier,
    ) { paddingValues ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
        ) {
            if (viewState.confidentCount > 0) {
                item(key = "link-all") {
                    Button(
                        onClick = { intentDispatcher(LinkReviewIntent.OnLinkAllConfidentClicked) },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(
                            stringResource(
                                StringRes.link_review_link_all_confident,
                                viewState.confidentCount,
                            ),
                        )
                    }
                }
            }
            items(
                items = viewState.suggestions,
                key = { suggestion -> suggestion.pairKey },
            ) { suggestion ->
                SuggestionRow(
                    suggestion = suggestion,
                    onLink = {
                        intentDispatcher(LinkReviewIntent.OnLinkClicked(suggestion.pairKey))
                    },
                    onNotSameBook = {
                        intentDispatcher(LinkReviewIntent.OnNotSameBookClicked(suggestion.pairKey))
                    },
                    onSkip = {
                        intentDispatcher(LinkReviewIntent.OnSkipClicked(suggestion.pairKey))
                    },
                )
                HorizontalDivider()
            }
        }
    }
}

@Composable
private fun SuggestionRow(
    suggestion: LinkSuggestionUiModel,
    onLink: () -> Unit,
    onNotSameBook: () -> Unit,
    onSkip: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val reason = when (suggestion.reason) {
        SuggestionReason.IdentifierMatch -> StringRes.link_reason_identifier
        SuggestionReason.TitleAndAuthor -> StringRes.link_reason_title_author
    }
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        SuggestedBook(book = suggestion.first)
        SuggestedBook(book = suggestion.second)
        Text(
            text = stringResource(reason),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TextButton(onClick = onLink) {
                Text(stringResource(StringRes.link_review_link))
            }
            TextButton(onClick = onNotSameBook) {
                Text(stringResource(StringRes.link_not_same_book))
            }
            TextButton(onClick = onSkip) {
                Text(stringResource(StringRes.link_review_skip))
            }
        }
    }
}

@Composable
private fun SuggestedBook(
    book: SuggestedBookUiModel,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = book.title,
                style = MaterialTheme.typography.bodyLarge,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            if (book.authors.isNotEmpty()) {
                Text(
                    text = book.authors.joinToString(", "),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        HomeBadge(home = book.home)
    }
}
