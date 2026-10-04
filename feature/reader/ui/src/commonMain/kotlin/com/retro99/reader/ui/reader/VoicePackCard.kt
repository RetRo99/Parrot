package com.retro99.reader.ui.reader

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.retro99.base.ui.compose.Ember
import com.retro99.reader.ui.tts.NeuralVoicePackage
import com.retro99.reader.ui.tts.TtsVoice
import com.retro99.translations.StringRes
import org.jetbrains.compose.resources.stringResource
import resources.translations.general_cancel
import resources.translations.general_retry
import resources.translations.reader_tts_update
import resources.translations.reader_voices_ai_generated
import resources.translations.reader_voices_best_quality
import resources.translations.reader_voices_delete_pack
import resources.translations.reader_voices_deleting
import resources.translations.reader_voices_download_pack
import resources.translations.reader_voices_download_pack_no_size
import resources.translations.reader_voices_downloaded
import resources.translations.reader_voices_downloading
import resources.translations.reader_voices_downloading_unknown
import resources.translations.reader_voices_failed_message
import resources.translations.reader_voices_finishing
import resources.translations.reader_voices_finishing_eink
import resources.translations.reader_voices_high_quality
import resources.translations.reader_voices_in_use_caption
import resources.translations.reader_voices_licence_link
import resources.translations.reader_voices_pack_description
import resources.translations.reader_voices_pack_kokoro
import resources.translations.reader_voices_pack_language_english
import resources.translations.reader_voices_pack_meta
import resources.translations.reader_voices_pack_meta_no_size
import resources.translations.reader_voices_pack_supertonic
import resources.translations.reader_voices_review_terms
import resources.translations.reader_voices_show_all
import resources.translations.reader_voices_show_fewer
import resources.translations.reader_voices_state_deleting
import resources.translations.reader_voices_state_downloaded
import resources.translations.reader_voices_state_downloading
import resources.translations.reader_voices_state_failed
import resources.translations.reader_voices_state_not_downloaded
import resources.translations.reader_voices_state_update
import resources.translations.reader_voices_terms_accepted
import resources.translations.reader_voices_terms_not_accepted
import resources.translations.reader_voices_update_available
import resources.translations.reader_voices_update_available_no_size
import resources.translations.reader_voices_will_use_after_download

private const val COLLAPSED_VOICE_COUNT = 3
private val CARD_RADIUS = 20.dp

/** Callbacks for the actions a pack card can trigger. */
internal data class VoicePackActions(
    val onDownload: () -> Unit,
    val onReviewTerms: () -> Unit,
    val onUpdate: () -> Unit,
    val onDelete: () -> Unit,
    val onRetry: () -> Unit,
    val onCancel: () -> Unit,
    val onOpenLicence: () -> Unit,
)

@Composable
internal fun NeuralVoicePackage.shortName(): String = when (this) {
    NeuralVoicePackage.KOKORO -> stringResource(StringRes.reader_voices_pack_kokoro)
    NeuralVoicePackage.SUPERTONIC -> stringResource(StringRes.reader_voices_pack_supertonic)
}

/**
 * One natural-voice pack. The download / update / delete state and its single action live in the
 * header; the voice rows below only switch between selectable and unavailable.
 */
@Composable
internal fun VoicePackCard(
    voicePackage: NeuralVoicePackage,
    voices: List<TtsVoice>,
    state: PackUiState,
    hasAcceptedTerms: Boolean,
    selectedVoiceId: String?,
    pendingVoiceId: String?,
    previewingVoiceKey: String?,
    isPreviewPlaying: Boolean,
    isEink: Boolean,
    actions: VoicePackActions,
    onVoiceClick: (TtsVoice) -> Unit,
    onPreview: (TtsVoice) -> Unit,
    onStopPreview: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = Ember.colors
    val shape = RoundedCornerShape(CARD_RADIUS)
    val packName = voicePackage.shortName()
    val qualityLabel = if (voicePackage == NeuralVoicePackage.SUPERTONIC) {
        stringResource(StringRes.reader_voices_best_quality)
    } else {
        stringResource(StringRes.reader_voices_high_quality)
    }
    val stateLabel = stringResource(
        when (state) {
            is PackUiState.NotDownloaded, is PackUiState.TermsRequired ->
                StringRes.reader_voices_state_not_downloaded
            is PackUiState.Downloading, PackUiState.Finishing ->
                StringRes.reader_voices_state_downloading
            PackUiState.Downloaded -> StringRes.reader_voices_state_downloaded
            is PackUiState.UpdateAvailable -> StringRes.reader_voices_state_update
            PackUiState.Failed -> StringRes.reader_voices_state_failed
            PackUiState.Deleting -> StringRes.reader_voices_state_deleting
        },
    )
    val groupDescription =
        stringResource(StringRes.reader_voices_pack_description, packName, qualityLabel, stateLabel)
    val selectedIndex = voices.indexOfFirst { voice -> voice.id == selectedVoiceId }
    var expanded by remember { mutableStateOf(selectedIndex >= COLLAPSED_VOICE_COUNT) }
    val visibleVoices = if (expanded) voices else voices.take(COLLAPSED_VOICE_COUNT)
    val selectable = state.areVoicesSelectable

    Column(
        modifier
            .fillMaxWidth()
            .clip(shape)
            .background(colors.surface)
            .border(if (isEink) 2.dp else 1.dp, if (isEink) colors.ink else colors.line, shape)
            .semantics { contentDescription = groupDescription },
    ) {
        PackHeader(
            voicePackage = voicePackage,
            packName = packName,
            qualityLabel = qualityLabel,
            voiceCount = voices.size,
            sizeMb = voices.firstNotNullOfOrNull { voice -> voice.downloadSizeBytes }
                ?.toNetworkMegabytes(),
            state = state,
            hasAcceptedTerms = hasAcceptedTerms,
            isEink = isEink,
            actions = actions,
        )
        Divider(isEink)
        visibleVoices.forEach { voice ->
            VoiceRow(
                title = voice.displayName(),
                caption = when {
                    voice.id == pendingVoiceId ->
                        stringResource(StringRes.reader_voices_will_use_after_download)
                    voice.id == selectedVoiceId ->
                        stringResource(StringRes.reader_voices_in_use_caption)
                    else -> null
                },
                captionIsAccent = voice.id == pendingVoiceId,
                selected = voice.id == selectedVoiceId,
                available = selectable,
                isEink = isEink,
                isPreviewing = previewingVoiceKey == voice.id,
                isPreviewPlaying = isPreviewPlaying,
                onSelect = { onVoiceClick(voice) },
                onPreview = { onPreview(voice) },
                onStopPreview = onStopPreview,
            )
        }
        if (voices.size > COLLAPSED_VOICE_COUNT) {
            Divider(isEink)
            Row(
                Modifier
                    .fillMaxWidth()
                    .heightIn(min = 48.dp)
                    .clickable(role = Role.Button) { expanded = !expanded }
                    .padding(horizontal = 20.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    if (expanded) {
                        stringResource(StringRes.reader_voices_show_fewer)
                    } else {
                        stringResource(StringRes.reader_voices_show_all, voices.size)
                    },
                    color = colors.accentText,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Bold,
                )
                Icon(
                    if (expanded) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown,
                    contentDescription = null,
                    tint = colors.accentText,
                )
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun PackHeader(
    voicePackage: NeuralVoicePackage,
    packName: String,
    qualityLabel: String,
    voiceCount: Int,
    sizeMb: Int?,
    state: PackUiState,
    hasAcceptedTerms: Boolean,
    isEink: Boolean,
    actions: VoicePackActions,
) {
    val colors = Ember.colors
    val isSupertonic = voicePackage == NeuralVoicePackage.SUPERTONIC
    val language = stringResource(StringRes.reader_voices_pack_language_english)
    Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier
                    .size(40.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(if (isEink) colors.ink else colors.navActive),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    packName.take(1),
                    style = Ember.type.cardTitle,
                    color = if (isEink) colors.surface else colors.navActiveContent,
                )
            }
            Column(Modifier.weight(1f).padding(start = 12.dp)) {
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.Center,
                ) {
                    Text(
                        packName,
                        style = Ember.type.cardTitle.copy(fontSize = 19.sp),
                        color = colors.ink,
                        modifier = Modifier.align(Alignment.CenterVertically),
                    )
                    QualityPill(
                        text = qualityLabel,
                        filled = isSupertonic,
                        isEink = isEink,
                        modifier = Modifier.align(Alignment.CenterVertically),
                    )
                }
                Text(
                    if (sizeMb != null) {
                        stringResource(StringRes.reader_voices_pack_meta, voiceCount, sizeMb, language)
                    } else {
                        stringResource(StringRes.reader_voices_pack_meta_no_size, voiceCount, language)
                    },
                    color = colors.ink2,
                    fontSize = 14.sp,
                )
            }
        }
        if (isSupertonic) {
            Text(
                "✦ " + stringResource(StringRes.reader_voices_ai_generated),
                color = colors.ink,
                fontSize = 14.sp,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                if (hasAcceptedTerms) {
                    stringResource(StringRes.reader_voices_terms_accepted)
                } else {
                    stringResource(StringRes.reader_voices_terms_not_accepted)
                },
                color = colors.ink2,
                fontSize = 14.sp,
            )
        }
        PackStateBlock(state = state, isEink = isEink)
        val showLicence = isSupertonic && state !is PackUiState.Downloading &&
            state !is PackUiState.Failed && state != PackUiState.Finishing &&
            state != PackUiState.Deleting
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            PackActions(state = state, actions = actions, isEink = isEink)
            if (showLicence) {
                LinkButton(stringResource(StringRes.reader_voices_licence_link), actions.onOpenLicence)
            }
        }
    }
}

/** Progress bar and status text that describe the pack's current state. */
@Composable
private fun PackStateBlock(state: PackUiState, isEink: Boolean) {
    val colors = Ember.colors
    when (state) {
        is PackUiState.Downloading -> {
            PackProgressBar(fraction = state.percent?.let { percent -> percent / 100f }, isEink = isEink)
            StatusText(
                if (state.percent != null && state.downloadedMb != null && state.totalMb != null) {
                    stringResource(
                        StringRes.reader_voices_downloading,
                        state.percent,
                        state.downloadedMb,
                        state.totalMb,
                    )
                } else {
                    stringResource(StringRes.reader_voices_downloading_unknown)
                },
            )
        }
        PackUiState.Finishing -> {
            PackProgressBar(fraction = null, isEink = isEink)
            StatusText(
                if (isEink) {
                    stringResource(StringRes.reader_voices_finishing_eink)
                } else {
                    stringResource(StringRes.reader_voices_finishing)
                },
            )
        }
        PackUiState.Deleting -> {
            PackProgressBar(fraction = null, isEink = isEink)
            StatusText(stringResource(StringRes.reader_voices_deleting))
        }
        PackUiState.Downloaded ->
            StatusText(stringResource(StringRes.reader_voices_downloaded), color = colors.accentText)
        is PackUiState.UpdateAvailable -> StatusText(
            if (state.updateSizeMb != null) {
                stringResource(StringRes.reader_voices_update_available, state.updateSizeMb)
            } else {
                stringResource(StringRes.reader_voices_update_available_no_size)
            },
            color = colors.accentText,
        )
        PackUiState.Failed -> Box(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(12.dp))
                .background(if (isEink) colors.surface else colors.error.copy(alpha = 0.14f))
                .border(if (isEink) 2.dp else 1.dp, colors.error, RoundedCornerShape(12.dp))
                .padding(12.dp),
        ) {
            Text(
                stringResource(StringRes.reader_voices_failed_message),
                color = if (isEink) colors.ink else colors.error,
                fontSize = 14.sp,
            )
        }
        is PackUiState.NotDownloaded, is PackUiState.TermsRequired -> Unit
    }
}

@Composable
private fun PackActions(state: PackUiState, actions: VoicePackActions, isEink: Boolean) {
    val colors = Ember.colors
    when (state) {
        is PackUiState.NotDownloaded -> PillButton(
            if (state.sizeMb != null) {
                stringResource(StringRes.reader_voices_download_pack, state.sizeMb)
            } else {
                stringResource(StringRes.reader_voices_download_pack_no_size)
            },
            isEink,
            actions.onDownload,
        )
        is PackUiState.TermsRequired ->
            PillButton(stringResource(StringRes.reader_voices_review_terms), isEink, actions.onReviewTerms)
        is PackUiState.Downloading ->
            OutlineButton(stringResource(StringRes.general_cancel), colors.ink, isEink, actions.onCancel)
        PackUiState.Downloaded -> OutlineButton(
            stringResource(StringRes.reader_voices_delete_pack) + "…",
            colors.destructive,
            isEink,
            actions.onDelete,
        )
        is PackUiState.UpdateAvailable -> {
            PillButton(stringResource(StringRes.reader_tts_update), isEink, actions.onUpdate)
            OutlineButton(
                stringResource(StringRes.reader_voices_delete_pack) + "…",
                colors.destructive,
                isEink,
                actions.onDelete,
            )
        }
        PackUiState.Failed -> {
            PillButton(stringResource(StringRes.general_retry), isEink, actions.onRetry)
            OutlineButton(stringResource(StringRes.general_cancel), colors.ink, isEink, actions.onCancel)
        }
        PackUiState.Finishing, PackUiState.Deleting -> Unit
    }
}

@Composable
private fun StatusText(text: String, color: Color = Ember.colors.ink) {
    Text(text, color = color, fontSize = 14.sp, fontWeight = FontWeight.Bold)
}

@Composable
private fun QualityPill(text: String, filled: Boolean, isEink: Boolean, modifier: Modifier = Modifier) {
    val colors = Ember.colors
    val background = when {
        isEink && filled -> colors.ink
        isEink -> colors.surface
        filled -> colors.accent
        else -> colors.navActive
    }
    val content = when {
        isEink && filled -> colors.surface
        isEink -> colors.ink
        filled -> colors.onAccent
        else -> colors.navActiveContent
    }
    Text(
        text,
        modifier = modifier
            .clip(CircleShape)
            .background(background)
            .then(if (isEink && !filled) Modifier.border(1.5.dp, colors.ink, CircleShape) else Modifier)
            .padding(horizontal = 10.dp, vertical = 3.dp),
        color = content,
        fontSize = 12.sp,
        fontWeight = FontWeight.Bold,
    )
}

/** Determinate when [fraction] is set; otherwise an indeterminate block (static on e-ink). */
@Composable
private fun PackProgressBar(fraction: Float?, isEink: Boolean) {
    val colors = Ember.colors
    val barHeight = if (isEink) 10.dp else 6.dp
    val shape = RoundedCornerShape(barHeight)
    val fill = if (isEink) colors.ink else colors.accent
    BoxWithConstraints(
        Modifier
            .fillMaxWidth()
            .height(barHeight)
            .clip(shape)
            .background(colors.track)
            .then(if (isEink) Modifier.border(1.5.dp, colors.ink, shape) else Modifier),
    ) {
        if (fraction != null) {
            Box(Modifier.fillMaxWidth(fraction.coerceIn(0.02f, 1f)).height(barHeight).background(fill))
        } else {
            val blockWidth = maxWidth * INDETERMINATE_FRACTION
            val travel: Dp = maxWidth - blockWidth
            val position = if (isEink) {
                0.5f
            } else {
                rememberInfiniteTransition(label = "pack-indeterminate").animateFloat(
                    initialValue = 0f,
                    targetValue = 1f,
                    animationSpec = infiniteRepeatable(tween(1400, easing = LinearEasing), RepeatMode.Reverse),
                    label = "pack-indeterminate-offset",
                ).value
            }
            Box(
                Modifier
                    .offset(x = travel * position)
                    .width(blockWidth)
                    .height(barHeight)
                    .background(fill),
            )
        }
    }
}

private const val INDETERMINATE_FRACTION = 0.3f

@Composable
internal fun Divider(isEink: Boolean) {
    Box(
        Modifier
            .fillMaxWidth()
            .height(if (isEink) 2.dp else 1.dp)
            .background(if (isEink) Ember.colors.ink else Ember.colors.line),
    )
}

@Composable
internal fun PillButton(
    text: String,
    isEink: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    val colors = Ember.colors
    Box(
        modifier
            .heightIn(min = 48.dp)
            .alpha(if (enabled) 1f else 0.4f)
            .clip(CircleShape)
            .background(if (isEink) colors.ink else colors.accent)
            .then(if (enabled) Modifier.clickable(role = Role.Button, onClick = onClick) else Modifier)
            .padding(horizontal = 20.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text,
            color = if (isEink) colors.surface else colors.onAccent,
            fontSize = 16.sp,
            fontWeight = FontWeight.Bold,
            maxLines = 1,
        )
    }
}

@Composable
internal fun OutlineButton(
    text: String,
    color: Color,
    isEink: Boolean,
    onClick: () -> Unit,
) {
    val colors = Ember.colors
    Box(
        Modifier
            .heightIn(min = 48.dp)
            .clip(CircleShape)
            .border(if (isEink) 2.dp else 1.dp, if (isEink) colors.ink else colors.chipBorder, CircleShape)
            .clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = 20.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(text, color = color, fontSize = 16.sp, fontWeight = FontWeight.Bold, maxLines = 1)
    }
}

@Composable
internal fun LinkButton(text: String, onClick: () -> Unit) {
    Box(
        Modifier
            .heightIn(min = 48.dp)
            .clip(RoundedCornerShape(8.dp))
            .clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = 8.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text,
            color = Ember.colors.accentText,
            fontSize = 16.sp,
            fontWeight = FontWeight.Bold,
            textDecoration = TextDecoration.Underline,
        )
    }
}
