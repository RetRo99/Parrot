package com.retro99.catalogue.ui.downloads

import androidx.compose.foundation.*
import com.retro99.catalogue.ui.browse.CatalogueMessage
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.retro99.base.ui.compose.*
import com.retro99.catalogue.domain.*
import com.retro99.catalogue.ui.add.catalogueDeviceName
import com.retro99.catalogue.ui.cover.CatalogueCover
import com.retro99.catalogue.ui.publication.catalogueMegabytes
import com.retro99.server.api.CatalogueImageModel
import com.retro99.translations.StringRes
import org.jetbrains.compose.resources.stringResource
import resources.translations.*

/** Exact §3 copy, shared with the book page and announcements. */
@Composable
fun downloadFailureText(row: CatalogueAcquisition, deviceName: String = catalogueDeviceName(), limit: Long = CatalogueAcquisitionLimits.MAX_FILE_BYTES): String {
    val unknown = stringResource(StringRes.catalogue_file_size_unknown)
    return when (row.state.failureReason) {
        AcquisitionFailureReason.Connection -> stringResource(StringRes.catalogue_failure_connection)
        AcquisitionFailureReason.TooLarge -> stringResource(StringRes.catalogue_failure_too_large, row.expectedSizeBytes?.let(::failureMegabytes) ?: unknown, failureMegabytes(limit))
        AcquisitionFailureReason.Storage -> stringResource(StringRes.catalogue_failure_storage, deviceName, row.neededBytes?.let(::failureMegabytes) ?: unknown)
        AcquisitionFailureReason.Invalid -> stringResource(StringRes.catalogue_failure_invalid)
        AcquisitionFailureReason.Protected -> stringResource(StringRes.catalogue_failure_protected)
        AcquisitionFailureReason.Refused -> stringResource(StringRes.catalogue_failure_refused, row.catalogueName)
        AcquisitionFailureReason.SignIn -> stringResource(StringRes.catalogue_failure_sign_in, row.catalogueName)
        null -> stringResource(StringRes.catalogue_failure_interrupted)
    }
}

private fun failureMegabytes(bytes: Long) = catalogueMegabytes(bytes).replace(".0 MB", " MB")

@Composable
fun CatalogueDownloadsContent(rows: List<DownloadRow>, onBack: () -> Unit = {}, onAction: (String) -> Unit = {}, modifier: Modifier = Modifier,
    deviceName: String = catalogueDeviceName(), limit: Long = CatalogueAcquisitionLimits.MAX_FILE_BYTES) {
    Column(modifier.fillMaxSize().background(Ember.colors.bg)) {
        EmberTopBar(title = stringResource(StringRes.catalogue_downloads_title), onBack = onBack)
        if (rows.isEmpty()) {
            CatalogueMessage(Icons.Outlined.Download, stringResource(StringRes.catalogue_downloads_empty_title), stringResource(StringRes.catalogue_downloads_empty_body), Modifier.padding(top = 44.dp))
        } else {
            LazyColumn(contentPadding = PaddingValues(start = 20.dp, end = 20.dp, bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                items(rows, key = { it.acquisition.requestId }) { row -> DownloadCard(row, { onAction(row.acquisition.requestId) }, deviceName, limit) }
                item { Text(stringResource(StringRes.catalogue_downloads_footer), style = Ember.type.meta.copy(fontSize = 14.sp, lineHeight = 20.sp),
                    color = Ember.colors.ink2, modifier = Modifier.padding(horizontal = 4.dp).padding(top = 4.dp)) }
            }
        }
    }
}

@Composable
private fun DownloadCard(row: DownloadRow, onAction: () -> Unit, deviceName: String, limit: Long) {
    val book = row.acquisition
    val failed = book.state is AcquisitionState.Failed || book.state == AcquisitionState.Interrupted
    val total = book.expectedSizeBytes?.takeIf { it > 0 }
    val status = when (book.state) {
        AcquisitionState.Waiting -> stringResource(StringRes.catalogue_state_waiting)
        AcquisitionState.Downloading -> if (total == null) stringResource(StringRes.catalogue_downloading_so_far, catalogueMegabytes(book.bytesSoFar))
            else stringResource(StringRes.catalogue_progress_of_size, catalogueMegabytes(book.bytesSoFar).removeSuffix(" MB"), catalogueMegabytes(total))
        AcquisitionState.Checking, AcquisitionState.Adding -> stringResource(StringRes.catalogue_state_adding)
        AcquisitionState.Done -> stringResource(StringRes.catalogue_in_library)
        else -> downloadFailureText(book, deviceName, limit)
    }
    val shape = RoundedCornerShape(18.dp)
    Row(Modifier.fillMaxWidth().background(Ember.colors.surface, shape).border(Ember.style.border, Ember.colors.line, shape).padding(14.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        CatalogueCover(book.coverReference?.let { CatalogueImageModel(book.sourceId, it) }, book.title, Modifier.size(40.dp, 58.dp), showTitle = false)
        Column(Modifier.weight(1f).semantics(mergeDescendants = true) { contentDescription = "${book.title}, $status" }, verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(book.title, style = Ember.type.meta.copy(fontSize = 17.sp, lineHeight = 21.sp, fontWeight = FontWeight.Bold), color = Ember.colors.ink, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text(status, style = Ember.type.meta.copy(fontSize = 14.sp, lineHeight = 18.sp, fontWeight = if (failed || book.state == AcquisitionState.Done) FontWeight.Bold else FontWeight.Normal),
                color = when { Ember.style.isEink -> Ember.colors.ink; failed -> Ember.colors.error; book.state == AcquisitionState.Done -> Ember.colors.success; else -> Ember.colors.ink2 })
            if (book.state == AcquisitionState.Downloading && total != null) EmberProgress(progress = (book.bytesSoFar.toFloat() / total).coerceIn(0f, 1f), modifier = Modifier.fillMaxWidth().padding(top = 3.dp), height = Ember.style.detailProgressHeight)
        }
        row.action?.let { action ->
            val text = stringResource(when (action) {
                DownloadAction.Cancel -> StringRes.catalogue_cancel
                DownloadAction.Retry -> StringRes.catalogue_retry
                DownloadAction.Dismiss -> StringRes.catalogue_dismiss
                DownloadAction.StartAgain -> StringRes.catalogue_start_again
                DownloadAction.SignIn -> StringRes.catalogue_sign_in
                DownloadAction.Open -> StringRes.catalogue_open
            })
            val label = stringResource(when (action) {
                DownloadAction.Cancel -> StringRes.catalogue_a11y_cancel_download_of
                DownloadAction.Retry -> StringRes.catalogue_a11y_retry
                DownloadAction.Dismiss -> StringRes.catalogue_a11y_dismiss
                DownloadAction.StartAgain -> StringRes.catalogue_a11y_start_again
                DownloadAction.SignIn -> StringRes.catalogue_sign_in_title
                DownloadAction.Open -> StringRes.catalogue_a11y_open
            }, if (action == DownloadAction.SignIn) book.catalogueName else book.title)
            Button(onClick = onAction, shape = CircleShape, modifier = Modifier.heightIn(min = 48.dp).semantics { contentDescription = label },
                border = if (row.filled) null else BorderStroke(Ember.style.border, Ember.colors.line),
                colors = ButtonDefaults.buttonColors(containerColor = if (row.filled) Ember.colors.accent else Ember.colors.surface, contentColor = if (row.filled) Ember.colors.onAccent else Ember.colors.ink),
                contentPadding = PaddingValues(horizontal = 14.dp, vertical = 10.dp)) {
                Text(text, style = Ember.type.meta.copy(fontSize = 16.sp, fontWeight = FontWeight.Bold))
            }
        }
    }
}
