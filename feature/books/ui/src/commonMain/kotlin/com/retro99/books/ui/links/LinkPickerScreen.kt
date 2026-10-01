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
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.retro99.base.ui.BaseScreen
import com.retro99.base.ui.IntentDispatcher
import com.retro99.base.ui.LoadingScreen
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
        viewState.sameSourceError != null -> stringResource(
            StringRes.link_error_same_source,
            viewState.sameSourceError.label(),
        )
        viewState.error != null -> stringResource(viewState.error.toStringRes())
        else -> null
    }
    if (errorMessage != null) {
        AlertDialog(
            onDismissRequest = { intentDispatcher(LinkPickerIntent.OnErrorDismissed) },
            text = { Text(errorMessage) },
            confirmButton = {
                TextButton(onClick = { intentDispatcher(LinkPickerIntent.OnErrorDismissed) }) {
                    Text(stringResource(StringRes.general_ok))
                }
            },
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = stringResource(StringRes.link_picker_title),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                },
                navigationIcon = {
                    IconButton(onClick = { intentDispatcher(LinkPickerIntent.OnBackClicked) }) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(StringRes.general_back),
                        )
                    }
                },
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
