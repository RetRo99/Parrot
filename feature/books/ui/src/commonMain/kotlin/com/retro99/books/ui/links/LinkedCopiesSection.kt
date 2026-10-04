package com.retro99.books.ui.links

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import com.retro99.base.ui.compose.EmberDialog
import com.retro99.base.ui.compose.EmberDialogAction
import com.retro99.base.ui.compose.EmberDialogActionStyle
import com.retro99.books.ui.components.HomeBadge
import com.retro99.books.ui.model.LinkedCopyUiModel
import com.retro99.translations.StringRes
import org.jetbrains.compose.resources.stringResource
import resources.translations.books_media_audio
import resources.translations.books_media_ebook
import resources.translations.books_media_readaloud
import resources.translations.general_cancel
import resources.translations.link_also_in
import resources.translations.link_not_same_book
import resources.translations.link_open_copy
import resources.translations.link_same_book_as
import resources.translations.link_unlink_confirm_message
import resources.translations.link_unlink_confirm_title
import resources.translations.book_detail_unlink_never

/**
 * The linking part of a book's detail screen: the other copies it is linked to ("Also in")
 * and the "Same book as…" action. Plain rows; the visual design comes later.
 */
@Composable
fun LinkedCopiesSection(
    linkedCopies: List<LinkedCopyUiModel>,
    onOpenCopy: (LinkedCopyUiModel) -> Unit,
    onNotSameBook: (LinkedCopyUiModel) -> Unit,
    onSameBookAs: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        if (linkedCopies.isNotEmpty()) {
            Text(
                text = stringResource(StringRes.link_also_in),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
            linkedCopies.forEach { copy ->
                LinkedCopyRow(
                    copy = copy,
                    onOpen = { onOpenCopy(copy) },
                    onNotSameBook = { onNotSameBook(copy) },
                )
            }
        }
        OutlinedButton(onClick = onSameBookAs, modifier = Modifier.fillMaxWidth()) {
            Text(text = stringResource(StringRes.link_same_book_as))
        }
    }
}

@Composable
private fun LinkedCopyRow(
    copy: LinkedCopyUiModel,
    onOpen: () -> Unit,
    onNotSameBook: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val formats = listOfNotNull(
        stringResource(StringRes.books_media_ebook).takeIf { copy.hasEbook },
        stringResource(StringRes.books_media_audio).takeIf { copy.hasAudiobook },
        stringResource(StringRes.books_media_readaloud).takeIf { copy.hasReadaloud },
    ).joinToString(" · ")
    Column(modifier = modifier.fillMaxWidth()) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            HomeBadge(home = copy.home)
            Text(
                text = formats,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f),
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TextButton(onClick = onOpen) {
                Text(text = stringResource(StringRes.link_open_copy))
            }
            TextButton(onClick = onNotSameBook) {
                Text(text = stringResource(StringRes.link_not_same_book) + "…")
            }
        }
    }
}

@Composable
fun UnlinkCopyConfirmationDialog(
    copy: LinkedCopyUiModel,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    EmberDialog(
        onDismissRequest = onDismiss,
        title = stringResource(StringRes.link_unlink_confirm_title),
        actions = listOf(
            EmberDialogAction(
                label = stringResource(StringRes.general_cancel),
                style = EmberDialogActionStyle.Neutral,
                onClick = onDismiss,
            ),
            EmberDialogAction(
                label = stringResource(StringRes.link_not_same_book),
                style = EmberDialogActionStyle.Main,
                onClick = onConfirm,
            ),
        ),
        modifier = modifier,
        body = AnnotatedString(
            stringResource(
                StringRes.link_unlink_confirm_message,
                copy.title,
                copy.home.label(),
            ) + "\n\n" + stringResource(StringRes.book_detail_unlink_never),
        ),
    )
}
