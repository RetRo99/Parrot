package com.retro99.books.ui.detail

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.PhoneAndroid
import androidx.compose.material.icons.outlined.Storage
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import com.retro99.analytics.api.UsageFeature
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.material3.Surface
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.runtime.remember
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.unit.dp
import com.retro99.base.ui.IntentDispatcher
import com.retro99.base.ui.compose.Ember
import com.retro99.books.ui.links.label
import com.retro99.reader.domain.model.DownloadState
import com.retro99.translations.StringRes
import org.jetbrains.compose.resources.stringResource
import resources.translations.book_detail_manage_title
import resources.translations.book_detail_phone
import resources.translations.book_detail_cloud
import resources.translations.book_detail_elsewhere
import resources.translations.book_detail_downloaded
import resources.translations.book_detail_not_downloaded
import resources.translations.book_detail_open
import resources.translations.book_detail_remove
import resources.translations.book_detail_link_copy
import resources.translations.book_detail_cloud_delete_caption
import resources.translations.book_detail_device_delete_caption
import resources.translations.books_media_download
import resources.translations.general_cancel
import resources.translations.positions_action
import resources.translations.link_not_same_book
import resources.translations.library_remove_from_parrot_button
import resources.translations.books_delete_local_button
import resources.translations.book_detail_remove_format_label

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun BookManageSheet(
    state: BookDetailViewState,
    media: List<DetailMedia>,
    dispatch: IntentDispatcher<BookDetailIntent>,
    onDismiss: () -> Unit,
) {
    LaunchedEffect(Unit) {
        dispatch(BookDetailIntent.OnFeatureVisible(UsageFeature.LinkedCopies, true))
    }
    LaunchedEffect(state.linkedCopies.isNotEmpty()) {
        dispatch(BookDetailIntent.OnFeatureVisible(UsageFeature.Positions, state.linkedCopies.isNotEmpty()))
    }
    val content: @Composable () -> Unit = {
        Column(
            Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(stringResource(StringRes.book_detail_manage_title), style = Ember.type.screenTitle)
            Text(stringResource(StringRes.book_detail_phone), style = Ember.type.label)
            media.forEach { item ->
                LocationRow(
                    title = mediaLabel(item.type),
                    subtitle = listOfNotNull(
                        stringResource(if (item.state is DownloadState.Cached)
                            StringRes.book_detail_downloaded else StringRes.book_detail_not_downloaded),
                        item.size?.let { size -> byteCount(size) },
                    ).joinToString(" · "),
                    icon = Icons.Outlined.PhoneAndroid,
                    action = {
                        if (item.canOpen) DetailButton(
                            stringResource(StringRes.book_detail_open), onClick = {
                                onDismiss()
                                dispatch(item.openIntent())
                            },
                        )
                        if (item.canRemove) DetailButton(
                            stringResource(StringRes.book_detail_remove) + "…", onClick = {
                                onDismiss()
                                dispatch(BookDetailIntent.OnDeleteCacheClicked(item.type))
                            },
                            actionDescription = stringResource(
                                StringRes.book_detail_remove_format_label, mediaLabel(item.type),
                            ),
                        )
                        if (item.state is DownloadState.Downloading ||
                            (item.state !is DownloadState.Cached && item.canDownload)) {
                            DetailButton(stringResource(if (item.state is DownloadState.Downloading)
                                StringRes.general_cancel else StringRes.books_media_download),
                                onClick = { dispatch(BookDetailIntent.OnDownloadClicked(item.type)) })
                        }
                    },
                    showProgress = item.state is DownloadState.Downloading,
                    progress = (item.state as? DownloadState.Downloading)?.progress,
                )
                LocationDivider()
            }
            if (state.parrotActive && state.libraryBookActions != null) {
                Text(stringResource(StringRes.book_detail_cloud), style = Ember.type.label)
                CloudLocations(state, dispatch)
            }
            Text(stringResource(StringRes.book_detail_elsewhere), style = Ember.type.label)
            state.linkedCopies.forEach { copy ->
                LocationRow(
                    title = copy.home.label(),
                    subtitle = copy.serverLabel.orEmpty(),
                    icon = Icons.Outlined.Storage,
                    action = {
                        DetailButton(stringResource(StringRes.book_detail_open), onClick = {
                            onDismiss()
                            dispatch(BookDetailIntent.OnOpenLinkedCopyClicked(copy))
                        })
                        DetailButton(stringResource(StringRes.link_not_same_book) + "…", onClick = {
                            onDismiss()
                            dispatch(BookDetailIntent.OnNotSameBookClicked(copy))
                        })
                    },
                )
            }
            if (state.linkedCopies.isNotEmpty()) DetailButton(
                stringResource(StringRes.positions_action), onClick = {
                    onDismiss()
                    dispatch(BookDetailIntent.OnReadingPositionsClicked)
                },
            )
            DetailButton(stringResource(StringRes.book_detail_link_copy), onClick = {
                onDismiss()
                dispatch(BookDetailIntent.OnSameBookAsClicked)
            })
            LocationDivider()
            if (state.libraryBookActions?.removeFromParrot == true) {
                DetailButton(stringResource(StringRes.library_remove_from_parrot_button) + "…",
                    onClick = {
                        onDismiss()
                        dispatch(BookDetailIntent.OnRemoveFromParrotClicked)
                    }, destructive = true, modifier = Modifier.fillMaxWidth())
                Text(stringResource(StringRes.book_detail_cloud_delete_caption),
                    color = Ember.colors.ink2, style = Ember.type.meta)
            }
            if (state.libraryBookActions?.deleteFromDevice == true) {
                DetailButton(stringResource(StringRes.books_delete_local_button) + "…",
                    onClick = {
                        onDismiss()
                        dispatch(BookDetailIntent.OnDeleteLocalBookClicked)
                    }, destructive = true, modifier = Modifier.fillMaxWidth())
                Text(stringResource(StringRes.book_detail_device_delete_caption),
                    color = Ember.colors.ink2, style = Ember.type.meta)
            }
        }
    }
    if (Ember.style.isEink) {
        // No animated sheet entrance/exit on a slow-refresh display.
        Dialog(onDismissRequest = onDismiss,
            properties = DialogProperties(usePlatformDefaultWidth = false)) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.BottomCenter) {
                Box(Modifier.fillMaxSize().clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = onDismiss,
                ))
                Surface(
                    modifier = Modifier.fillMaxWidth().heightIn(max = 700.dp),
                    shape = RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp),
                    color = Ember.colors.surface,
                    contentColor = Ember.colors.ink,
                    border = BorderStroke(Ember.style.detailBorder, Ember.colors.line),
                ) { content() }
            }
        }
    } else {
        ModalBottomSheet(
            onDismissRequest = onDismiss,
            containerColor = Ember.colors.surface,
            contentColor = Ember.colors.ink,
        ) { content() }
    }
}
