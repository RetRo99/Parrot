package com.retro99.books.ui.positions

import androidx.compose.runtime.Composable
import com.retro99.base.ui.compose.relativeTimeText
import com.retro99.books.domain.model.BookHome
import com.retro99.books.domain.model.links.LinkedCopy
import com.retro99.books.ui.links.label
import com.retro99.books.ui.components.displayPercentOf
import com.retro99.reader.domain.model.PositionDomainModel
import com.retro99.reader.domain.positions.*
import com.retro99.reader.domain.translate.TranslationConfidence
import com.retro99.reader.domain.write.CopyWriteResult
import com.retro99.sync.domain.ObservedTime
import com.retro99.translations.StringRes
import org.jetbrains.compose.resources.stringResource
import resources.translations.*

@Composable
internal fun copyLabel(copy: LinkedCopy, state: PositionsViewState, row: CopyPositionRow? = null): String {
    val format = stringResource(when {
        copy.hasReadaloud -> StringRes.positions_format_readalong
        copy.hasAudiobook && !copy.hasEbook -> StringRes.positions_format_audio
        else -> StringRes.positions_format_ebook
    })
    val phone = state.deviceName.replaceFirstChar { it.lowercase() }
    val home = if (copy.home == BookHome.ThisDevice) stringResource(StringRes.positions_on_device, phone)
        else copy.home.label()
    val sameKindServers = state.rows.filter { it.copy.key.source == copy.key.source }.map { it.copy.serverId }.distinct()
    val serverName = state.serverNames[copy.serverId]?.takeIf { sameKindServers.size > 1 }
    val named = if (serverName == null) home else "$home ($serverName)"
    val suffix = if (row?.isConflict == true) " · " + stringResource(
        if (row.isLocalCandidate) StringRes.positions_phone_suffix else StringRes.positions_server_suffix, phone,
    ) else ""
    return "$format · $named$suffix"
}

@Composable
internal fun positionText(position: PositionDomainModel, copy: LinkedCopy): String {
    val percent = displayPercentOf(position)
    val audio = if (copy.hasAudiobook && !copy.hasEbook && !copy.hasReadaloud) position.bookTimeMs
        ?: position.audioTimestampMs?.takeIf { (position.totalChapters ?: 1) <= 1 } else null
    val chapter = position.locatorTitle?.takeIf { it.isNotBlank() }
        ?: position.chapterIndex?.let { stringResource(StringRes.position_conflict_chapter_only, it + 1) }
    if (chapter == null && audio == null) return stringResource(StringRes.positions_about, percent)
    return listOfNotNull(chapter, audio?.let(::clockTime), "$percent%").joinToString(" · ")
}

internal fun clockTime(ms: Long): String {
    val seconds = ms.coerceAtLeast(0) / 1000
    return "${seconds / 3600}:${((seconds / 60) % 60).toString().padStart(2, '0')}:${(seconds % 60).toString().padStart(2, '0')}"
}

@Composable
internal fun sourceText(row: CopyPositionRow, state: PositionsViewState): String {
    val time = ObservedTime.toEpochMillis(row.observedAt)?.let { relativeTimeText(it) }.orEmpty()
    return when (val source = row.sourceLabel) {
        is PositionSource.SetFrom -> source.copy?.let {
            stringResource(StringRes.positions_set_from, copyLabel(it, state), time)
        } ?: stringResource(StringRes.positions_set_from_unknown, time)
        PositionSource.Restored -> stringResource(StringRes.positions_restored, time)
        PositionSource.Unknown -> stringResource(StringRes.positions_unknown_source)
        PositionSource.ThisDevice, PositionSource.Server -> {
            val device = if (row.sourceLabel == PositionSource.ThisDevice)
                state.deviceName.replaceFirstChar { it.lowercase() }
            else row.position?.deviceName?.takeIf { it.isNotBlank() } ?: row.copy.home.label()
            stringResource(if (row.copy.hasAudiobook && !row.copy.hasEbook && !row.copy.hasReadaloud)
                StringRes.positions_listened_on else StringRes.positions_read_on, device, time)
        }
    }
}

@Composable
internal fun previewResultText(preview: ApplyPreview): String = when (val reason = preview.disabledReason) {
    is ApplyDisabledReason.NoTranslation -> stringResource(reason.messageResource())
    ApplyDisabledReason.NotSupported -> stringResource(StringRes.positions_cannot_update)
    null -> when (preview.warning) {
        ApplyWarning.CollapseToStart -> stringResource(StringRes.positions_jump_start)
        ApplyWarning.CollapseToEnd -> stringResource(StringRes.positions_jump_end)
        null -> preview.translated?.let { translated ->
            if (translated.confidence == TranslationConfidence.Approximate)
                stringResource(StringRes.positions_goes_approximate, displayPercentOf(translated.position))
            else stringResource(StringRes.positions_goes_reliable, positionText(translated.position, preview.target))
        } ?: stringResource(StringRes.positions_no_match)
    }
}

@Composable
internal fun writeFailureText(result: CopyWriteResult): String = when (result) {
    is CopyWriteResult.Failed -> result.error.message?.takeIf { it.isNotBlank() }
        ?: stringResource(StringRes.positions_try_again)
    is CopyWriteResult.Refused -> stringResource(StringRes.positions_cannot_update)
    CopyWriteResult.Written -> ""
}
