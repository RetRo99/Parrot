package com.retro99.reader.ui.reader

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.retro99.base.ui.compose.Ember
import com.retro99.base.ui.compose.EmberBottomSheet
import com.retro99.reader.ui.navigator.TTS_SYSTEM_VOICE_KEY
import com.retro99.reader.ui.tts.NeuralVoicePackage
import com.retro99.reader.ui.tts.TtsPreparationProgress
import com.retro99.reader.ui.tts.TtsVoice
import com.retro99.translations.StringRes
import org.jetbrains.compose.resources.stringResource
import resources.translations.Res
import resources.translations.general_back
import resources.translations.general_cancel
import resources.translations.general_retry
import resources.translations.reader_tts_delete
import resources.translations.reader_tts_delete_package_dialog_message
import resources.translations.reader_tts_delete_package_dialog_title
import resources.translations.reader_tts_download
import resources.translations.reader_tts_preview_default
import resources.translations.reader_tts_stop_preview
import resources.translations.reader_tts_system_voice
import resources.translations.reader_tts_update
import resources.translations.reader_voices_delete_voice
import resources.translations.reader_voices_download_voice
import resources.translations.reader_voices_failed
import resources.translations.reader_voices_language
import resources.translations.reader_voices_licence
import resources.translations.reader_voices_natural_downloaded
import resources.translations.reader_voices_natural_downloading
import resources.translations.reader_voices_natural_downloading_unknown
import resources.translations.reader_voices_natural_header
import resources.translations.reader_voices_natural_not_downloaded
import resources.translations.reader_voices_natural_plain
import resources.translations.reader_voices_natural_update
import resources.translations.reader_voices_phone_header
import resources.translations.reader_voices_preview_voice
import resources.translations.reader_voices_system_description
import resources.translations.reader_voices_title

private const val ENGLISH_CODE = "en"
private val DOWNLOAD_STATE_ALPHA = 0.5f

/** Everything a neural voice row needs to show, derived from its package's shared state. */
private data class NeuralVoiceRowState(
    val isDownloaded: Boolean,
    val isPreparing: Boolean,
    val isDeleting: Boolean,
    val didFail: Boolean,
    val isUpdateAvailable: Boolean,
    val progress: TtsPreparationProgress?,
    val sizeMb: Int?,
)

/**
 * Bottom sheet listing natural (downloadable) voices and the phone's own voices. Downloads are
 * per voice package, so every voice of a package shares its download / update / failure state.
 */
@Composable
internal fun VoicesSheet(
    voices: List<TtsVoice>,
    selectedVoiceId: String?,
    preparingVoicePackage: NeuralVoicePackage?,
    preparationProgress: TtsPreparationProgress?,
    failedVoicePackage: NeuralVoicePackage?,
    deletingVoicePackage: NeuralVoicePackage?,
    hasAcceptedSupertonicTerms: Boolean,
    previewingVoiceKey: String?,
    isPreviewPlaying: Boolean,
    isEink: Boolean,
    onVoiceSelected: (String?) -> Unit,
    onUpdatePackage: (NeuralVoicePackage) -> Unit,
    onDeletePackage: (NeuralVoicePackage) -> Unit,
    onRetryPackage: (NeuralVoicePackage) -> Unit,
    onCancelPreparation: () -> Unit,
    onAcceptTermsAndSelect: (String) -> Unit,
    onPreviewVoice: (String?, String) -> Unit,
    onStopPreview: () -> Unit,
    onClose: () -> Unit,
) {
    val colors = Ember.colors
    val previewText = stringResource(StringRes.reader_tts_preview_default)
    val systemGroups = remember(voices) { voices.toSystemVoiceGroups() }
    val neuralVoices = remember(voices) { voices.filter { voice -> voice.isNeural } }
    val languages = remember(voices) { voices.voiceLanguages(systemGroups) }
    val selectedVoice = voices.firstOrNull { voice -> voice.id == selectedVoiceId }
    var languageCode by remember(selectedVoiceId) {
        mutableStateOf(selectedVoice?.languageCode() ?: languages.firstOrNull()?.code ?: ENGLISH_CODE)
    }
    var pendingTermsVoiceId by remember { mutableStateOf<String?>(null) }
    var packagePendingDeletion by remember { mutableStateOf<NeuralVoicePackage?>(null) }
    var showLicense by remember { mutableStateOf(false) }
    var licenseText by remember { mutableStateOf("") }
    LaunchedEffect(Unit) {
        licenseText = runCatching { Res.readBytes(SUPERTONIC_LICENSE_RESOURCE).decodeToString() }
            .getOrDefault("")
    }

    fun stateFor(voice: TtsVoice): NeuralVoiceRowState {
        val voicePackage = voice.neuralVoicePackage
        val packageVoices = neuralVoices.filter { other -> other.neuralVoicePackage == voicePackage }
        val downloaded = voicePackage != null && voices.isNeuralVoicePackageDownloaded(voicePackage)
        return NeuralVoiceRowState(
            isDownloaded = downloaded,
            isPreparing = voicePackage != null && preparingVoicePackage == voicePackage,
            isDeleting = voicePackage != null && deletingVoicePackage == voicePackage,
            didFail = voicePackage != null && failedVoicePackage == voicePackage,
            isUpdateAvailable = downloaded && voicePackage != null &&
                voices.isNeuralVoicePackageUpdateAvailable(voicePackage),
            progress = preparationProgress,
            sizeMb = packageVoices.mapNotNull { other -> other.downloadSizeBytes }
                .firstOrNull()?.toNetworkMegabytes(),
        )
    }

    val naturalVoices = neuralVoices.filter { voice -> voice.languageCode() == languageCode }
    val phoneVoices = systemGroups.firstOrNull { group -> group.languageCode == languageCode }
        ?.voices.orEmpty()

    EmberBottomSheet(onDismiss = onClose) {
        BoxWithConstraints(Modifier.fillMaxWidth()) {
            Column(
                Modifier
                    .fillMaxWidth()
                    .heightIn(max = maxHeight * 0.94f)
                    .navigationBarsPadding(),
            ) {
                Row(
                    Modifier.fillMaxWidth().padding(start = 12.dp, end = 24.dp, bottom = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    IconButton(onClick = onClose, modifier = Modifier.size(48.dp)) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            stringResource(StringRes.general_back),
                            tint = colors.ink,
                        )
                    }
                    Text(
                        stringResource(StringRes.reader_voices_title),
                        modifier = Modifier.weight(1f).padding(start = 4.dp),
                        style = MaterialTheme.typography.headlineSmall,
                        color = colors.ink,
                    )
                    LanguageChip(
                        languages = languages,
                        selectedCode = languageCode,
                        isEink = isEink,
                        onSelect = { code -> languageCode = code },
                    )
                }
                LazyColumn(
                    Modifier.fillMaxWidth().selectableGroup(),
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(
                        start = 24.dp,
                        end = 24.dp,
                        bottom = 16.dp,
                    ),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    if (naturalVoices.isNotEmpty()) {
                        item(key = "natural-header") {
                            VoicesSectionLabel(stringResource(StringRes.reader_voices_natural_header))
                        }
                        items(naturalVoices, key = { voice -> voice.id }) { voice ->
                            val rowState = stateFor(voice)
                            val voicePackage = voice.neuralVoicePackage
                            NeuralVoiceRow(
                                name = voice.displayName(),
                                state = rowState,
                                selected = voice.id == selectedVoiceId,
                                isEink = isEink,
                                isPreviewing = previewingVoiceKey == voice.id,
                                isPreviewPlaying = isPreviewPlaying,
                                onSelect = {
                                    if (
                                        voicePackage == NeuralVoicePackage.SUPERTONIC &&
                                        !hasAcceptedSupertonicTerms &&
                                        !rowState.isDownloaded
                                    ) {
                                        pendingTermsVoiceId = voice.id
                                    } else {
                                        onVoiceSelected(voice.id)
                                    }
                                },
                                onPreview = { onPreviewVoice(voice.id, previewText) },
                                onStopPreview = onStopPreview,
                                onCancel = onCancelPreparation,
                                onRetry = { voicePackage?.let(onRetryPackage) },
                                onUpdate = { voicePackage?.let(onUpdatePackage) },
                                onDelete = { packagePendingDeletion = voicePackage },
                            )
                        }
                    }
                    item(key = "phone-header") {
                        VoicesSectionLabel(stringResource(StringRes.reader_voices_phone_header))
                    }
                    item(key = "system-voice") {
                        SimpleVoiceRow(
                            name = stringResource(StringRes.reader_tts_system_voice),
                            description = stringResource(StringRes.reader_voices_system_description),
                            selected = selectedVoiceId == null,
                            isEink = isEink,
                            isPreviewing = previewingVoiceKey == TTS_SYSTEM_VOICE_KEY,
                            isPreviewPlaying = isPreviewPlaying,
                            onSelect = { onVoiceSelected(null) },
                            onPreview = { onPreviewVoice(null, previewText) },
                            onStopPreview = onStopPreview,
                        )
                    }
                    items(phoneVoices, key = { voice -> voice.id }) { voice ->
                        SimpleVoiceRow(
                            name = voice.displayName(),
                            description = voice.locale,
                            selected = voice.id == selectedVoiceId,
                            isEink = isEink,
                            isPreviewing = previewingVoiceKey == voice.id,
                            isPreviewPlaying = isPreviewPlaying,
                            onSelect = { onVoiceSelected(voice.id) },
                            onPreview = { onPreviewVoice(voice.id, previewText) },
                            onStopPreview = onStopPreview,
                        )
                    }
                    item(key = "licence") {
                        Box(
                            Modifier
                                .heightIn(min = 48.dp)
                                .clip(RoundedCornerShape(8.dp))
                                .clickable(role = Role.Button) { showLicense = true },
                            contentAlignment = Alignment.CenterStart,
                        ) {
                            Text(
                                stringResource(StringRes.reader_voices_licence),
                                color = colors.accentText,
                                fontSize = 16.sp,
                                fontWeight = FontWeight.Bold,
                                textDecoration = TextDecoration.Underline,
                            )
                        }
                    }
                }
            }
        }
    }

    pendingTermsVoiceId?.let { voiceId ->
        SupertonicTermsDialog(
            onDismiss = { pendingTermsVoiceId = null },
            onViewLicense = { showLicense = true },
            onAccept = {
                pendingTermsVoiceId = null
                onAcceptTermsAndSelect(voiceId)
            },
        )
    }

    packagePendingDeletion?.let { voicePackage ->
        val packageName = voicePackage.displayName()
        AlertDialog(
            onDismissRequest = { packagePendingDeletion = null },
            title = {
                Text(stringResource(StringRes.reader_tts_delete_package_dialog_title, packageName))
            },
            text = {
                Text(stringResource(StringRes.reader_tts_delete_package_dialog_message, packageName))
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        packagePendingDeletion = null
                        onDeletePackage(voicePackage)
                    },
                ) {
                    Text(stringResource(StringRes.reader_tts_delete), color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { packagePendingDeletion = null }) {
                    Text(stringResource(StringRes.general_cancel))
                }
            },
        )
    }

    if (showLicense) {
        SupertonicLicenseDialog(licenseText = licenseText, onDismiss = { showLicense = false })
    }
}

@Composable
private fun NeuralVoiceRow(
    name: String,
    state: NeuralVoiceRowState,
    selected: Boolean,
    isEink: Boolean,
    isPreviewing: Boolean,
    isPreviewPlaying: Boolean,
    onSelect: () -> Unit,
    onPreview: () -> Unit,
    onStopPreview: () -> Unit,
    onCancel: () -> Unit,
    onRetry: () -> Unit,
    onUpdate: () -> Unit,
    onDelete: () -> Unit,
) {
    val colors = Ember.colors
    val size = state.sizeMb
    val descriptor = when {
        state.isPreparing -> {
            val percent = (state.progress as? TtsPreparationProgress.Downloading)?.percentage
            when {
                size == null -> stringResource(StringRes.reader_voices_natural_plain)
                percent != null -> stringResource(StringRes.reader_voices_natural_downloading, size, percent)
                else -> stringResource(StringRes.reader_voices_natural_downloading_unknown, size)
            }
        }
        state.isUpdateAvailable && size != null ->
            stringResource(StringRes.reader_voices_natural_update, size)
        state.isDownloaded && size != null ->
            stringResource(StringRes.reader_voices_natural_downloaded, size)
        size != null -> stringResource(StringRes.reader_voices_natural_not_downloaded, size)
        else -> stringResource(StringRes.reader_voices_natural_plain)
    }
    VoiceCard(
        selected = selected,
        isError = state.didFail,
        isEink = isEink,
        onSelect = onSelect,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            RadioButton(selected = selected, onClick = null)
            Column(Modifier.weight(1f).padding(horizontal = 10.dp)) {
                Text(
                    name,
                    color = colors.ink,
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    if (state.didFail) stringResource(StringRes.reader_voices_failed) else descriptor,
                    color = if (state.didFail) colors.error else colors.ink2,
                    fontSize = 14.sp,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            PreviewButton(
                name = name,
                enabled = state.isDownloaded && !state.isPreparing && !state.isDeleting,
                isPreviewing = isPreviewing,
                isPreviewPlaying = isPreviewPlaying,
                isEink = isEink,
                onPreview = onPreview,
                onStopPreview = onStopPreview,
            )
            Spacer(Modifier.width(8.dp))
            when {
                state.didFail -> PillButton(stringResource(StringRes.general_retry), isEink, onRetry)
                state.isPreparing -> Unit
                state.isUpdateAvailable -> PillButton(stringResource(StringRes.reader_tts_update), isEink, onUpdate)
                state.isDownloaded -> IconButton(onClick = onDelete, modifier = Modifier.size(48.dp)) {
                    Icon(
                        Icons.Default.Delete,
                        stringResource(StringRes.reader_voices_delete_voice, name),
                        tint = colors.ink2,
                    )
                }
                else -> IconButton(onClick = onSelect, modifier = Modifier.size(48.dp)) {
                    Icon(
                        Icons.Default.Download,
                        stringResource(StringRes.reader_voices_download_voice, name),
                        tint = colors.accentText,
                    )
                }
            }
        }
        if (state.isPreparing) {
            Row(
                Modifier.fillMaxWidth().padding(top = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                val fraction = (state.progress as? TtsPreparationProgress.Downloading)?.fraction
                Box(
                    Modifier
                        .weight(1f)
                        .height(if (isEink) 8.dp else 4.dp)
                        .clip(RoundedCornerShape(2.dp))
                        .background(colors.track)
                        .then(if (isEink) Modifier.border(2.dp, colors.line, RoundedCornerShape(2.dp)) else Modifier),
                ) {
                    Box(
                        Modifier
                            .fillMaxWidth(fraction ?: 0.04f)
                            .height(if (isEink) 8.dp else 4.dp)
                            .background(if (isEink) colors.ink else colors.accent),
                    )
                }
                Box(
                    Modifier
                        .padding(start = 12.dp)
                        .heightIn(min = 48.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .clickable(role = Role.Button, onClick = onCancel)
                        .padding(horizontal = 12.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        stringResource(StringRes.general_cancel),
                        color = colors.accentText,
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Bold,
                    )
                }
            }
        }
    }
}

@Composable
private fun SimpleVoiceRow(
    name: String,
    description: String,
    selected: Boolean,
    isEink: Boolean,
    isPreviewing: Boolean,
    isPreviewPlaying: Boolean,
    onSelect: () -> Unit,
    onPreview: () -> Unit,
    onStopPreview: () -> Unit,
) {
    val colors = Ember.colors
    VoiceCard(selected = selected, isError = false, isEink = isEink, onSelect = onSelect) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            RadioButton(selected = selected, onClick = null)
            Column(Modifier.weight(1f).padding(horizontal = 10.dp)) {
                Text(
                    name,
                    color = colors.ink,
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(description, color = colors.ink2, fontSize = 14.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
            PreviewButton(
                name = name,
                enabled = true,
                isPreviewing = isPreviewing,
                isPreviewPlaying = isPreviewPlaying,
                isEink = isEink,
                onPreview = onPreview,
                onStopPreview = onStopPreview,
            )
        }
    }
}

@Composable
private fun VoiceCard(
    selected: Boolean,
    isError: Boolean,
    isEink: Boolean,
    onSelect: () -> Unit,
    content: @Composable () -> Unit,
) {
    val colors = Ember.colors
    val shape = RoundedCornerShape(20.dp)
    val borderColor = when {
        isError -> colors.error
        selected -> colors.accent
        isEink -> colors.line
        else -> colors.chipBorder
    }
    Column(
        Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(
                when {
                    isError && !isEink -> colors.error.copy(alpha = 0.12f)
                    selected && !isEink -> colors.navActive.copy(alpha = 0.5f)
                    else -> colors.surface
                },
            )
            .border(if (selected || isError || isEink) 2.dp else 1.dp, borderColor, shape)
            .selectable(selected = selected, role = Role.RadioButton, onClick = onSelect)
            .padding(horizontal = 12.dp, vertical = 10.dp),
    ) {
        content()
    }
}

@Composable
private fun PreviewButton(
    name: String,
    enabled: Boolean,
    isPreviewing: Boolean,
    isPreviewPlaying: Boolean,
    isEink: Boolean,
    onPreview: () -> Unit,
    onStopPreview: () -> Unit,
) {
    val colors = Ember.colors
    val active = isPreviewing && isPreviewPlaying
    IconButton(
        onClick = if (active) onStopPreview else onPreview,
        enabled = enabled,
        modifier = Modifier
            .size(48.dp)
            .alpha(if (enabled) 1f else DOWNLOAD_STATE_ALPHA)
            .clip(CircleShape)
            .border(if (isEink) 2.dp else 1.dp, if (isEink) colors.line else colors.chipBorder, CircleShape),
    ) {
        Icon(
            if (active) Icons.Default.Stop else Icons.Default.PlayArrow,
            contentDescription = if (active) {
                stringResource(StringRes.reader_tts_stop_preview)
            } else {
                stringResource(StringRes.reader_voices_preview_voice, name)
            },
            tint = colors.ink,
        )
    }
}

@Composable
private fun PillButton(text: String, isEink: Boolean, onClick: () -> Unit) {
    val colors = Ember.colors
    Box(
        Modifier
            .heightIn(min = 48.dp)
            .clip(CircleShape)
            .background(if (isEink) colors.ink else colors.accent)
            .clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = 18.dp),
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
private fun VoicesSectionLabel(text: String) {
    Text(
        text.uppercase(),
        modifier = Modifier.padding(top = 8.dp),
        color = Ember.colors.ink2,
        fontSize = 13.sp,
        fontWeight = FontWeight.Bold,
        letterSpacing = 1.6.sp,
    )
}

private data class VoiceLanguage(val code: String, val label: String)

@Composable
private fun LanguageChip(
    languages: List<VoiceLanguage>,
    selectedCode: String,
    isEink: Boolean,
    onSelect: (String) -> Unit,
) {
    val colors = Ember.colors
    var expanded by remember { mutableStateOf(false) }
    val label = languages.firstOrNull { language -> language.code == selectedCode }?.label
        ?: selectedCode.uppercase()
    val description = stringResource(StringRes.reader_voices_language)
    Box {
        Row(
            Modifier
                .heightIn(min = 48.dp)
                .clip(CircleShape)
                .border(if (isEink) 2.dp else 1.dp, if (isEink) colors.line else colors.chipBorder, CircleShape)
                .clickable(role = Role.DropdownList, onClickLabel = description) { expanded = true }
                .padding(start = 16.dp, end = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(label, color = colors.ink, fontSize = 16.sp, fontWeight = FontWeight.Bold, maxLines = 1)
            Icon(Icons.Default.KeyboardArrowDown, contentDescription = null, tint = colors.ink)
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            languages.forEach { language ->
                DropdownMenuItem(
                    text = { Text(language.label) },
                    onClick = {
                        expanded = false
                        onSelect(language.code)
                    },
                )
            }
        }
    }
}

private fun TtsVoice.languageCode(): String = locale.substringBefore('-').substringBefore('_').lowercase()

/** Languages that have at least one voice, labelled from the device's own voice names where known. */
private fun List<TtsVoice>.voiceLanguages(systemGroups: List<SystemVoiceGroup>): List<VoiceLanguage> {
    val labels = systemGroups.associate { group -> group.languageCode.lowercase() to group.languageLabel }
    return map { voice -> voice.languageCode() }
        .filter { code -> code.isNotBlank() }
        .distinct()
        .map { code ->
            VoiceLanguage(
                code = code,
                label = labels[code] ?: if (code == ENGLISH_CODE) "English" else code.uppercase(),
            )
        }
        .sortedBy { language -> language.label }
}
