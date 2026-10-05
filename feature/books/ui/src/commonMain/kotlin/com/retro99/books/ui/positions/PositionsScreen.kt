package com.retro99.books.ui.positions

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.retro99.base.ui.BaseScreen
import com.retro99.base.ui.IntentDispatcher
import com.retro99.base.ui.LoadingScreen
import com.retro99.base.ui.compose.EmberTopBar
import com.retro99.base.ui.compose.relativeTimeText
import com.retro99.books.domain.model.links.LinkedCopy
import com.retro99.books.domain.model.links.CopySource
import com.retro99.books.domain.model.BookHome
import com.retro99.books.ui.links.label
import com.retro99.reader.domain.model.PositionDomainModel
import com.retro99.reader.domain.positions.ApplyPreview
import com.retro99.reader.domain.positions.ApplyWarning
import com.retro99.reader.domain.positions.CopyPositionRow
import com.retro99.reader.domain.positions.PositionSource
import com.retro99.reader.domain.translate.TranslationConfidence
import com.retro99.reader.domain.usecase.ApplyResult
import com.retro99.reader.domain.write.CopyWriteResult
import com.retro99.sync.domain.ObservedTime
import com.retro99.translations.StringRes
import org.jetbrains.compose.resources.stringResource
import org.koin.compose.viewmodel.koinViewModel
import org.koin.core.parameter.parametersOf
import resources.translations.general_back
import resources.translations.positions_about
import resources.translations.positions_applied
import resources.translations.positions_apply
import resources.translations.positions_apply_failed
import resources.translations.positions_apply_title
import resources.translations.positions_audio_position
import resources.translations.positions_duration
import resources.translations.positions_from_device
import resources.translations.positions_from_server
import resources.translations.positions_latest
import resources.translations.positions_not_supported
import resources.translations.positions_same_place
import resources.translations.positions_set_from
import resources.translations.positions_stale
import resources.translations.positions_title
import resources.translations.positions_use_this
import resources.translations.positions_warn_end
import resources.translations.positions_warn_start
import resources.translations.positions_local_candidate
import resources.translations.positions_server_candidate
import resources.translations.positions_restored
import resources.translations.positions_unknown_source
import resources.translations.positions_set_from_unknown
import resources.translations.resume_linked_source_audiobook
import resources.translations.resume_linked_source_ebook
import resources.translations.resume_linked_source_readaloud
import resources.translations.resume_linked_this_place

/** Every linked copy's position; pick one and apply it to the others (§1.3b). Minimal UI. */
@Composable
fun PositionsScreen(
    serverId: String,
    bookUuid: String,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: PositionsViewModel = koinViewModel {
        parametersOf(serverId, bookUuid, onBack)
    },
) {
    BaseScreen(
        modifier = modifier,
        viewModel = viewModel,
    ) { viewState, intentDispatcher ->
        if (viewState.isLoading) {
            LoadingScreen()
        } else {
            PositionsScreenContent(viewState, intentDispatcher)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PositionsScreenContent(
    viewState: PositionsViewState,
    intentDispatcher: IntentDispatcher<PositionsIntent>,
) {
    Scaffold(
        topBar = {
            EmberTopBar(
                title = stringResource(StringRes.positions_title),
                onBack = { intentDispatcher(PositionsIntent.OnBackClicked) },
            )
        },
    ) { padding ->
        LazyColumn(modifier = Modifier.fillMaxSize().padding(padding)) {
            items(viewState.rows, key = { row -> row.candidateId }) { row ->
                PositionRow(
                    row = row,
                    isSelected = row.candidateId == viewState.selectedKey,
                    onClick = {
                        intentDispatcher(PositionsIntent.OnRowClicked(row.candidateId))
                    },
                    onUseThis = { intentDispatcher(PositionsIntent.OnUseThisPositionClicked) },
                )
            }
        }
    }

    viewState.previews?.let { previews ->
        ModalBottomSheet(
            onDismissRequest = { intentDispatcher(PositionsIntent.OnSheetDismissed) },
        ) {
            ApplySheet(
                previews = previews,
                checkedKeys = viewState.checkedKeys,
                isApplying = viewState.isApplying,
                results = viewState.results,
                intentDispatcher = intentDispatcher,
            )
        }
    }
}

@Composable
private fun PositionRow(
    row: CopyPositionRow,
    isSelected: Boolean,
    onClick: () -> Unit,
    onUseThis: () -> Unit,
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 6.dp)
            .clickable(onClick = onClick),
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = copyLabel(row.copy),
                    style = MaterialTheme.typography.titleSmall,
                    modifier = Modifier.weight(1f),
                )
                if (row.isLatest) {
                    Text(
                        text = stringResource(StringRes.positions_latest),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
            }
            row.position?.let { position ->
                if (row.isConflict) Text(
                    stringResource(if (row.isLocalCandidate) StringRes.positions_local_candidate else StringRes.positions_server_candidate),
                    style = MaterialTheme.typography.labelMedium,
                )
                Text(text = positionText(position), style = MaterialTheme.typography.bodyMedium)
                position.deviceName?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
            }
            sourceText(row)?.let { text ->
                Text(text = text, style = MaterialTheme.typography.bodySmall)
            }
            if (row.isStale) {
                Text(
                    text = stringResource(StringRes.positions_stale),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
            row.excerpt?.let { excerpt ->
                Text(
                    text = "… ${excerpt.before} ▌${excerpt.after} …",
                    style = MaterialTheme.typography.bodySmall,
                    fontStyle = FontStyle.Italic,
                    maxLines = 3,
                )
            }
            if (isSelected && row.position != null) {
                TextButton(onClick = onUseThis) {
                    Text(stringResource(StringRes.positions_use_this))
                }
            }
        }
    }
}

@Composable
private fun ApplySheet(
    previews: List<ApplyPreview>,
    checkedKeys: Set<String>,
    isApplying: Boolean,
    results: List<ApplyResult>?,
    intentDispatcher: IntentDispatcher<PositionsIntent>,
) {
    Column(modifier = Modifier.padding(16.dp)) {
        Text(
            text = stringResource(StringRes.positions_apply_title),
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
        )
        previews.forEach { preview ->
            val key = preview.target.key.value
            Row(verticalAlignment = Alignment.CenterVertically) {
                Checkbox(
                    checked = key in checkedKeys,
                    enabled = preview.enabled && !isApplying,
                    onCheckedChange = { _ ->
                        intentDispatcher(PositionsIntent.OnTargetToggled(key))
                    },
                )
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = copyLabel(preview.target),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    previewText(preview)?.let { text ->
                        Text(text = text, style = MaterialTheme.typography.bodySmall)
                    }
                    warningText(preview)?.let { text ->
                        Text(
                            text = text,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                }
            }
            HorizontalDivider()
        }
        results?.let { applied -> ResultText(applied) }
        Row {
            Spacer(modifier = Modifier.weight(1f))
            Button(
                onClick = { intentDispatcher(PositionsIntent.OnApplyClicked) },
                enabled = !isApplying && checkedKeys.isNotEmpty(),
            ) {
                Text(stringResource(StringRes.positions_apply))
            }
            Spacer(modifier = Modifier.width(8.dp))
        }
    }
}

@Composable
private fun ResultText(results: List<ApplyResult>) {
    val updated = results.filter { result -> result.result == CopyWriteResult.Written }
    val failed = results - updated.toSet()
    if (updated.isNotEmpty()) {
        val names = updated.map { result -> result.target.home.label() }.joinToString(", ")
        Text(stringResource(StringRes.positions_applied, names))
    }
    if (failed.isNotEmpty()) {
        val names = failed.map { result -> result.target.home.label() }.joinToString(", ")
        Text(
            text = stringResource(StringRes.positions_apply_failed, names),
            color = MaterialTheme.colorScheme.error,
        )
    }
}

/** "Storyteller (read-aloud)": the copy's home and format. */
@Composable
private fun copyLabel(copy: LinkedCopy): String {
    val home = copy.home.label()
    val format = when {
        copy.hasAudiobook && !copy.hasEbook && !copy.hasReadaloud ->
            StringRes.resume_linked_source_audiobook
        copy.hasReadaloud -> StringRes.resume_linked_source_readaloud
        else -> StringRes.resume_linked_source_ebook
    }
    return stringResource(format, home)
}

/** Chapter and percent for ebooks; "2 h 13 min of 10 h 2 min" and percent for audio. */
@Composable
private fun positionText(position: PositionDomainModel): String {
    val percent = ((position.totalProgression ?: 0.0).coerceIn(0.0, 1.0) * 100).toInt()
    // Book-level time: a file offset is only the book's time for a single-file audiobook.
    val audio = position.bookTimeMs
        ?: position.audioTimestampMs?.takeIf { _ -> (position.totalChapters ?: 1) <= 1 }
    val duration = position.totalDurationMs
    if (position.locatorHref == null && audio != null && duration != null) {
        val place = stringResource(
            StringRes.positions_audio_position,
            durationText(audio),
            durationText(duration),
        )
        return "$place · $percent%"
    }
    val chapter = position.locatorTitle
    if (position.locatorHref == null) return stringResource(StringRes.positions_about, percent)
    return listOfNotNull(chapter, "$percent%").joinToString(" · ")
}

@Composable
private fun durationText(millis: Long): String {
    val minutes = millis / 60_000
    return stringResource(
        StringRes.positions_duration,
        (minutes / 60).toInt(),
        (minutes % 60).toInt(),
    )
}

@Composable
private fun sourceText(row: CopyPositionRow): String? {
    val time = ObservedTime.toEpochMillis(row.observedAt)
        ?.let { millis -> relativeTimeText(millis) }
        .orEmpty()
    return when (val source = row.sourceLabel) {
        PositionSource.ThisDevice -> stringResource(StringRes.positions_from_device, time)
        PositionSource.Server ->
            stringResource(
                StringRes.positions_from_server,
                if (row.copy.key.source == CopySource.Library) BookHome.ParrotCloud.label()
                else row.copy.home.label(),
                time,
            )
        is PositionSource.SetFrom -> source.copy?.let { copy ->
            stringResource(StringRes.positions_set_from, copyLabel(copy), time)
        } ?: stringResource(StringRes.positions_set_from_unknown, time)
        PositionSource.Restored -> stringResource(StringRes.positions_restored, time)
        PositionSource.Unknown -> if (row.position != null) stringResource(StringRes.positions_unknown_source) else null
    }
}

@Composable
private fun previewText(preview: ApplyPreview): String? {
    val translated = preview.translated ?: return null
    val percent = ((translated.position.totalProgression ?: 0.0) * 100).toInt()
    return if (translated.confidence == TranslationConfidence.Approximate) {
        stringResource(StringRes.positions_about, percent)
    } else {
        stringResource(
            StringRes.positions_same_place,
            translated.position.locatorTitle
                ?: stringResource(StringRes.resume_linked_this_place),
        )
    }
}

@Composable
private fun warningText(preview: ApplyPreview): String? {
    val name = preview.target.home.label()
    return when {
        preview.disabledReason != null ->
            stringResource(StringRes.positions_not_supported, name)
        preview.warning == ApplyWarning.CollapseToStart ->
            stringResource(StringRes.positions_warn_start, name)
        preview.warning == ApplyWarning.CollapseToEnd ->
            stringResource(StringRes.positions_warn_end, name)
        else -> null
    }
}
