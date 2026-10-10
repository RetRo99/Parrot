package com.retro99.reader.ui.reader

import com.retro99.reader.domain.tts.preparedAudioSizeLabel
import com.retro99.reader.ui.tts.TtsChapterPreparationFailure
import com.retro99.translations.StringRes
import org.jetbrains.compose.resources.StringResource
import resources.translations.general_cancel
import resources.translations.general_retry
import resources.translations.reader_tts_delete
import resources.translations.reader_tts_prepared_chapter_audio_length
import resources.translations.reader_tts_prepared_chapter_audio_length_hours
import resources.translations.reader_tts_prepared_chapter_audio_length_short
import resources.translations.reader_tts_prepared_chapter_continue
import resources.translations.reader_tts_prepared_chapter_estimate
import resources.translations.reader_tts_prepared_chapter_estimate_hours
import resources.translations.reader_tts_prepared_chapter_estimate_short
import resources.translations.reader_tts_prepared_chapter_failed
import resources.translations.reader_tts_prepared_chapter_failed_space
import resources.translations.reader_tts_prepared_chapter_hint
import resources.translations.reader_tts_prepared_chapter_other_busy
import resources.translations.reader_tts_prepared_chapter_other_settings
import resources.translations.reader_tts_prepared_chapter_other_speed
import resources.translations.reader_tts_prepared_chapter_partly
import resources.translations.reader_tts_prepared_chapter_prepare
import resources.translations.reader_tts_prepared_chapter_prepare_again
import resources.translations.reader_tts_prepared_chapter_progress
import resources.translations.reader_tts_prepared_chapter_progress_left
import resources.translations.reader_tts_prepared_chapter_progress_left_hours
import resources.translations.reader_tts_prepared_chapter_progress_left_short
import resources.translations.reader_tts_prepared_chapter_ready
import resources.translations.reader_tts_prepared_chapter_voice_pack
import resources.translations.reader_tts_prepared_chapter_voices
import resources.translations.reader_tts_prepared_cloud_download
import resources.translations.reader_tts_prepared_cloud_manage_storage
import resources.translations.reader_tts_prepared_chapter_voice_terms
import kotlin.math.roundToInt

/** One button the row offers. The card renders them in the order given. */
internal enum class PreparedChapterAction(val label: StringResource, val isDestructive: Boolean = false) {
    PREPARE(StringRes.reader_tts_prepared_chapter_prepare),
    PREPARE_AGAIN(StringRes.reader_tts_prepared_chapter_prepare_again),
    CONTINUE(StringRes.reader_tts_prepared_chapter_continue),
    CANCEL(StringRes.general_cancel),
    DELETE(StringRes.reader_tts_delete, isDestructive = true),
    RETRY(StringRes.general_retry),
    OPEN_VOICES(StringRes.reader_tts_prepared_chapter_voices),

    /** Fetch a chapter another device prepared. Only ever on request. */
    DOWNLOAD(StringRes.reader_tts_prepared_cloud_download),

    /** To the cloud account screen, where the allowance and its breakdown are. */
    MANAGE_STORAGE(StringRes.reader_tts_prepared_cloud_manage_storage),
}

/**
 * The text and the actions of one row state, decided outside the composable so every state
 * can be asserted in a test: this project has no Compose render-test harness.
 */
internal data class PreparedChapterRowUi(
    val status: StringResource,
    val statusArgs: List<Any> = emptyList(),
    /** Fraction for the progress bar; null means no bar. */
    val progress: Float? = null,
    val isFailure: Boolean = false,
    val actions: List<PreparedChapterAction> = emptyList(),
    /** A second, quieter line under the status; null means none. */
    val detail: StringResource? = null,
    val detailArgs: List<Any> = emptyList(),
)

/** Bytes as the row says them; the same wording the Settings total uses. */
internal fun preparedChapterSizeLabel(bytes: Long): String = preparedAudioSizeLabel(bytes)

/** "1", "1.25": the speed as the sheet writes it, without a trailing zero. */
internal fun preparedRateLabel(rate: Float): String {
    val hundredths = (rate * 100).roundToInt()
    val whole = hundredths / 100
    val remainder = hundredths % 100
    return when {
        remainder == 0 -> whole.toString()
        remainder % 10 == 0 -> "$whole.${remainder / 10}"
        else -> "$whole.${remainder.toString().padStart(2, '0')}"
    }
}

/**
 * @param voiceLabel name of the voice prepared audio was made for, when it is still known.
 * @param estimate what preparing this chapter is about to cost, when the sentence count is
 *   known. Null leaves the plain hint, which says preparing takes a while without saying
 *   how long: better than a number invented from a sentence count nobody has counted yet.
 */
internal fun preparedChapterRowUi(
    state: PreparedChapterRowState,
    voiceLabel: String? = null,
    estimate: PreparedChapterEstimate? = null,
): PreparedChapterRowUi = when (state) {
    PreparedChapterRowState.NotPrepared -> PreparedChapterRowUi(
        status = notPreparedStatus(estimate),
        statusArgs = notPreparedArgs(estimate),
        actions = listOf(PreparedChapterAction.PREPARE),
        detail = audioLengthDetail(estimate?.audioMinutes),
        detailArgs = audioLengthArgs(estimate?.audioMinutes),
    )

    is PreparedChapterRowState.Preparing -> PreparedChapterRowUi(
        status = preparingStatus(state),
        statusArgs = preparingArgs(state),
        progress = if (state.total > 0) state.done.toFloat() / state.total else 0f,
        actions = listOf(PreparedChapterAction.CANCEL),
    )

    PreparedChapterRowState.PreparingAnotherChapter -> PreparedChapterRowUi(
        status = StringRes.reader_tts_prepared_chapter_other_busy,
    )

    is PreparedChapterRowState.Ready -> PreparedChapterRowUi(
        status = StringRes.reader_tts_prepared_chapter_ready,
        statusArgs = listOf(preparedChapterSizeLabel(state.bytes)),
        actions = listOf(PreparedChapterAction.DELETE),
    )

    is PreparedChapterRowState.OtherSettings -> if (voiceLabel == null) {
        PreparedChapterRowUi(
            status = StringRes.reader_tts_prepared_chapter_other_speed,
            statusArgs = listOf(preparedRateLabel(state.rate)),
            actions = listOf(PreparedChapterAction.PREPARE_AGAIN),
        )
    } else {
        PreparedChapterRowUi(
            status = StringRes.reader_tts_prepared_chapter_other_settings,
            statusArgs = listOf(voiceLabel, preparedRateLabel(state.rate)),
            actions = listOf(PreparedChapterAction.PREPARE_AGAIN),
        )
    }

    is PreparedChapterRowState.Partly -> PreparedChapterRowUi(
        status = StringRes.reader_tts_prepared_chapter_partly,
        statusArgs = listOf(state.done, state.total),
        progress = if (state.total > 0) state.done.toFloat() / state.total else 0f,
        actions = listOf(PreparedChapterAction.CONTINUE, PreparedChapterAction.DELETE),
    )

    is PreparedChapterRowState.Failed -> PreparedChapterRowUi(
        status = if (state.reason == TtsChapterPreparationFailure.NOT_ENOUGH_SPACE) {
            StringRes.reader_tts_prepared_chapter_failed_space
        } else {
            StringRes.reader_tts_prepared_chapter_failed
        },
        isFailure = true,
        actions = listOf(PreparedChapterAction.RETRY),
    )

    is PreparedChapterRowState.VoiceUnusable -> PreparedChapterRowUi(
        status = if (state.needsTerms) {
            StringRes.reader_tts_prepared_chapter_voice_terms
        } else {
            StringRes.reader_tts_prepared_chapter_voice_pack
        },
        actions = listOf(PreparedChapterAction.OPEN_VOICES),
    )
}

private fun notPreparedStatus(estimate: PreparedChapterEstimate?): StringResource = when {
    estimate == null -> StringRes.reader_tts_prepared_chapter_hint
    estimate.minutes == 0 -> StringRes.reader_tts_prepared_chapter_estimate_short
    estimate.minutes >= MINUTES_PER_HOUR -> StringRes.reader_tts_prepared_chapter_estimate_hours
    else -> StringRes.reader_tts_prepared_chapter_estimate
}

private fun notPreparedArgs(estimate: PreparedChapterEstimate?): List<Any> {
    if (estimate == null) return emptyList()
    val size = preparedChapterEstimateSizeLabel(estimate.bytes)
    return when {
        estimate.minutes == 0 -> listOf(size)
        estimate.minutes >= MINUTES_PER_HOUR -> listOf(
            estimate.minutes / MINUTES_PER_HOUR,
            estimate.minutes % MINUTES_PER_HOUR,
            size,
        )
        else -> listOf(estimate.minutes, size)
    }
}

private fun preparingStatus(state: PreparedChapterRowState.Preparing): StringResource =
    when (preparedTimeLeftLabel(state.remainingMs)) {
        null -> StringRes.reader_tts_prepared_chapter_progress
        PreparedTimeLeftLabel.UnderMinute -> StringRes.reader_tts_prepared_chapter_progress_left_short
        is PreparedTimeLeftLabel.Minutes -> StringRes.reader_tts_prepared_chapter_progress_left
        is PreparedTimeLeftLabel.Hours -> StringRes.reader_tts_prepared_chapter_progress_left_hours
    }

private fun preparingArgs(state: PreparedChapterRowState.Preparing): List<Any> =
    listOf<Any>(state.done, state.total) + when (val left = preparedTimeLeftLabel(state.remainingMs)) {
        null, PreparedTimeLeftLabel.UnderMinute -> emptyList()
        is PreparedTimeLeftLabel.Minutes -> listOf(left.minutes)
        is PreparedTimeLeftLabel.Hours -> listOf(left.hours, left.minutes)
    }

private fun audioLengthDetail(minutes: Int?): StringResource? = when {
    minutes == null -> null
    minutes == 0 -> StringRes.reader_tts_prepared_chapter_audio_length_short
    minutes >= MINUTES_PER_HOUR -> StringRes.reader_tts_prepared_chapter_audio_length_hours
    else -> StringRes.reader_tts_prepared_chapter_audio_length
}

private fun audioLengthArgs(minutes: Int?): List<Any> = when {
    minutes == null || minutes == 0 -> emptyList()
    minutes >= MINUTES_PER_HOUR -> listOf(minutes / MINUTES_PER_HOUR, minutes % MINUTES_PER_HOUR)
    else -> listOf(minutes)
}

private const val MINUTES_PER_HOUR = 60
