package com.retro99.saved.ui.library

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.navigationevent.NavigationEventInfo
import androidx.navigationevent.compose.NavigationBackHandler
import androidx.navigationevent.compose.rememberNavigationEventState
import com.retro99.base.ui.BaseScreen
import com.retro99.base.ui.compose.Ember
import com.retro99.base.ui.compose.EmberChevron
import com.retro99.base.ui.compose.EmberCover
import com.retro99.base.ui.compose.EmberGroupCard
import com.retro99.base.ui.compose.EmberSectionHeader
import com.retro99.books.domain.model.BookType
import com.retro99.saved.domain.model.SavedCounts
import com.retro99.saved.domain.model.SavedItemType
import com.retro99.saved.ui.SavedBookList
import com.retro99.saved.ui.SavedEmptyState
import com.retro99.saved.ui.SavedExportSheet
import com.retro99.saved.ui.SavedFilterChips
import com.retro99.saved.ui.SavedItemDetailSheet
import com.retro99.saved.ui.SavedItemRow
import com.retro99.saved.ui.SavedNoteEditorSheet
import com.retro99.saved.ui.SavedSyncText
import com.retro99.saved.ui.SavedUndoBar
import com.retro99.saved.ui.savedCountsText
import com.retro99.saved.ui.savedExportLabels
import com.retro99.translations.StringRes
import kotlinx.coroutines.delay
import org.jetbrains.compose.resources.stringResource
import org.koin.compose.viewmodel.koinViewModel
import org.koin.core.parameter.parametersOf
import resources.translations.general_back
import resources.translations.saved_bar_bookmark_removed
import resources.translations.saved_bar_highlight_removed
import resources.translations.saved_bar_undo
import resources.translations.saved_download_body
import resources.translations.saved_download_cancel
import resources.translations.saved_download_confirm
import resources.translations.saved_download_title
import resources.translations.saved_book_unavailable
import resources.translations.saved_library_by_book
import resources.translations.saved_library_latest
import resources.translations.saved_library_no_results
import resources.translations.saved_library_search
import resources.translations.saved_library_title
import resources.translations.saved_no_matching_items

@Composable
fun NotesHighlightsScreen(
    bookKey: String?,
    onBack: () -> Unit,
    onOpenReader: (String, String, BookType) -> Unit,
    onOpenBookDetail: (String, String) -> Unit,
    onSignIn: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: NotesHighlightsViewModel = koinViewModel {
        parametersOf(bookKey, onBack, onOpenReader, onOpenBookDetail)
    },
) {
    BaseScreen(viewModel, modifier) { state, dispatch ->
        val clipboard = LocalClipboardManager.current
        val labels = savedExportLabels()
        val unavailable = stringResource(StringRes.saved_book_unavailable)
        val snackbar = remember { SnackbarHostState() }
        NavigationBackHandler(
            state = rememberNavigationEventState(NavigationEventInfo.None),
            isBackEnabled = state.bookKey != null && bookKey == null,
            onBackCompleted = { dispatch(NotesHighlightsIntent.Back) },
        )
        LaunchedEffect(state.copyText) {
            state.copyText?.let {
                clipboard.setText(AnnotatedString(it))
                dispatch(NotesHighlightsIntent.CopyHandled)
            }
        }
        LaunchedEffect(state.showUnavailable) {
            if (state.showUnavailable) {
                snackbar.showSnackbar(unavailable)
                dispatch(NotesHighlightsIntent.DismissUnavailable)
            }
        }
        LaunchedEffect(state.removed) {
            if (state.removed != null) {
                delay(6_000)
                dispatch(NotesHighlightsIntent.DismissRemoved)
            }
        }
        Scaffold(
            containerColor = Ember.colors.bg,
            snackbarHost = { SnackbarHost(snackbar) },
            topBar = {
                Row(Modifier.fillMaxWidth().statusBarsPadding().padding(end = 24.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = { dispatch(NotesHighlightsIntent.Back) }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack,
                            stringResource(StringRes.general_back), tint = Ember.colors.ink)
                    }
                    Text(if (state.bookKey == null) stringResource(StringRes.saved_library_title) else state.bookTitle,
                        style = Ember.type.cardTitle, color = Ember.colors.ink,
                        maxLines = 2, overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.semantics { heading() })
                }
            },
            bottomBar = {
                if (state.removed != null) SavedUndoBar(
                    message = stringResource(if (state.removed.type == SavedItemType.Bookmark)
                        StringRes.saved_bar_bookmark_removed else StringRes.saved_bar_highlight_removed),
                    undoLabel = stringResource(StringRes.saved_bar_undo),
                    onUndo = { dispatch(NotesHighlightsIntent.UndoDelete) },
                )
            },
        ) { padding ->
            Column(Modifier.fillMaxSize().padding(padding)) {
                OutlinedTextField(
                    value = state.query,
                    onValueChange = { dispatch(NotesHighlightsIntent.Search(it)) },
                    placeholder = { Text(stringResource(StringRes.saved_library_search)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp),
                )
                if (state.bookKey != null) {
                    SavedBookList(
                        items = state.bookItems,
                        query = state.query,
                        filter = state.filter,
                        onFilter = { dispatch(NotesHighlightsIntent.SetFilter(it)) },
                        isEditing = state.isEditing,
                        syncState = state.syncState,
                        onOpen = { dispatch(NotesHighlightsIntent.OpenItem(it)) },
                        onDetails = { dispatch(NotesHighlightsIntent.ShowDetail(it.id)) },
                        onDelete = { dispatch(NotesHighlightsIntent.Delete(it)) },
                        onToggleEdit = { dispatch(NotesHighlightsIntent.ToggleEditing) },
                        onExport = { dispatch(NotesHighlightsIntent.OpenExport) },
                        onSignIn = onSignIn,
                        modifier = Modifier.weight(1f),
                    )
                } else {
                    SavedFilterChips(state.filter, { dispatch(NotesHighlightsIntent.SetFilter(it)) },
                        Modifier.padding(horizontal = 20.dp, vertical = 8.dp))
                    if (state.items.isEmpty()) {
                        SavedEmptyState(null, Modifier.weight(1f))
                    } else {
                        LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(bottom = 20.dp)) {
                            if (state.latest.isEmpty()) item {
                                Text(if (state.query.isBlank()) stringResource(StringRes.saved_no_matching_items)
                                    else stringResource(StringRes.saved_library_no_results, state.query),
                                    color = Ember.colors.ink2, modifier = Modifier.padding(24.dp))
                            }
                            item { EmberSectionHeader(stringResource(StringRes.saved_library_latest)) }
                            items(state.latest, key = { "latest-${it.id}" }) { item ->
                                EmberGroupCard {
                                    val info = state.books[item.book.key]
                                    Row(Modifier.fillMaxWidth().clickable {
                                        dispatch(NotesHighlightsIntent.OpenBook(item.book.key))
                                    }.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                                        EmberCover(info?.coverUrl, item.book.key, null, Modifier.size(32.dp, 48.dp))
                                        Text(info?.title ?: item.book.title.orEmpty(), color = Ember.colors.ink2,
                                            style = Ember.type.meta, maxLines = 2,
                                            modifier = Modifier.padding(start = 12.dp).weight(1f))
                                        EmberChevron()
                                    }
                                    SavedItemRow(item, false,
                                        onClick = { dispatch(NotesHighlightsIntent.OpenItem(item)) },
                                        onLongClick = { dispatch(NotesHighlightsIntent.ShowDetail(item.id)) },
                                        onDelete = { dispatch(NotesHighlightsIntent.Delete(item)) },
                                        modifier = Modifier.padding(horizontal = 16.dp), showDivider = false)
                                }
                                Spacer(Modifier.height(8.dp))
                            }
                            item { EmberSectionHeader(stringResource(StringRes.saved_library_by_book)) }
                            items(state.byBook, key = { "book-${it.key}" }) { book ->
                                EmberGroupCard {
                                    Row(Modifier.fillMaxWidth().clickable {
                                        dispatch(NotesHighlightsIntent.OpenBook(book.key))
                                    }.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                                        EmberCover(book.coverUrl, book.key, null, Modifier.size(40.dp, 60.dp))
                                        Column(Modifier.weight(1f).padding(horizontal = 14.dp)) {
                                            Text(book.title, style = Ember.type.bookTitle, color = Ember.colors.ink,
                                                maxLines = 2, overflow = TextOverflow.Ellipsis)
                                            Text(savedCountsText(book.counts), style = Ember.type.meta, color = Ember.colors.ink2)
                                        }
                                        EmberChevron()
                                    }
                                }
                                Spacer(Modifier.height(8.dp))
                            }
                        }
                    }
                    SavedSyncText(state.syncState, onSignIn, Modifier.padding(horizontal = 24.dp, vertical = 8.dp))
                }
            }
        }
        state.detail?.let { item ->
            SavedItemDetailSheet(
                item = item,
                onDismiss = { dispatch(NotesHighlightsIntent.CloseDetail) },
                onColor = { dispatch(NotesHighlightsIntent.SetColor(item.id, it)) },
                onEditNote = { dispatch(NotesHighlightsIntent.EditNote(item.id)) },
                onCopy = { dispatch(NotesHighlightsIntent.CopyItem(item)) },
                onShare = { dispatch(NotesHighlightsIntent.ShareItem(item)) },
                onRemove = { dispatch(NotesHighlightsIntent.Delete(item)) },
            )
        }
        state.noteEditor?.let { item ->
            SavedNoteEditorSheet(
                item = item,
                onSave = { dispatch(NotesHighlightsIntent.SaveNote(item.id, it)) },
                onDismiss = { dispatch(NotesHighlightsIntent.CloseNote) },
            )
        }
        state.downloadPrompt?.let { prompt ->
            AlertDialog(
                onDismissRequest = { dispatch(NotesHighlightsIntent.DismissDownload) },
                title = { Text(stringResource(StringRes.saved_download_title)) },
                text = { Text(stringResource(StringRes.saved_download_body, prompt.title)) },
                confirmButton = { TextButton(onClick = { dispatch(NotesHighlightsIntent.ConfirmDownload) }) {
                    Text(stringResource(StringRes.saved_download_confirm))
                } },
                dismissButton = { TextButton(onClick = { dispatch(NotesHighlightsIntent.DismissDownload) }) {
                    Text(stringResource(StringRes.saved_download_cancel))
                } },
                containerColor = Ember.colors.surface,
            )
        }
        if (state.isExportVisible) SavedExportSheet(
            counts = SavedCounts.of(state.bookItems),
            onShareText = { dispatch(NotesHighlightsIntent.Export(ExportFormat.ShareText, labels)) },
            onCopyAll = { dispatch(NotesHighlightsIntent.Export(ExportFormat.CopyAll, labels)) },
            onSaveMarkdown = { dispatch(NotesHighlightsIntent.Export(ExportFormat.Markdown, labels)) },
            onDismiss = { dispatch(NotesHighlightsIntent.CloseExport) },
        )
    }
}
