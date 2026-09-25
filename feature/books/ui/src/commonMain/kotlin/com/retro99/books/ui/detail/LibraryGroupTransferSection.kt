package com.retro99.books.ui.detail

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.retro99.server.api.library.LibraryOperation
import com.retro99.server.api.library.LibraryTransferProgress
import com.retro99.server.api.library.LibraryTransferStatus
import com.retro99.translations.StringRes
import resources.translations.books_detail_transfers
import resources.translations.cloud_backup_cancel
import resources.translations.cloud_backup_cancelled
import resources.translations.cloud_backup_complete
import resources.translations.cloud_backup_failed
import resources.translations.cloud_backup_finishing
import resources.translations.cloud_backup_progress
import resources.translations.cloud_backup_queued
import resources.translations.cloud_backup_reason_generic
import resources.translations.cloud_backup_retry
import resources.translations.cloud_backup_unknown_state
import resources.translations.cloud_download_cancel
import resources.translations.cloud_download_cancelled
import resources.translations.cloud_download_complete
import resources.translations.cloud_download_failed
import resources.translations.cloud_download_finishing
import resources.translations.cloud_download_progress
import resources.translations.cloud_download_queued
import resources.translations.cloud_download_retry
import org.jetbrains.compose.resources.stringResource

@Composable
fun LibraryGroupTransferSection(
    transfers: List<LibraryTransferProgress>,
    isLoading: Boolean,
    errorMessage: String?,
    activeAction: LibraryTransferActionKey?,
    onCancel: (LibraryTransferActionKey) -> Unit,
    onRetry: (LibraryTransferActionKey) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            text = stringResource(StringRes.books_detail_transfers),
            style = MaterialTheme.typography.titleMedium,
        )
        if (isLoading) LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
        errorMessage?.let { message ->
            Text(message, color = MaterialTheme.colorScheme.error)
        }
        transfers.forEach { transfer ->
            LibraryTransferCard(
                transfer = transfer,
                isActionRunning = activeAction == transfer.actionKey(),
                onCancel = { onCancel(transfer.actionKey()) },
                onRetry = { onRetry(transfer.actionKey()) },
            )
        }
    }
}

@Composable
private fun LibraryTransferCard(
    transfer: LibraryTransferProgress,
    isActionRunning: Boolean,
    onCancel: () -> Unit,
    onRetry: () -> Unit,
) {
    val isUpload = transfer.operation == LibraryOperation.Upload
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = transfer.source.key.adapterId.value,
                style = MaterialTheme.typography.titleSmall,
            )
            Text(transferStatus(transfer))
            if (transfer.canCancel || transfer.canRetry) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                ) {
                    if (transfer.canCancel) {
                        TextButton(
                            enabled = !isActionRunning,
                            onClick = onCancel,
                        ) {
                            Text(
                                stringResource(
                                    if (isUpload) {
                                        StringRes.cloud_backup_cancel
                                    } else {
                                        StringRes.cloud_download_cancel
                                    },
                                ),
                            )
                        }
                    }
                    if (transfer.canRetry) {
                        TextButton(
                            enabled = !isActionRunning,
                            onClick = onRetry,
                        ) {
                            Text(
                                stringResource(
                                    if (isUpload) {
                                        StringRes.cloud_backup_retry
                                    } else {
                                        StringRes.cloud_download_retry
                                    },
                                ),
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun transferStatus(transfer: LibraryTransferProgress): String {
    val isUpload = transfer.operation == LibraryOperation.Upload
    val progress = if (transfer.totalBytes > 0) {
        ((transfer.bytesTransferred.toDouble() / transfer.totalBytes.toDouble()) * 100)
            .toInt()
            .coerceIn(0, 100)
    } else {
        0
    }
    return when (transfer.status) {
        LibraryTransferStatus.Queued -> stringResource(
            if (isUpload) StringRes.cloud_backup_queued else StringRes.cloud_download_queued,
        )
        LibraryTransferStatus.Transferring -> stringResource(
            if (isUpload) StringRes.cloud_backup_progress else StringRes.cloud_download_progress,
            progress,
        )
        LibraryTransferStatus.Verifying,
        LibraryTransferStatus.Finalizing -> stringResource(
            if (isUpload) StringRes.cloud_backup_finishing else StringRes.cloud_download_finishing,
        )
        LibraryTransferStatus.Completed -> stringResource(
            if (isUpload) StringRes.cloud_backup_complete else StringRes.cloud_download_complete,
        )
        LibraryTransferStatus.Failed -> stringResource(
            if (isUpload) StringRes.cloud_backup_failed else StringRes.cloud_download_failed,
            stringResource(StringRes.cloud_backup_reason_generic),
        )
        LibraryTransferStatus.Cancelled -> stringResource(
            if (isUpload) StringRes.cloud_backup_cancelled else StringRes.cloud_download_cancelled,
        )
        LibraryTransferStatus.Unknown -> stringResource(StringRes.cloud_backup_unknown_state)
    }
}
