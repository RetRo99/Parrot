package com.retro99.statistics.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.*
import androidx.compose.ui.semantics.*
import com.retro99.base.ui.compose.Ember
import com.retro99.base.ui.compose.EmberGroupCard
import com.retro99.reader.ui.recap.RecapProse
import com.retro99.reader.ui.recap.RecapSettingsSheet
import resources.translations.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.retro99.base.ui.compose.stringTextWrapper
import com.retro99.books.domain.model.BookType
import com.retro99.reader.domain.recap.RecapRequestResult
import com.retro99.statistics.ui.model.ReadingSessionUiModel
import com.retro99.translations.StringRes
import org.jetbrains.compose.resources.stringResource
import resources.translations.statistics_recap_failed_permanent
import resources.translations.statistics_recap_failed_retryable
import resources.translations.statistics_recap_generating
import resources.translations.statistics_recap_ineligible
import resources.translations.statistics_recap_none
import resources.translations.statistics_recap_not_enough
import resources.translations.statistics_recap_retry_unavailable
import resources.translations.statistics_recap_generate
import resources.translations.statistics_recap_queued
import resources.translations.statistics_recap_request_failed
import resources.translations.statistics_recap_ready
import resources.translations.statistics_recap_text_unavailable
import resources.translations.statistics_recap_sign_in
import resources.translations.statistics_recap_turn_on_hint
import resources.translations.statistics_recap_waiting_opt_in
import resources.translations.statistics_session_detail_back
import resources.translations.statistics_session_detail_duration
import resources.translations.statistics_session_detail_progress
import resources.translations.statistics_session_detail_progress_end
import resources.translations.statistics_session_detail_progress_range
import resources.translations.statistics_session_detail_time
import resources.translations.statistics_session_detail_title
import resources.translations.statistics_session_recap_title
import kotlin.math.roundToInt

/** A session's statistics and its stored recap, inside the sessions sheet. */
@Composable
internal fun SessionDetailContent(
    detail: SessionDetailState,
    onBack: () -> Unit,
    onGenerateRecap: () -> Unit,
    modifier: Modifier = Modifier,
    onSignIn: () -> Unit = {},
) {
    val session = detail.session
    var recapSettingsOpen by remember { mutableStateOf(false) }
    if (recapSettingsOpen) RecapSettingsSheet(onDismiss = { recapSettingsOpen = false })
    Column(
        modifier = modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp)
            .padding(bottom = 32.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = stringResource(StringRes.statistics_session_detail_back),
                )
            }
            Text(
                text = stringResource(StringRes.statistics_session_detail_title),
            style = Ember.type.screenTitle,
            )
        }
        Text(
            text = session.bookTitle,
            style = MaterialTheme.typography.titleMedium,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = 8.dp),
        )
        Text(
            text = stringResource(
                StringRes.statistics_session_detail_time,
                session.dateFormatted,
                session.endTimeFormatted,
            ) + (detail.recapChapterTitle?.let { " · $it" } ?: ""),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(bottom = 16.dp),
        )

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            SessionStatTile(stringResource(StringRes.recap_time_read), stringTextWrapper(session.durationFormatted), Modifier.weight(1f))
            session.pagesRead?.let { pages ->
                SessionStatTile(stringResource(StringRes.recap_pages_read), pages.toString(), Modifier.weight(1f))
            }
            session.endProgression?.let { end ->
                SessionStatTile(stringResource(StringRes.recap_ended_at), "${end.toPercent()}%", Modifier.weight(1f))
            }
        }

        if (!detail.recapsAvailable) return@Column
        HorizontalDivider(
            modifier = Modifier.padding(vertical = 16.dp),
            color = MaterialTheme.colorScheme.outlineVariant,
        )

        Text(
            text = stringResource(StringRes.recap_section_title),
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.padding(bottom = 8.dp),
        )
        SessionRecapSection(detail = detail)
        RecapGenerateAction(detail = detail, onGenerate = onGenerateRecap)
        when (detail.recap) {
            SessionRecapUiState.SignInRequired -> TextButton(onClick = onSignIn) { Text(stringResource(StringRes.recap_sign_in)) }
            SessionRecapUiState.WaitingForOptIn -> TextButton(onClick = { recapSettingsOpen = true }) { Text(stringResource(StringRes.recap_turn_on_action)) }
            else -> Unit
        }
    }
}

@Composable
private fun SessionStatTile(label: String, value: String, modifier: Modifier) {
    EmberGroupCard(modifier = modifier) {
        Column(Modifier.padding(12.dp)) {
            Text(value, style = MaterialTheme.typography.titleLarge, color = Ember.colors.ink)
            Text(label, fontSize = 13.sp, color = Ember.colors.ink2)
        }
    }
}

@Composable
private fun SessionStatRow(label: String, value: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(text = value, style = MaterialTheme.typography.bodyLarge)
    }
}

@Composable
private fun sessionProgressText(session: ReadingSessionUiModel): String? {
    val end = session.endProgression?.toPercent() ?: return null
    val start = session.startProgression?.toPercent()
    return if (start != null && start != end) {
        stringResource(StringRes.statistics_session_detail_progress_range, start, end)
    } else {
        stringResource(StringRes.statistics_session_detail_progress_end, end)
    }
}

private fun Double.toPercent(): Int = (this * 100).roundToInt().coerceIn(0, 100)

@Composable
private fun SessionRecapSection(
    detail: SessionDetailState,
) {
    when (val recap = if (detail.recapRequestResult in setOf(RecapRequestResult.QUEUED, RecapRequestResult.IN_PROGRESS))
        SessionRecapUiState.Generating else detail.recap) {
        SessionRecapUiState.Loading -> RecapMessage(stringResource(StringRes.recap_writing))
        SessionRecapUiState.Ready -> RecapMessage(stringResource(StringRes.recap_none))
        is SessionRecapUiState.None -> {
            RecapMessage(stringResource(StringRes.recap_none))
            // Audiobooks never capture recaps; the other lines would mislead.
            if (detail.session.bookType != BookType.AUDIOBOOK) {
                RecapMessage(stringResource(StringRes.statistics_recap_text_unavailable))
            }
        }
        SessionRecapUiState.Ineligible -> RecapMessage(stringResource(StringRes.recap_too_short))
        SessionRecapUiState.WaitingForOptIn ->
            RecapMessage(stringResource(StringRes.recap_turned_off))
        SessionRecapUiState.Generating -> RecapMessage(stringResource(StringRes.recap_writing))
        is SessionRecapUiState.Succeeded -> {
            RecapProse(recap.summary)
            Text(
                text = stringResource(StringRes.recap_ai_notice),
                style = MaterialTheme.typography.labelMedium,
                fontSize = 13.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 12.dp),
            )
        }
        SessionRecapUiState.NotEnough -> RecapMessage(stringResource(StringRes.recap_too_short))
        is SessionRecapUiState.FailedRetryable -> {
            RecapMessage(stringResource(StringRes.recap_failed))
        }
        is SessionRecapUiState.FailedPermanent -> {
            RecapMessage(stringResource(StringRes.recap_failed))
            if (!recap.canRetry) RecapMessage(stringResource(StringRes.statistics_recap_retry_unavailable))
        }
        SessionRecapUiState.SignInRequired -> RecapMessage(stringResource(StringRes.recap_signed_out))
        SessionRecapUiState.Offline -> RecapMessage(stringResource(StringRes.recap_offline))
        SessionRecapUiState.Limited -> RecapMessage(stringResource(StringRes.recap_limit))
        SessionRecapUiState.Expired -> RecapMessage(stringResource(StringRes.recap_expired))
    }
}

@Composable
private fun RecapGenerateAction(
    detail: SessionDetailState,
    onGenerate: () -> Unit,
) {
    when (detail.recapRequestResult) {
        RecapRequestResult.QUEUED, RecapRequestResult.IN_PROGRESS, RecapRequestResult.ALREADY_GENERATED -> Unit
        RecapRequestResult.TEXT_UNAVAILABLE -> RecapMessage(stringResource(StringRes.statistics_recap_retry_unavailable))
        RecapRequestResult.ACCOUNT_REQUIRED -> RecapMessage(stringResource(StringRes.statistics_recap_sign_in))
        null -> Unit
    }
    if (detail.recapRequestFailed) RecapMessage(stringResource(StringRes.statistics_recap_request_failed))
    if (!detail.canRequestRecap) return
    Button(
        onClick = onGenerate,
        shape = CircleShape,
        border = if (Ember.style.isEink) BorderStroke(2.dp, Ember.colors.line) else null,
        colors = ButtonDefaults.buttonColors(
            containerColor = if (Ember.style.isEink) Ember.colors.surface else Ember.colors.navActive,
            contentColor = if (Ember.style.isEink) Ember.colors.ink else Ember.colors.navActiveContent,
        ),
        modifier = Modifier.padding(top = 8.dp),
    ) {
        Text(stringResource(if (detail.recap == SessionRecapUiState.Ready) StringRes.recap_write else StringRes.recap_retry))
    }
}

@Composable
private fun RecapMessage(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
    )
}
