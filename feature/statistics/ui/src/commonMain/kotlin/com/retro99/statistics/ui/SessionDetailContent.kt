package com.retro99.statistics.ui

import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.retro99.base.ui.compose.stringTextWrapper
import com.retro99.books.domain.model.BookType
import com.retro99.reader.domain.recap.RecapEngine
import com.retro99.reader.domain.recap.RecapRequestResult
import com.retro99.statistics.ui.model.ReadingSessionUiModel
import com.retro99.translations.StringRes
import org.jetbrains.compose.resources.stringResource
import resources.translations.statistics_recap_engine_cloud
import resources.translations.statistics_recap_engine_model
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
import resources.translations.statistics_session_detail_speed
import resources.translations.statistics_session_detail_time
import resources.translations.statistics_session_detail_title
import resources.translations.statistics_session_detail_wpm
import resources.translations.statistics_session_recap_title
import kotlin.math.roundToInt

/** A session's statistics and its stored recap, inside the sessions sheet. */
@Composable
internal fun SessionDetailContent(
    detail: SessionDetailState,
    onBack: () -> Unit,
    onGenerateRecap: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val session = detail.session
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
                style = MaterialTheme.typography.headlineSmall,
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
            ),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(bottom = 16.dp),
        )

        SessionStatRow(
            label = stringResource(StringRes.statistics_session_detail_duration),
            value = stringTextWrapper(session.durationFormatted),
        )
        SessionStatRow(
            label = stringResource(StringRes.statistics_session_detail_speed),
            value = stringResource(StringRes.statistics_session_detail_wpm, session.readingSpeedWpm),
        )
        sessionProgressText(session)?.let { progress ->
            SessionStatRow(
                label = stringResource(StringRes.statistics_session_detail_progress),
                value = progress,
            )
        }

        HorizontalDivider(
            modifier = Modifier.padding(vertical = 16.dp),
            color = MaterialTheme.colorScheme.outlineVariant,
        )

        Text(
            text = stringResource(StringRes.statistics_session_recap_title),
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.padding(bottom = 8.dp),
        )
        SessionRecapSection(detail = detail)
        RecapGenerateAction(detail = detail, onGenerate = onGenerateRecap)
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
    when (val recap = detail.recap) {
        SessionRecapUiState.Loading -> CircularProgressIndicator(modifier = Modifier.size(24.dp))
        SessionRecapUiState.Ready -> RecapMessage(stringResource(StringRes.statistics_recap_queued))
        is SessionRecapUiState.None -> {
            RecapMessage(stringResource(StringRes.statistics_recap_none))
            // Audiobooks never capture recaps; the other lines would mislead.
            if (detail.session.bookType != BookType.AUDIOBOOK) {
                RecapMessage(stringResource(StringRes.statistics_recap_text_unavailable))
                if (!recap.cloudRecapsEnabled) {
                    RecapMessage(stringResource(StringRes.statistics_recap_turn_on_hint))
                }
            }
        }
        SessionRecapUiState.Ineligible -> RecapMessage(stringResource(StringRes.statistics_recap_ineligible))
        SessionRecapUiState.WaitingForOptIn ->
            RecapMessage(stringResource(StringRes.statistics_recap_waiting_opt_in))
        SessionRecapUiState.Generating -> Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
            RecapMessage(stringResource(StringRes.statistics_recap_generating))
        }
        is SessionRecapUiState.Succeeded -> {
            Text(text = recap.summary, style = MaterialTheme.typography.bodyLarge)
            Text(
                text = recapEngineLabel(recap.engineId, recap.model),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 12.dp),
            )
        }
        SessionRecapUiState.NotEnough -> RecapMessage(stringResource(StringRes.statistics_recap_not_enough))
        is SessionRecapUiState.FailedRetryable -> {
            RecapMessage(stringResource(StringRes.statistics_recap_failed_retryable))
        }
        is SessionRecapUiState.FailedPermanent -> {
            RecapMessage(stringResource(StringRes.statistics_recap_failed_permanent))
            if (!recap.canRetry) RecapMessage(stringResource(StringRes.statistics_recap_retry_unavailable))
        }
        SessionRecapUiState.SignInRequired -> RecapMessage(stringResource(StringRes.statistics_recap_sign_in))
    }
}

@Composable
private fun RecapGenerateAction(
    detail: SessionDetailState,
    onGenerate: () -> Unit,
) {
    when (detail.recapRequestResult) {
        RecapRequestResult.QUEUED -> RecapMessage(stringResource(StringRes.statistics_recap_queued))
        RecapRequestResult.IN_PROGRESS -> RecapMessage(stringResource(StringRes.statistics_recap_generating))
        RecapRequestResult.ALREADY_GENERATED -> RecapMessage(stringResource(StringRes.statistics_recap_ready))
        RecapRequestResult.TEXT_UNAVAILABLE -> RecapMessage(stringResource(StringRes.statistics_recap_retry_unavailable))
        RecapRequestResult.ACCOUNT_REQUIRED -> RecapMessage(stringResource(StringRes.statistics_recap_sign_in))
        null -> Unit
    }
    if (detail.recapRequestFailed) RecapMessage(stringResource(StringRes.statistics_recap_request_failed))
    OutlinedButton(
        onClick = onGenerate,
        enabled = detail.canRequestRecap,
        modifier = Modifier.padding(top = 8.dp),
    ) {
        if (detail.isRequestingRecap) CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
        Text(stringResource(StringRes.statistics_recap_generate))
    }
}

@Composable
private fun RecapMessage(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@Composable
private fun recapEngineLabel(engineId: String?, model: String?): String {
    val engine = when (engineId) {
        RecapEngine.CLOUD_ENGINE_ID, null -> stringResource(StringRes.statistics_recap_engine_cloud)
        else -> engineId
    }
    return model?.takeIf { it.isNotBlank() }
        ?.let { stringResource(StringRes.statistics_recap_engine_model, engine, it) }
        ?: engine
}
