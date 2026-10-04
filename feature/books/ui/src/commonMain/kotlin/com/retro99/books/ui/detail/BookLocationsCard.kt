package com.retro99.books.ui.detail

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Cloud
import androidx.compose.material.icons.outlined.PhoneAndroid
import androidx.compose.material.icons.outlined.Storage
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import resources.translations.book_detail_download_failed
import resources.translations.book_detail_retry
import resources.translations.book_detail_remove_download_label
import com.retro99.base.ui.IntentDispatcher
import com.retro99.base.ui.compose.Ember
import com.retro99.books.domain.BookFileTransfer
import com.retro99.books.domain.model.BookType
import com.retro99.books.ui.links.label
import com.retro99.books.ui.model.BookUiModel
import com.retro99.reader.domain.model.DownloadState
import com.retro99.translations.StringRes
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource
import org.jetbrains.compose.resources.pluralStringResource
import resources.translations.Res
import resources.translations.book_detail_format_quantity
import resources.translations.book_detail_phone
import resources.translations.book_detail_phone_only
import resources.translations.book_detail_phone_missing
import resources.translations.book_detail_action_size
import resources.translations.book_detail_remove
import resources.translations.book_detail_manage
import resources.translations.book_detail_cloud
import resources.translations.book_detail_cloud_available
import resources.translations.book_detail_only_phone
import resources.translations.book_detail_also_on
import resources.translations.book_detail_open
import resources.translations.book_detail_preparation
import resources.translations.book_detail_preparation_stage
import resources.translations.book_detail_downloading
import resources.translations.book_detail_downloading_unknown
import resources.translations.book_detail_transferred_bytes
import resources.translations.general_cancel
import resources.translations.cloud_backup_button
import resources.translations.cloud_backup_queued
import resources.translations.cloud_backup_progress
import resources.translations.cloud_backup_finishing
import resources.translations.cloud_backup_failed
import resources.translations.cloud_backup_retry
import resources.translations.cloud_backup_replacing
import resources.translations.book_detail_replace
import resources.translations.cloud_download_queued
import resources.translations.cloud_download_progress
import resources.translations.cloud_download_finishing
import resources.translations.cloud_download_failed
import resources.translations.cloud_download_retry
import resources.translations.cloud_backup_unknown_state
import resources.translations.cloud_backup_reason_attestation_required
import resources.translations.cloud_backup_reason_content_blocked
import resources.translations.cloud_backup_reason_file_exists
import resources.translations.cloud_backup_reason_generic
import resources.translations.cloud_backup_reason_quota_exceeded
import resources.translations.cloud_backup_reason_verify_failed
import resources.translations.cloud_backup_reason_uploads_not_enabled
import resources.translations.cloud_backup_reason_file_too_large
import resources.translations.cloud_download_reason_generic
import resources.translations.cloud_download_reason_unavailable
import resources.translations.cloud_download_reason_verify_failed

@Composable
internal fun BookLocationsCard(
    state: BookDetailViewState,
    media: List<DetailMedia>,
    dispatch: IntentDispatcher<BookDetailIntent>,
    onManage: () -> Unit,
) {
    val book = state.book ?: return
    val shape = RoundedCornerShape(20.dp)
    val transfers = visibleDetailTransfers(state.bookFileTransfers)
    Column(Modifier.fillMaxWidth().clip(shape).background(Ember.colors.surface)
        .border(Ember.style.detailBorder, Ember.colors.line, shape)) {
        val cached = media.filter { item -> item.state is DownloadState.Cached }
        val removable = cached.filter { item -> item.canRemove }
        val deviceOnly = book is BookUiModel.LibraryBook &&
            state.libraryBookActions?.deleteFromDevice == true
        val summary = phoneMediaSummary(media)
        val format = summary.singleType?.let { type -> mediaLabel(type) }
            ?: pluralStringResource(
                Res.plurals.book_detail_format_quantity, summary.formatCount, summary.formatCount,
            )
        val description = when {
            cached.isEmpty() -> media.map { item ->
                val name = mediaLabel(item.type)
                item.size?.let { "$name ${byteCount(it)}" } ?: name
            }.joinToString(" · ")
            summary.size != null -> stringResource(
                StringRes.book_detail_action_size, format, byteCount(summary.size),
            )
            else -> format
        }
        val downloading = media.any { item -> item.state is DownloadState.Downloading }
        val restoreRows = transfers.any { transfer -> transfer.direction == "download" }
        if (cached.isNotEmpty() || (!downloading && !restoreRows)) LocationRow(
            title = stringResource(when {
                cached.isEmpty() -> StringRes.book_detail_phone_missing
                deviceOnly -> StringRes.book_detail_phone_only
                else -> StringRes.book_detail_phone
            }),
            subtitle = description,
            icon = Icons.Outlined.PhoneAndroid,
            action = if (removable.isNotEmpty()) {
                {
                    DetailButton(stringResource(StringRes.book_detail_remove) + "…", onClick = {
                        if (removable.size == 1) dispatch(
                            BookDetailIntent.OnDeleteCacheClicked(removable.single().type),
                        ) else onManage()
                    }, neutral = true,
                        actionDescription = stringResource(StringRes.book_detail_remove_download_label))
                }
            } else null,
        )
        transfers.filter { transfer -> transfer.direction == "download" }.forEach { transfer ->
            TransferLocationRow(transfer, state, dispatch)
        }
        if (book !is BookUiModel.LibraryBook) {
            media.filter { item -> item.state is DownloadState.Downloading }.forEach { item ->
                val fraction = (item.state as DownloadState.Downloading).progress
                LocationRow(
                    title = mediaLabel(item.type),
                    subtitle = fraction?.let { value -> stringResource(
                        StringRes.book_detail_downloading, (value * 100).toInt(),
                    ) } ?: stringResource(StringRes.book_detail_downloading_unknown),
                    icon = Icons.Outlined.PhoneAndroid,
                    progress = fraction,
                    showProgress = true,
                    action = {
                        DetailButton(stringResource(StringRes.general_cancel), neutral = true, onClick = {
                            dispatch(BookDetailIntent.OnDownloadClicked(item.type))
                        })
                    },
                )
            }
            media.filter { item -> item.state is DownloadState.Failed }.forEach { item ->
                LocationRow(
                    title = mediaLabel(item.type),
                    subtitle = stringResource(StringRes.book_detail_download_failed),
                    icon = Icons.Outlined.PhoneAndroid,
                    error = true,
                    action = {
                        DetailButton(stringResource(StringRes.book_detail_retry), neutral = true, onClick = {
                            dispatch(BookDetailIntent.OnDownloadClicked(item.type))
                        })
                    },
                )
            }
        }
        if (book is BookUiModel.LibraryBook && state.parrotActive) {
            LocationDivider()
            CloudLocations(state, dispatch)
        }
        if (book.narrationIsPreparing()) {
            LocationDivider()
            LocationRow(
                title = mediaLabel(BookType.READALOUD),
                subtitle = book.narrationStage?.let { stage -> stringResource(
                    StringRes.book_detail_preparation_stage, stage,
                    ((book.narrationProgress ?: 0.0) * 100).toInt(),
                ) } ?: stringResource(StringRes.book_detail_preparation),
                icon = Icons.Outlined.Storage,
            )
        }
        state.linkedCopies.forEach { copy ->
            LocationDivider()
            LocationRow(
                title = stringResource(StringRes.book_detail_also_on, copy.home.label()),
                subtitle = listOfNotNull(
                    if (copy.hasReadaloud) mediaLabel(BookType.READALOUD)
                    else if (copy.hasAudiobook) mediaLabel(BookType.AUDIOBOOK)
                    else mediaLabel(BookType.EBOOK),
                    copy.serverLabel,
                ).joinToString(" · "),
                icon = Icons.Outlined.Storage,
                action = {
                    DetailButton(stringResource(StringRes.book_detail_open), neutral = true, onClick = {
                        dispatch(BookDetailIntent.OnOpenLinkedCopyClicked(copy))
                    })
                },
            )
        }
        LocationDivider()
        TextButton(onClick = onManage, modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp)) {
            Text(stringResource(StringRes.book_detail_manage), color = Ember.colors.accentText,
                style = Ember.type.label)
        }
    }
}

@Composable
internal fun CloudLocations(
    state: BookDetailViewState,
    dispatch: IntentDispatcher<BookDetailIntent>,
) {
    val book = state.book as? BookUiModel.LibraryBook ?: return
    val uploads = visibleDetailTransfers(state.bookFileTransfers)
        .filter { transfer -> transfer.direction == "upload" }
    val available = book.mediaResources.any { resource ->
        resource.remoteAvailability == "Available"
    }
    if (uploads.isEmpty() || available || state.supportsBookBackup) {
        LocationRow(
            title = stringResource(StringRes.book_detail_cloud),
            subtitle = stringResource(if (available) StringRes.book_detail_cloud_available
                else StringRes.book_detail_only_phone),
            icon = Icons.Outlined.Cloud,
            success = available,
            action = if (state.supportsBookBackup) {
                {
                    DetailButton(stringResource(StringRes.cloud_backup_button), neutral = true, onClick = {
                        dispatch(BookDetailIntent.OnBackupClicked)
                    })
                }
            } else null,
        )
    }
    uploads.forEach { transfer -> TransferLocationRow(transfer, state, dispatch) }
}

@Composable
internal fun TransferLocationRow(
    transfer: BookFileTransfer,
    state: BookDetailViewState,
    dispatch: IntentDispatcher<BookDetailIntent>,
) {
    val download = transfer.direction == "download"
    val fraction = if (transfer.totalBytes > 0) {
        (transfer.bytesTransferred.toFloat() / transfer.totalBytes).coerceIn(0f, 1f)
    } else null
    val replacing = state.replacingBackupTransferId == transfer.transferId
    val status = when {
        replacing -> stringResource(StringRes.cloud_backup_replacing)
        transfer.state == "pending" -> stringResource(if (download)
            StringRes.cloud_download_queued else StringRes.cloud_backup_queued)
        transfer.state == "transferring" -> stringResource(if (download)
            StringRes.cloud_download_progress else StringRes.cloud_backup_progress,
            ((fraction ?: 0f) * 100).toInt())
        transfer.state in setOf("verifying", "finalizing") -> stringResource(if (download)
            StringRes.cloud_download_finishing else StringRes.cloud_backup_finishing)
        transfer.state == "failed" -> stringResource(if (download)
            StringRes.cloud_download_failed else StringRes.cloud_backup_failed,
            localizedTransferFailureReason(download, transfer.lastError))
        else -> stringResource(StringRes.cloud_backup_unknown_state)
    }
    val type = BookType.entries.find { type -> type.value == transfer.mediaType }
    LocationRow(
        title = status,
        subtitle = listOfNotNull(
            type?.let { media -> mediaLabel(media) },
            if (transfer.isActive && transfer.totalBytes > 0) stringResource(
                StringRes.book_detail_transferred_bytes,
                byteCount(transfer.bytesTransferred), byteCount(transfer.totalBytes),
            ) else null,
        ).joinToString(" · "),
        icon = if (download) Icons.Outlined.PhoneAndroid else Icons.Outlined.Cloud,
        progress = fraction,
        showProgress = transfer.isActive,
        error = transfer.state == "failed",
        action = {
            if (transfer.isActive) {
                DetailButton(stringResource(StringRes.general_cancel), neutral = true, onClick = {
                    dispatch(BookDetailIntent.OnCancelBookFileTransferClicked(transfer.transferId))
                })
            }
            if (!replacing && (transfer.state == "failed" ||
                    (transfer.state == "pending" && transfer.attemptCount > 0 &&
                        transfer.lastError != null))) {
                val replace = !download && transfer.lastError == "file_exists"
                if (download || state.parrotActive) {
                    DetailButton(stringResource(when {
                        replace -> StringRes.book_detail_replace
                        download -> StringRes.cloud_download_retry
                        else -> StringRes.cloud_backup_retry
                    }), onClick = {
                        dispatch(if (replace) BookDetailIntent.OnReplaceBackupClicked(
                            transfer.transferId,
                        ) else BookDetailIntent.OnRetryBookBackupClicked(transfer.transferId))
                    }, destructive = replace, neutral = !replace)
                }
            }
        },
    )
}

@Composable
internal fun LocationDivider() = HorizontalDivider(color = Ember.colors.line)

@Composable
internal fun LocationRow(
    title: String,
    subtitle: String,
    icon: ImageVector,
    action: (@Composable () -> Unit)? = null,
    progress: Float? = null,
    showProgress: Boolean = false,
    success: Boolean = false,
    error: Boolean = false,
) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = 80.dp).padding(14.dp)
            .semantics(mergeDescendants = true) {},
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Icon(icon, contentDescription = null, tint = Ember.colors.accentText,
            modifier = Modifier.background(if (Ember.style.isEink) Ember.colors.surface
                else Ember.colors.navActive, RoundedCornerShape(10.dp)).padding(10.dp).size(16.dp))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(title, style = Ember.type.label.copy(fontSize = 15.sp), color = Ember.colors.ink)
            if (subtitle.isNotBlank()) Text(subtitle, style = Ember.type.meta,
                color = when {
                    error -> Ember.colors.destructive
                    success -> Ember.colors.success
                    else -> Ember.colors.ink2
                })
            if (showProgress) DetailProgressBar(progress)
        }
        if (action != null) Column { action() }
    }
}

@Composable
private fun localizedTransferFailureReason(download: Boolean, reason: String?): String =
    stringResource(transferFailureReasonRes(download, reason))

internal fun transferFailureReasonRes(download: Boolean, reason: String?): StringResource =
    when {
        download && reason == "verify_failed" -> StringRes.cloud_download_reason_verify_failed
        download && reason in setOf("cloud_file_unavailable", "cloud_file_changed") ->
            StringRes.cloud_download_reason_unavailable
        download -> StringRes.cloud_download_reason_generic
        reason == "file_exists" -> StringRes.cloud_backup_reason_file_exists
        reason == "quota_exceeded" -> StringRes.cloud_backup_reason_quota_exceeded
        reason == "content_blocked" -> StringRes.cloud_backup_reason_content_blocked
        reason == "attestation_required" -> StringRes.cloud_backup_reason_attestation_required
        reason == "verify_failed" -> StringRes.cloud_backup_reason_verify_failed
        // Server gates (allowlist, size cap): retrying won't help.
        reason == "uploads_not_enabled" -> StringRes.cloud_backup_reason_uploads_not_enabled
        reason == "file_too_large" -> StringRes.cloud_backup_reason_file_too_large
        else -> StringRes.cloud_backup_reason_generic
    }
