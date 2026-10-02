package com.retro99.books.ui.detail

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
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
import resources.translations.book_detail_download_readalong
import resources.translations.book_detail_download_ebook
import resources.translations.book_detail_download_audiobook
import resources.translations.book_detail_ebook_only
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
    val actions = detailActionMedia(media)
    val primary = actions.firstOrNull() ?: return
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
            val action = downloadLabel(primary, ebookOnly = false)
            primary.size?.let { size ->
                stringResource(StringRes.book_detail_action_size, action, byteCount(size))
            } ?: action
        }
    }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
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
                modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp),
            )
            val secondary = actions.getOrNull(1)
            if (secondary != null) {
                val transfer = secondary.state as? DownloadState.Downloading
                val action = when {
                    transfer != null -> transfer.progress?.let {
                        stringResource(StringRes.book_detail_downloading, (it * 100).toInt())
                    } ?: stringResource(StringRes.book_detail_downloading_unknown)
                    secondary.canOpen -> stringResource(StringRes.books_detail_action_listen)
                    primary.canOpen -> stringResource(if (audioOnly)
                        StringRes.book_detail_download_read else StringRes.book_detail_download_listen)
                    else -> downloadLabel(secondary, ebookOnly = primary.type == BookType.READALOUD)
                }
                val secondaryLabel = if (!secondary.canOpen && transfer == null) {
                    secondary.size?.let { stringResource(StringRes.book_detail_action_size,
                        action, byteCount(it)) } ?: action
                } else action
                DetailButton(
                    secondaryLabel,
                    onClick = { dispatch(if (secondary.canOpen)
                        BookDetailIntent.OnListenClicked(secondary.type)
                        else BookDetailIntent.OnDownloadClicked(secondary.type)) },
                    icon = if (secondary.canOpen) Icons.Outlined.Headphones else Icons.Outlined.Download,
                    enabled = transfer == null && (secondary.canOpen || secondary.canDownload),
                    loading = transfer != null,
                    modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp),
                )
            }
        }
        val caption = when {
            actions.any { it.state is DownloadState.Downloading } -> StringRes.book_detail_download_continues
            media.any { it.type == BookType.READALOUD && it.canDownload } ->
                StringRes.book_detail_download_readalong_caption
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

@Composable
private fun downloadLabel(media: DetailMedia, ebookOnly: Boolean): String = stringResource(
    when (media.type) {
        BookType.READALOUD -> StringRes.book_detail_download_readalong
        BookType.EBOOK -> if (ebookOnly) StringRes.book_detail_ebook_only
            else StringRes.book_detail_download_ebook
        BookType.AUDIOBOOK -> StringRes.book_detail_download_audiobook
    },
)
