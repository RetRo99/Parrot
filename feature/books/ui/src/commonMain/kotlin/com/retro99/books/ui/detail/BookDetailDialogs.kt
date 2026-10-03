package com.retro99.books.ui.detail

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import com.retro99.analytics.api.UsageOperation
import com.retro99.base.ui.IntentDispatcher
import com.retro99.base.ui.compose.Ember
import com.retro99.books.ui.components.LinkedResumeDialog
import com.retro99.books.ui.components.PositionConflictDialog
import com.retro99.books.ui.components.toUiModel
import com.retro99.books.ui.links.UnlinkCopyConfirmationDialog
import com.retro99.books.ui.model.BookUiModel
import com.retro99.translations.StringRes
import org.jetbrains.compose.resources.stringResource
import resources.translations.general_cancel
import resources.translations.books_delete_cache_title
import resources.translations.books_delete_cache_message
import resources.translations.books_delete_cache_confirm
import resources.translations.books_delete_local_title
import resources.translations.books_delete_local_message
import resources.translations.books_delete_local_confirm
import resources.translations.library_remove_from_parrot_title
import resources.translations.library_remove_from_parrot_message
import resources.translations.library_remove_from_parrot_message_keep_local
import resources.translations.library_remove_from_parrot_confirm
import resources.translations.cloud_backup_replace_title
import resources.translations.cloud_backup_replace_message
import resources.translations.cloud_backup_replace_button
import resources.translations.cloud_backup_title
import resources.translations.cloud_backup_confirm
import resources.translations.cloud_backup_attestation_checkbox
import resources.translations.resume_linked_compare

@Composable
internal fun BookDetailDialogs(
    state: BookDetailViewState,
    dispatch: IntentDispatcher<BookDetailIntent>,
) {
    val book = state.book ?: return
    state.deleteConfirmationBookType?.let { type ->
        DetailConfirmation(
            stringResource(StringRes.books_delete_cache_title),
            stringResource(StringRes.books_delete_cache_message, mediaLabel(type)),
            stringResource(StringRes.books_delete_cache_confirm),
            onConfirm = { dispatch(BookDetailIntent.OnDeleteCacheConfirmed) },
            onDismiss = { dispatch(BookDetailIntent.OnDeleteCacheDismissed) },
        )
    }
    if (state.showDeleteLocalBookConfirmation) DetailConfirmation(
        stringResource(StringRes.books_delete_local_title),
        stringResource(StringRes.books_delete_local_message, book.title),
        stringResource(StringRes.books_delete_local_confirm),
        onConfirm = { dispatch(BookDetailIntent.OnDeleteLocalBookConfirmed) },
        onDismiss = { dispatch(BookDetailIntent.OnDeleteLocalBookDismissed) },
    )
    if (state.showRemoveFromParrotConfirmation) DetailConfirmation(
        stringResource(StringRes.library_remove_from_parrot_title),
        stringResource(if ((book as? BookUiModel.LibraryBook)?.hasDeviceCopy == true)
            StringRes.library_remove_from_parrot_message_keep_local
            else StringRes.library_remove_from_parrot_message, book.title),
        stringResource(StringRes.library_remove_from_parrot_confirm),
        onConfirm = { dispatch(BookDetailIntent.OnRemoveFromParrotConfirmed) },
        onDismiss = { dispatch(BookDetailIntent.OnRemoveFromParrotDismissed) },
    )
    if (state.replaceBackupConfirmationTransferId != null) DetailConfirmation(
        stringResource(StringRes.cloud_backup_replace_title),
        stringResource(StringRes.cloud_backup_replace_message, book.title),
        stringResource(StringRes.cloud_backup_replace_button),
        onConfirm = { dispatch(BookDetailIntent.OnReplaceBackupConfirmed) },
        onDismiss = { dispatch(BookDetailIntent.OnReplaceBackupDismissed) },
    )
    if (state.showBackupConfirmation) {
        AlertDialog(
            onDismissRequest = { dispatch(BookDetailIntent.OnBackupDismissed) },
            containerColor = Ember.colors.surface,
            titleContentColor = Ember.colors.ink,
            textContentColor = Ember.colors.ink,
            title = { Text(stringResource(StringRes.cloud_backup_title)) },
            text = {
                Column {
                    Text(book.title)
                    Row {
                        Checkbox(checked = state.backupRightsAttested, onCheckedChange = { checked ->
                            dispatch(BookDetailIntent.OnBackupAttestationChanged(checked))
                        })
                        Text(stringResource(StringRes.cloud_backup_attestation_checkbox))
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { dispatch(BookDetailIntent.OnBackupConfirmed) },
                    enabled = state.backupRightsAttested) {
                    Text(stringResource(StringRes.cloud_backup_confirm))
                }
            },
            dismissButton = {
                TextButton(onClick = { dispatch(BookDetailIntent.OnBackupDismissed) }) {
                    Text(stringResource(StringRes.general_cancel))
                }
            },
        )
    }
    state.unlinkConfirmationCopy?.let { copy ->
        UnlinkCopyConfirmationDialog(
            copy = copy,
            onConfirm = { dispatch(BookDetailIntent.OnNotSameBookConfirmed) },
            onDismiss = { dispatch(BookDetailIntent.OnNotSameBookDismissed) },
        )
    }
    val offer = state.linkedResumeOffer
    if (offer != null && !state.comparingLinkedPositions) {
        LaunchedEffect(offer.dismissalEntry) { dispatch(BookDetailIntent.OnPromptVisible(UsageOperation.LinkedResume)) }
        LinkedResumeDialog(
            model = offer.toUiModel(),
            onContinue = { dispatch(BookDetailIntent.OnLinkedResumeContinueClicked) },
            onStay = { dispatch(BookDetailIntent.OnLinkedResumeStayClicked) },
            compareAll = {
                TextButton(onClick = { dispatch(BookDetailIntent.OnLinkedResumeCompareClicked) }) {
                    Text(stringResource(StringRes.resume_linked_compare))
                }
            },
        )
    } else if (offer == null && state.pendingOpenBookType != null &&
        state.progressInfo?.hasConflict == true) {
        LaunchedEffect(Unit) { dispatch(BookDetailIntent.OnPromptVisible(UsageOperation.Conflict)) }
        PositionConflictDialog(
            localProgressPercent = state.progressInfo.localProgressPercent ?: 0,
            remoteProgressPercent = state.progressInfo.remoteProgressPercent ?: 0,
            onUseLocal = { dispatch(BookDetailIntent.OnUseLocalPositionClicked) },
            onUseRemote = { dispatch(BookDetailIntent.OnUseRemotePositionClicked) },
            onDismissRequest = { dispatch(BookDetailIntent.OnConflictDialogDismissed) },
        )
    }
}

@Composable
private fun DetailConfirmation(
    title: String,
    message: String,
    confirm: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Ember.colors.surface,
        titleContentColor = Ember.colors.ink,
        textContentColor = Ember.colors.ink,
        title = { Text(title) },
        text = { Text(message) },
        confirmButton = {
            TextButton(onClick = onConfirm) { Text(confirm, color = Ember.colors.destructive) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(StringRes.general_cancel)) }
        },
    )
}
