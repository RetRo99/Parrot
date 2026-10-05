package com.retro99.books.ui.links

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.material.icons.Icons
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
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
import com.retro99.books.ui.components.BookItemCard
import com.retro99.books.ui.components.BookSearchBar
import com.retro99.translations.StringRes
import org.jetbrains.compose.resources.stringResource
import org.koin.compose.viewmodel.koinViewModel
import org.koin.core.parameter.parametersOf
import resources.translations.general_back
import resources.translations.general_ok
import resources.translations.link_error_same_source
import resources.translations.link_picker_title

/** "Same book as…": lists books on other sources; picking one links it to the book. */
@Composable
fun LinkPickerScreen(
    serverId: String,
    bookUuid: String,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: LinkPickerViewModel = koinViewModel {
        parametersOf(serverId, bookUuid, onBack)
    },
) {
    BaseScreen(
        modifier = modifier,
        viewModel = viewModel,
    ) { viewState, intentDispatcher ->
        when {
            viewState.isLoading -> LoadingScreen()
            else -> LinkPickerScreenContent(
                viewState = viewState,
                searchFieldState = viewModel.searchFieldState,
                intentDispatcher = intentDispatcher,
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun LinkPickerScreenContent(
    viewState: LinkPickerViewState,
    searchFieldState: TextFieldState,
    intentDispatcher: IntentDispatcher<LinkPickerIntent>,
    modifier: Modifier = Modifier,
) {
    val errorMessage = when {
        viewState.linkFailureMessage != null -> viewState.linkFailureMessage
        viewState.sameSourceError != null -> stringResource(
            StringRes.link_error_same_source,
            viewState.sameSourceError.label(),
        )
        viewState.error != null -> stringResource(viewState.error.toStringRes())
        else -> null
    }
    if (viewState.pendingBook != null && viewState.currentBook != null) {
        LinkConfirmationSheet(viewState.currentBook, viewState.pendingBook, viewState.isLinking, errorMessage,
            onDismiss = { intentDispatcher(LinkPickerIntent.OnDismissConfirmation) },
            onConfirm = { intentDispatcher(LinkPickerIntent.OnConfirmLink) })
    } else if (errorMessage != null) {
        EmberDialog(
            onDismissRequest = { intentDispatcher(LinkPickerIntent.OnErrorDismissed) },
            title = "",
            body = AnnotatedString(errorMessage),
            actions = listOf(
                EmberDialogAction(
                    label = stringResource(StringRes.general_ok),
                    style = EmberDialogActionStyle.Neutral,
                    onClick = { intentDispatcher(LinkPickerIntent.OnErrorDismissed) },
                ),
            ),
        )
    }

    Scaffold(
        topBar = {
            EmberTopBar(
                title = stringResource(StringRes.link_picker_title),
                onBack = { intentDispatcher(LinkPickerIntent.OnBackClicked) },
            )
        },
        modifier = modifier,
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues),
        ) {
            BookSearchBar(
                searchFieldState = searchFieldState,
                isVisible = false,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
            )
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(vertical = 8.dp),
            ) {
                if (viewState.catalogueFailures.isNotEmpty()) item("catalogue-error") {
                    Column(Modifier.padding(16.dp)) {
                        Text("Couldn’t load versions from ${viewState.catalogueFailures.size} source(s). Some versions may be missing.")
                        androidx.compose.material3.TextButton(onClick = { intentDispatcher(LinkPickerIntent.OnRetry) }) {
                            Text("Try again")
                        }
                    }
                }
                items(
                    items = viewState.filteredBooks,
                    key = { book -> "${book.serverId}:${book.uuid}" },
                ) { book ->
                    BookItemCard(
                        book = book,
                        isFavorite = false,
                        onClick = {
                            intentDispatcher(
                                LinkPickerIntent.OnBookPicked(book.serverId, book.uuid),
                            )
                        },
                        onFavoriteClick = {},
                        showFavorite = false,
                        highlightQuery = viewState.searchQuery.trim(),
                    )
                }
            }
        }
    }
}
