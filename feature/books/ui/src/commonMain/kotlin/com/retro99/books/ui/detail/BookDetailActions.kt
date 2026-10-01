package com.retro99.books.ui.detail

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.MenuBook
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.Headphones
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.retro99.base.ui.IntentDispatcher
import com.retro99.base.ui.compose.Ember
import com.retro99.books.domain.model.BookType
import com.retro99.reader.domain.model.DownloadState
import com.retro99.translations.StringRes
import org.jetbrains.compose.resources.stringResource
import resources.translations.book_detail_continue_reading
import resources.translations.book_detail_continue_listening
import resources.translations.book_detail_download_read
import resources.translations.book_detail_download_listen
import resources.translations.book_detail_action_size
import resources.translations.book_detail_upload_wait
import resources.translations.book_detail_downloading
import resources.translations.book_detail_downloading_unknown
import resources.translations.book_detail_download_caption
import resources.translations.book_detail_download_readalong_caption
import resources.translations.book_detail_download_audio_caption
import resources.translations.book_detail_download_continues
import resources.translations.book_detail_preparing_format
import resources.translations.books_detail_action_read
import resources.translations.books_detail_action_listen
import resources.translations.books_detail_action_download_failed

@Composable
internal fun BookDetailActions(
    state: BookDetailViewState,
    media: List<DetailMedia>,
    dispatch: IntentDispatcher<BookDetailIntent>,
) {
    val primary = media.firstOrNull { item -> !item.preparing } ?: media.firstOrNull() ?: return
    val audioOnly = primary.type == BookType.AUDIOBOOK
    val downloading = primary.state as? DownloadState.Downloading
    val label = when {
        primary.preparing -> stringResource(StringRes.book_detail_preparing_format,
            mediaLabel(primary.type))
        downloading != null -> downloading.progress?.let { fraction ->
            stringResource(StringRes.book_detail_downloading, (fraction * 100).toInt())
        } ?: stringResource(StringRes.book_detail_downloading_unknown)
        primary.canOpen -> stringResource(when {
            (state.progressInfo?.progressPercent ?: 0) > 0 && audioOnly ->
                StringRes.book_detail_continue_listening
            (state.progressInfo?.progressPercent ?: 0) > 0 -> StringRes.book_detail_continue_reading
            audioOnly -> StringRes.books_detail_action_listen
            else -> StringRes.books_detail_action_read
        })
        primary.awaitingUpload -> stringResource(StringRes.book_detail_upload_wait)
        primary.state is DownloadState.Failed ->
            stringResource(StringRes.books_detail_action_download_failed)
        else -> {
            val action = stringResource(if (audioOnly) StringRes.book_detail_download_listen
                else StringRes.book_detail_download_read)
            primary.size?.let { size ->
                stringResource(StringRes.book_detail_action_size, action, byteCount(size))
            } ?: action
        }
    }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            DetailButton(
                label,
                onClick = {
                    dispatch(if (primary.canOpen) primary.openIntent()
                        else BookDetailIntent.OnDownloadClicked(primary.type))
                },
                primary = true,
                loading = downloading != null,
                enabled = downloading == null && (primary.canOpen || primary.canDownload),
                icon = when {
                    !primary.canOpen -> Icons.Outlined.Download
                    audioOnly -> Icons.Outlined.Headphones
                    else -> Icons.AutoMirrored.Outlined.MenuBook
                },
                modifier = Modifier.weight(1f).heightIn(min = 56.dp),
            )
            val listen = media.firstOrNull { item ->
                item.type == BookType.READALOUD && item.canOpen
            } ?: media.firstOrNull { item -> item.type == BookType.AUDIOBOOK && item.canOpen }
            if (listen != null && !audioOnly && primary.canOpen) {
                DetailButton(
                    stringResource(StringRes.books_detail_action_listen),
                    onClick = { dispatch(BookDetailIntent.OnListenClicked(listen.type)) },
                    icon = Icons.Outlined.Headphones,
                    secondary = true,
                    modifier = Modifier.heightIn(min = 56.dp),
                )
            }
        }
        val caption = when {
            downloading != null -> StringRes.book_detail_download_continues
            primary.canOpen || !primary.canDownload -> null
            primary.type == BookType.READALOUD -> StringRes.book_detail_download_readalong_caption
            audioOnly -> StringRes.book_detail_download_audio_caption
            else -> StringRes.book_detail_download_caption
        }
        if (caption != null) {
            Text(stringResource(caption), modifier = Modifier.fillMaxWidth(),
                style = Ember.type.meta, color = Ember.colors.ink2)
        }
    }
}
