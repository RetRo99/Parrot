package com.retro99.reader.ui.recap

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.retro99.base.ui.compose.Ember
import com.retro99.base.ui.compose.EmberBottomSheet
import com.retro99.reader.domain.recap.RecapSummaryParts
import com.retro99.translations.StringRes
import org.jetbrains.compose.resources.stringResource
import org.koin.compose.viewmodel.koinViewModel
import org.koin.core.parameter.parametersOf
import resources.translations.*
import kotlinx.datetime.toLocalDateTime

/** Toolbar-only entry. A seen recap remains available here. */
@Composable
internal fun ReaderRecapPill(
    bookUuid: String,
    compact: Boolean = false,
    viewModel: ReaderRecapViewModel = recapViewModel(bookUuid),
) {
    val state by viewModel.viewState.collectAsState()
    if (state.banner == null) return
    val description = stringResource(StringRes.recap_pill_description)
    val colors = Ember.colors
    val outline = if (Ember.style.isEink) Modifier.border(2.dp, colors.line, CircleShape) else Modifier
    if (compact) {
        // Too narrow for the label: keep the entry as a tinted circle.
        Box(
            modifier = Modifier
                .size(40.dp)
                .clip(CircleShape)
                .background(colors.navActive)
                .then(outline)
                .clickable(role = Role.Button, onClick = { viewModel.onIntent(ReaderRecapIntent.Open) })
                .semantics { contentDescription = description },
            contentAlignment = Alignment.Center,
        ) {
            Text(RECAP_SPARK, fontSize = 16.sp, color = colors.navActiveContent)
        }
    } else {
        Box(
            modifier = Modifier
                .height(36.dp)
                .clip(CircleShape)
                .background(colors.navActive)
                .then(outline)
                .clickable(role = Role.Button, onClick = { viewModel.onIntent(ReaderRecapIntent.Open) })
                .semantics { contentDescription = description }
                .padding(horizontal = 12.dp),
            contentAlignment = Alignment.Center,
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Text(RECAP_SPARK, fontSize = 16.sp, color = colors.navActiveContent)
                Text(
                    stringResource(StringRes.recap_last_time),
                    fontSize = 14.sp,
                    lineHeight = 18.sp,
                    fontWeight = FontWeight.Bold,
                    color = colors.navActiveContent,
                    maxLines = 1,
                )
            }
        }
    }
}

private const val RECAP_SPARK = "✦"

@Composable
private fun recapViewModel(bookUuid: String): ReaderRecapViewModel =
    koinViewModel(key = "reader_recap_$bookUuid") { parametersOf(bookUuid) }

/** No UI over the text except the explicitly presented modal sheet. */
@Composable
internal fun ReaderRecapBannerHost(bookUuid: String, audioActive: Boolean, viewModel: ReaderRecapViewModel = recapViewModel(bookUuid)) {
    val state by viewModel.viewState.collectAsState()
    val banner = state.banner ?: return
    var settingsOpen by remember { mutableStateOf(false) }
    LaunchedEffect(banner.sessionId, state.presentation) { viewModel.onIntent(ReaderRecapIntent.OnEntry(audioActive)) }
    if (settingsOpen) RecapSettingsSheet(onDismiss = { settingsOpen = false })
    if (!state.sheetOpen) return
    val title = stringResource(StringRes.recap_last_time)
    val announcement = stringResource(StringRes.recap_sheet_description,
        banner.recap?.let { recapDateTime(it.createdAt) }.orEmpty())
    LaunchedEffect(banner.sessionId) { viewModel.onIntent(ReaderRecapIntent.Visible) }
    val close = { viewModel.onIntent(ReaderRecapIntent.Dismiss) }
    EmberBottomSheet(onDismiss = close, modifier = Modifier.semantics { paneTitle = announcement }) {
        Column(Modifier.fillMaxWidth().heightIn(max = 600.dp).verticalScroll(rememberScrollState()).padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                Text("✦", color = Ember.colors.accentText, modifier = Modifier.padding(end = 12.dp))
                Text(title, style = Ember.type.screenTitle, color = Ember.colors.ink, modifier = Modifier.weight(1f))
                IconButton(onClick = close, modifier = Modifier.size(48.dp)) {
                    Icon(Icons.Default.Close, stringResource(StringRes.reader_recap_dismiss), tint = Ember.colors.ink2)
                }
            }
            banner.recap?.let { recap ->
                val chapter = recap.endChapter.title
                Text(listOfNotNull(recapDateTime(recap.createdAt),
                    recap.activeReadingMs.takeIf { it > 0 }?.let { stringResource(StringRes.recap_minutes, (it / 60_000).toInt()) },
                    chapter).joinToString(" · "), color = Ember.colors.ink2, fontSize = 13.sp)
            }
            if (banner.isWriting) {
                Text(stringResource(StringRes.recap_writing_reader), color = Ember.colors.ink,
                    modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
            } else RecapProse(banner.summary)
            Button(onClick = close, modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp),
                colors = ButtonDefaults.buttonColors(containerColor = Ember.colors.accent, contentColor = Ember.colors.onAccent)) {
                Text(stringResource(StringRes.recap_continue))
            }
            Text(stringResource(StringRes.recap_ai_notice), color = Ember.colors.ink2, fontSize = 13.sp)
            TextButton(onClick = { close(); settingsOpen = true }) {
                Text(stringResource(StringRes.recap_settings), color = Ember.colors.accentText)
            }
        }
    }
}

@Composable
fun RecapProse(text: String) {
    val parts = RecapSummaryParts.parse(text)
    Text(parts.summary, color = Ember.colors.ink, fontSize = 16.sp)
    parts.stoppedAt?.let { stopped ->
        Row(Modifier.padding(top = 12.dp).height(IntrinsicSize.Min)) {
            androidx.compose.foundation.layout.Box(Modifier.width(3.dp).fillMaxHeight()) {
                Surface(color = Ember.colors.accent, modifier = Modifier.fillMaxSize()) {}
            }
            Text(stopped, color = Ember.colors.ink, fontSize = 16.sp, fontWeight = FontWeight.SemiBold,
                modifier = Modifier.padding(start = 12.dp))
        }
    }
}

@Composable
private fun recapDateTime(epochMs: Long): String {
    val zone = kotlinx.datetime.TimeZone.currentSystemDefault()
    val instant = kotlin.time.Instant.fromEpochMilliseconds(epochMs)
    val dateTime = instant.toLocalDateTime(zone)
    val today = kotlin.time.Clock.System.now().toLocalDateTime(zone).date
    val day = when (today.toEpochDays() - dateTime.date.toEpochDays()) {
        0L -> stringResource(StringRes.recap_today)
        1L -> stringResource(StringRes.recap_yesterday)
        else -> dateTime.date.toString()
    }
    return "$day, ${dateTime.hour.toString().padStart(2, '0')}:${dateTime.minute.toString().padStart(2, '0')}"
}
