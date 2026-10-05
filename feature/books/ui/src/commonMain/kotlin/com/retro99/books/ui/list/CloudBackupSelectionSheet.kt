package com.retro99.books.ui.list

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.retro99.base.ui.compose.Ember
import com.retro99.base.ui.compose.EmberBottomSheet
import com.retro99.base.ui.compose.EmberCover
import com.retro99.books.domain.model.BookType
import com.retro99.books.ui.model.CloudBackupBook
import com.retro99.books.ui.model.cloudStorageLabel
import com.retro99.translations.StringRes
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource
import resources.translations.book_detail_read_along
import resources.translations.books_media_ebook
import resources.translations.cloud_backup_attestation_checkbox
import resources.translations.cloud_backup_selection_add_count
import resources.translations.cloud_backup_selection_add_one
import resources.translations.cloud_backup_selection_book_count
import resources.translations.cloud_backup_selection_book_count_one
import resources.translations.cloud_backup_selection_mobile_data
import resources.translations.cloud_backup_selection_not_enough_space
import resources.translations.cloud_backup_selection_select_all
import resources.translations.cloud_backup_selection_select_none
import resources.translations.cloud_backup_selection_snackbar_count
import resources.translations.cloud_backup_selection_snackbar_one
import resources.translations.cloud_backup_selection_storage_unavailable
import resources.translations.cloud_backup_selection_subtitle
import resources.translations.cloud_backup_selection_summary
import resources.translations.cloud_backup_selection_summary_one
import resources.translations.cloud_backup_selection_title
import resources.translations.cloud_backup_selection_view
import resources.translations.general_close

@Composable
internal fun CloudBackupSelectionSheet(
    books: List<CloudBackupBook>,
    selectedBookIds: Set<String>,
    selectedBytes: Long,
    availableBytes: Long?,
    storageLoading: Boolean,
    storageUnavailable: Boolean,
    rightsAttested: Boolean,
    isAdding: Boolean,
    onBookToggled: (String, Boolean) -> Unit,
    onSelectAll: () -> Unit,
    onSelectNone: () -> Unit,
    onRightsChanged: (Boolean) -> Unit,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    val colors = Ember.colors
    val type = Ember.type
    val selectedCount = books.count { book -> book.id in selectedBookIds }
    val overQuota = availableBytes != null && selectedBytes > availableBytes
    val mobileData = rememberIsOnMobileData()
    val maxSheetHeight = with(LocalDensity.current) {
        (LocalWindowInfo.current.containerSize.height.toDp() * 0.60f).coerceAtLeast(240.dp)
    }
    val allSelected = books.isNotEmpty() && selectedCount == books.size
    val actionEnabled = rightsAttested && selectedCount > 0 && !overQuota && !storageLoading && !isAdding

    EmberBottomSheet(
        onDismiss = onDismiss,
        showTopBorder = true,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = maxSheetHeight)
                .padding(horizontal = 24.dp)
                .padding(bottom = 20.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = stringResource(StringRes.cloud_backup_selection_title),
                    style = type.screenTitle.copy(fontSize = 22.sp, lineHeight = 28.sp),
                    color = colors.ink,
                    modifier = Modifier.weight(1f),
                )
                Box(
                    modifier = Modifier
                        .size(44.dp)
                        .clip(CircleShape)
                        .clickable(role = Role.Button, onClick = onDismiss),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        imageVector = Icons.Filled.Close,
                        contentDescription = stringResource(StringRes.general_close),
                        tint = colors.ink,
                        modifier = Modifier.size(20.dp),
                    )
                }
            }
            Text(
                text = stringResource(StringRes.cloud_backup_selection_subtitle),
                style = type.meta.copy(fontSize = 14.sp, lineHeight = 20.sp),
                color = colors.ink2,
                modifier = Modifier.padding(top = 2.dp),
            )

            Row(
                modifier = Modifier.fillMaxWidth().padding(top = 12.dp, bottom = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(
                    text = if (books.size == 1) {
                        stringResource(StringRes.cloud_backup_selection_book_count_one)
                    } else {
                        stringResource(StringRes.cloud_backup_selection_book_count, books.size)
                    },
                    style = type.meta.copy(fontSize = 14.sp, fontWeight = FontWeight.SemiBold),
                    color = colors.ink,
                )
                Text(
                    text = stringResource(
                        if (allSelected) StringRes.cloud_backup_selection_select_none
                        else StringRes.cloud_backup_selection_select_all,
                    ),
                    style = type.label.copy(fontSize = 14.sp),
                    color = colors.accentText,
                    modifier = Modifier
                        .clip(CircleShape)
                        .clickable(role = Role.Button, onClick = if (allSelected) onSelectNone else onSelectAll)
                        .padding(horizontal = 8.dp, vertical = 8.dp),
                )
            }

            LazyColumn(
                modifier = Modifier.weight(1f, fill = false),
            ) {
                items(books, key = CloudBackupBook::id) { candidate ->
                    CloudBackupBookRow(
                        candidate = candidate,
                        checked = candidate.id in selectedBookIds,
                        onToggle = { checked -> onBookToggled(candidate.id, checked) },
                    )
                }
            }

            Spacer(Modifier.height(8.dp))
            val freeLabel = availableBytes?.let(::cloudStorageLabel) ?: "—"
            Text(
                text = if (selectedCount == 1) {
                    stringResource(
                        StringRes.cloud_backup_selection_summary_one,
                        cloudStorageLabel(selectedBytes),
                        freeLabel,
                    )
                } else {
                    stringResource(
                        StringRes.cloud_backup_selection_summary,
                        selectedCount,
                        cloudStorageLabel(selectedBytes),
                        freeLabel,
                    )
                },
                style = type.meta.copy(fontSize = 13.sp),
                color = colors.ink,
                maxLines = 2,
            )
            if (storageLoading) {
                CircularProgressIndicator(
                    modifier = Modifier.padding(top = 4.dp).size(14.dp),
                    color = colors.accentText,
                    strokeWidth = 2.dp,
                )
            }
            if (overQuota) {
                Text(
                    text = stringResource(
                        StringRes.cloud_backup_selection_not_enough_space,
                        cloudStorageLabel(selectedBytes),
                        cloudStorageLabel(availableBytes!!),
                    ),
                    style = type.meta.copy(fontSize = 13.sp),
                    color = colors.error,
                    modifier = Modifier.padding(top = 2.dp),
                )
            } else if (storageUnavailable) {
                Text(
                    text = stringResource(StringRes.cloud_backup_selection_storage_unavailable),
                    style = type.meta.copy(fontSize = 13.sp),
                    color = colors.ink2,
                    modifier = Modifier.padding(top = 2.dp),
                )
            }
            if (mobileData && selectedBytes > 0L) {
                Text(
                    text = stringResource(StringRes.cloud_backup_selection_mobile_data),
                    style = type.meta.copy(fontSize = 13.sp),
                    color = colors.ink2,
                    modifier = Modifier.padding(top = 2.dp),
                )
            }

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 8.dp)
                    .toggleable(
                        value = rightsAttested,
                        role = Role.Checkbox,
                        onValueChange = onRightsChanged,
                    ),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(Modifier.size(48.dp), contentAlignment = Alignment.Center) {
                    Checkbox(
                        checked = rightsAttested,
                        onCheckedChange = null,
                        modifier = Modifier.size(24.dp),
                    )
                }
                Text(
                    text = stringResource(StringRes.cloud_backup_attestation_checkbox),
                    style = type.meta.copy(fontSize = 14.sp, lineHeight = 20.sp),
                    color = colors.ink,
                    modifier = Modifier.weight(1f),
                )
            }

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 8.dp)
                    .height(52.dp)
                    .clip(CircleShape)
                    .background(if (actionEnabled) colors.accent else colors.track)
                    .then(
                        if (actionEnabled) Modifier.clickable(role = Role.Button, onClick = onConfirm)
                        else Modifier.semantics { disabled() },
                    ),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = if (selectedCount == 1) {
                        stringResource(StringRes.cloud_backup_selection_add_one)
                    } else {
                        stringResource(StringRes.cloud_backup_selection_add_count, selectedCount)
                    },
                    style = type.label.copy(fontSize = 16.sp, fontWeight = FontWeight.Bold),
                    color = if (actionEnabled) colors.onAccent else colors.ink2,
                )
            }
        }
    }
}

@Composable
private fun CloudBackupBookRow(
    candidate: CloudBackupBook,
    checked: Boolean,
    onToggle: (Boolean) -> Unit,
) {
    val colors = Ember.colors
    val type = Ember.type
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(64.dp)
            .toggleable(value = checked, role = Role.Checkbox, onValueChange = onToggle),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Box(Modifier.size(48.dp), contentAlignment = Alignment.Center) {
            Checkbox(
                checked = checked,
                onCheckedChange = null,
                modifier = Modifier.size(24.dp),
            )
        }
        EmberCover(
            data = candidate.book.coverUrl,
            cacheKey = candidate.book.uuid,
            contentDescription = candidate.book.title,
            fallbackLabel = candidate.book.title,
            modifier = Modifier.size(width = 36.dp, height = 48.dp),
        )
        Column(
            modifier = Modifier.weight(1f).padding(start = 8.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Text(
                text = candidate.book.title,
                style = type.meta.copy(fontSize = 16.sp, fontWeight = FontWeight.Bold, lineHeight = 20.sp),
                color = colors.ink,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            val format = candidate.mediaTypes.map { mediaType ->
                stringResource(
                    when (mediaType) {
                        BookType.READALOUD.value -> StringRes.book_detail_read_along
                        else -> StringRes.books_media_ebook
                    },
                )
            }.distinct().joinToString(" + ")
            Text(
                text = "$format · ${cloudStorageLabel(candidate.sizeBytes)}",
                style = type.meta.copy(fontSize = 13.sp),
                color = colors.ink2,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
internal fun cloudBackupSnackbarMessage(bookCount: Int): String = if (bookCount == 1) {
    stringResource(StringRes.cloud_backup_selection_snackbar_one)
} else {
    stringResource(StringRes.cloud_backup_selection_snackbar_count, bookCount)
}

internal fun cloudBackupSnackbarActionLabel(): StringResource = StringRes.cloud_backup_selection_view
