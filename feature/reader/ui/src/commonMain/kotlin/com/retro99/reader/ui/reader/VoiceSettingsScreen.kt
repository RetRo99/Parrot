package com.retro99.reader.ui.reader

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.navigationevent.NavigationEventInfo
import androidx.navigationevent.compose.NavigationBackHandler
import androidx.navigationevent.compose.rememberNavigationEventState
import com.retro99.reader.ui.navigator.TTS_SYSTEM_VOICE_KEY
import com.retro99.reader.ui.tts.NeuralVoicePackage
import com.retro99.reader.ui.tts.TtsPreparationProgress
import com.retro99.reader.ui.tts.TtsSpeechRate
import com.retro99.reader.ui.tts.TtsVoice
import com.retro99.translations.StringRes
import org.jetbrains.compose.resources.stringResource
import resources.translations.Res
import resources.translations.books_detail_show_less
import resources.translations.books_detail_show_more
import resources.translations.general_back
import resources.translations.general_cancel
import resources.translations.general_close
import resources.translations.general_retry
import resources.translations.mini_player_play
import resources.translations.mini_player_stop
import resources.translations.reader_tts_agree_and_download
import resources.translations.reader_tts_ai_generated_disclosure
import resources.translations.reader_tts_best_quality
import resources.translations.reader_tts_choose_voice
import resources.translations.reader_tts_delete
import resources.translations.reader_tts_delete_failed
import resources.translations.reader_tts_delete_package_dialog_message
import resources.translations.reader_tts_delete_package_dialog_title
import resources.translations.reader_tts_deleting_voices
import resources.translations.reader_tts_device
import resources.translations.reader_tts_device_default_description
import resources.translations.reader_tts_download
import resources.translations.reader_tts_download_failed
import resources.translations.reader_tts_download_to_preview
import resources.translations.reader_tts_downloading_progress
import resources.translations.reader_tts_downloading_unknown_size
import resources.translations.reader_tts_faster
import resources.translations.reader_tts_finalizing_voice_pack
import resources.translations.reader_tts_high_quality
import resources.translations.reader_tts_kokoro_pack_name
import resources.translations.reader_tts_kokoro_tab
import resources.translations.reader_tts_manage
import resources.translations.reader_tts_model_license_loading
import resources.translations.reader_tts_model_license_title
import resources.translations.reader_tts_neural_benefit
import resources.translations.reader_tts_neural_voice_pack_downloaded
import resources.translations.reader_tts_neural_voice_pack_downloaded_compact
import resources.translations.reader_tts_neural_voice_pack_not_downloaded
import resources.translations.reader_tts_neural_voice_pack_not_downloaded_compact
import resources.translations.reader_tts_update
import resources.translations.reader_tts_update_available_message
import resources.translations.reader_tts_update_available_title
import resources.translations.reader_tts_update_later
import resources.translations.reader_tts_update_now
import resources.translations.reader_tts_neural_voices_description
import resources.translations.reader_tts_normal
import resources.translations.reader_tts_on_device
import resources.translations.reader_tts_pitch
import resources.translations.reader_tts_preparing
import resources.translations.reader_tts_preparing_progress
import resources.translations.reader_tts_preview
import resources.translations.reader_tts_preview_default
import resources.translations.reader_tts_preview_text
import resources.translations.reader_tts_preview_voice
import resources.translations.reader_tts_reset
import resources.translations.reader_tts_selected_voice_label
import resources.translations.reader_tts_slower
import resources.translations.reader_tts_speed
import resources.translations.reader_tts_stop_preview
import resources.translations.reader_tts_supertonic_pack_name
import resources.translations.reader_tts_supertonic_tab
import resources.translations.reader_tts_supertonic_terms_accepted
import resources.translations.reader_tts_supertonic_terms_message
import resources.translations.reader_tts_supertonic_terms_required
import resources.translations.reader_tts_supertonic_terms_title
import resources.translations.reader_tts_system_source
import resources.translations.reader_tts_system_voice
import resources.translations.reader_tts_view_full_license
import resources.translations.reader_tts_voice_offline_details
import resources.translations.reader_tts_voice_settings
import kotlin.math.abs
import kotlin.math.roundToInt

@Composable
internal fun VoiceSettingsScreen(
    voices: List<TtsVoice>,
    selectedVoiceId: String?,
    isPreparing: Boolean,
    preparingVoicePackage: NeuralVoicePackage?,
    preparationProgress: TtsPreparationProgress?,
    failedVoicePackage: NeuralVoicePackage?,
    deletingVoicePackage: NeuralVoicePackage?,
    failedVoicePackageDeletion: NeuralVoicePackage?,
    hasAcceptedSupertonicTerms: Boolean,
    rate: Float,
    pitch: Float,
    previewingVoiceKey: String?,
    isPreviewPlaying: Boolean,
    onVoiceSelected: (String?) -> Unit,
    onDownloadNeuralVoicePackage: (NeuralVoicePackage) -> Unit,
    onUpdateNeuralVoicePackage: (NeuralVoicePackage) -> Unit,
    onDeleteNeuralVoicePackage: (NeuralVoicePackage) -> Unit,
    onRetryVoicePreparation: (NeuralVoicePackage) -> Unit,
    onAcceptSupertonicTermsAndDownload: () -> Unit,
    onPreviewVoice: (String?, String) -> Unit,
    onStopPreview: () -> Unit,
    onRateChanged: (Float) -> Unit,
    onPitchChanged: (Float) -> Unit,
    onClose: () -> Unit,
) {
    val defaultPreviewText = stringResource(StringRes.reader_tts_preview_default)
    var previewText by rememberSaveable { mutableStateOf(defaultPreviewText) }
    var isPreviewTextExpanded by rememberSaveable { mutableStateOf(false) }
    var isPitchControlExpanded by rememberSaveable { mutableStateOf(false) }
    var voicePackagePendingDeletion by remember { mutableStateOf<NeuralVoicePackage?>(null) }
    var showSupertonicTerms by remember { mutableStateOf(false) }
    var dismissedUpdatePackage by remember { mutableStateOf<NeuralVoicePackage?>(null) }
    var showSupertonicLicense by remember { mutableStateOf(false) }
    var returnToTermsAfterLicense by remember { mutableStateOf(false) }
    var supertonicLicenseText by remember { mutableStateOf("") }
    val selectedVoiceKey = selectedVoiceId ?: TTS_SYSTEM_VOICE_KEY
    val neuralVoices = remember(voices) {
        voices.filter { voice -> voice.isNeural }
    }
    val neuralVoicesByPackage = remember(neuralVoices) {
        neuralVoices
            .groupBy { voice -> voice.neuralVoicePackage }
            .mapNotNull { (voicePackage, packageVoices) ->
                voicePackage?.let { packageId -> packageId to packageVoices }
            }
            .toMap()
    }
    val selectedVoice = voices.firstOrNull { voice -> voice.id == selectedVoiceId }
    val attentionVoicePackage = preparingVoicePackage
        ?: deletingVoicePackage
        ?: failedVoicePackage
        ?: failedVoicePackageDeletion
    val selectedVoiceNeedsPackage = voices
        .firstOrNull { voice -> voice.id == selectedVoiceId }
        ?.needsDownload == true
    val selectedVoiceSource = when (selectedVoice?.neuralVoicePackage) {
        NeuralVoicePackage.KOKORO -> VoiceSource.KOKORO
        NeuralVoicePackage.SUPERTONIC -> VoiceSource.SUPERTONIC
        null -> VoiceSource.DEVICE
    }
    var selectedVoiceSourceKey by rememberSaveable {
        mutableStateOf(selectedVoiceSource.name)
    }
    val visibleVoiceSource = VoiceSource.entries
        .firstOrNull { source -> source.name == selectedVoiceSourceKey }
        ?: selectedVoiceSource
    val visibleVoicePackage = visibleVoiceSource.neuralVoicePackage
    val visibleNeuralVoices = visibleVoicePackage
        ?.let { voicePackage -> neuralVoicesByPackage[voicePackage] }
        .orEmpty()
    val systemVoiceGroups = remember(voices) {
        voices.toSystemVoiceGroups()
    }
    val selectedSystemLanguage = systemVoiceGroups
        .firstOrNull { group ->
            group.voices.any { voice -> voice.id == selectedVoiceId }
        }
        ?.languageCode
    var expandedSystemLanguages by rememberSaveable(selectedSystemLanguage) {
        mutableStateOf(listOfNotNull(selectedSystemLanguage))
    }
    val selectedVoiceName = selectedVoice?.displayName()
        ?: stringResource(StringRes.reader_tts_system_voice)
    val selectedVoiceSourceLabel = when (selectedVoice?.neuralVoicePackage) {
        NeuralVoicePackage.KOKORO -> stringResource(StringRes.reader_tts_kokoro_tab)
        NeuralVoicePackage.SUPERTONIC -> stringResource(StringRes.reader_tts_supertonic_tab)
        null -> stringResource(StringRes.reader_tts_system_source)
    }
    val selectedVoiceDescription = when {
        selectedVoice?.isNeural == true -> selectedVoice
            .neuralVoiceDetails()
            ?.let { details ->
                stringResource(StringRes.reader_tts_voice_offline_details, details)
            }
            ?: stringResource(StringRes.reader_tts_neural_voices_description)

        selectedVoice != null -> stringResource(StringRes.reader_tts_on_device)
        else -> stringResource(StringRes.reader_tts_device_default_description)
    }
    val backHandlerState = rememberNavigationEventState(NavigationEventInfo.None)

    LaunchedEffect(Unit) {
        supertonicLicenseText = runCatching {
            Res.readBytes(SUPERTONIC_LICENSE_RESOURCE).decodeToString()
        }.getOrDefault("")
    }

    LaunchedEffect(selectedVoiceId, selectedVoice?.isNeural) {
        selectedVoiceSourceKey = selectedVoiceSource.name
    }

    LaunchedEffect(attentionVoicePackage) {
        selectedVoiceSourceKey = when (attentionVoicePackage) {
            NeuralVoicePackage.KOKORO -> VoiceSource.KOKORO.name
            NeuralVoicePackage.SUPERTONIC -> VoiceSource.SUPERTONIC.name
            null -> selectedVoiceSourceKey
        }
    }

    NavigationBackHandler(
        state = backHandlerState,
        onBackCompleted = onClose,
    )

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .statusBarsPadding()
            .navigationBarsPadding(),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 4.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onClose) {
                Icon(
                    Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = stringResource(StringRes.general_back),
                )
            }
            Text(
                text = stringResource(StringRes.reader_tts_voice_settings),
                style = MaterialTheme.typography.titleLarge,
                textAlign = TextAlign.Center,
                modifier = Modifier.weight(1f),
            )
            Spacer(modifier = Modifier.width(48.dp))
        }
        HorizontalDivider()

        LazyColumn(
            modifier = Modifier
                .fillMaxWidth()
                .selectableGroup(),
            contentPadding = PaddingValues(bottom = 24.dp),
        ) {
            item {
                SelectedVoiceCard(
                    name = selectedVoiceName,
                    description = selectedVoiceDescription,
                    sourceLabel = selectedVoiceSourceLabel,
                    isPreviewing = previewingVoiceKey == selectedVoiceKey,
                    isPreviewPlaying = isPreviewPlaying,
                    isPreviewEnabled = !selectedVoiceNeedsPackage,
                    onPreview = {
                        onPreviewVoice(
                            selectedVoiceId,
                            previewText.ifBlank { defaultPreviewText },
                        )
                    },
                    onStopPreview = onStopPreview,
                )
                if (selectedVoiceNeedsPackage) {
                    Text(
                        text = stringResource(StringRes.reader_tts_download_to_preview),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                    )
                }
                PreviewTextEditor(
                    value = previewText,
                    defaultPreviewText = defaultPreviewText,
                    expanded = isPreviewTextExpanded,
                    onExpandedChange = { expanded -> isPreviewTextExpanded = expanded },
                    onValueChange = { value -> previewText = value },
                )
            }

            item {
                ReadingControls(
                    rate = rate,
                    pitch = pitch,
                    isPitchControlExpanded = isPitchControlExpanded,
                    onPitchControlExpandedChange = { expanded ->
                        isPitchControlExpanded = expanded
                    },
                    onRateChanged = onRateChanged,
                    onPitchChanged = onPitchChanged,
                )
            }

            item {
                SectionHeader(
                    title = stringResource(StringRes.reader_tts_choose_voice),
                    topPadding = 8.dp,
                )
                if (neuralVoices.isNotEmpty()) {
                    VoiceSourceSelector(
                        selectedSource = visibleVoiceSource,
                        onSourceSelected = { source ->
                            selectedVoiceSourceKey = source.name
                        },
                    )
                }
            }

            if (visibleVoicePackage != null && visibleNeuralVoices.isNotEmpty()) {
                val isPackageDownloaded = visibleNeuralVoices
                    .isNeuralVoicePackageDownloaded(visibleVoicePackage)
                val hasAcceptedTerms = visibleVoicePackage != NeuralVoicePackage.SUPERTONIC ||
                        hasAcceptedSupertonicTerms
                item(key = "neural-package-${visibleVoicePackage.name}") {
                    NeuralVoicePackageStatus(
                        voicePackage = visibleVoicePackage,
                        voiceCount = visibleNeuralVoices.size,
                        isUpdateAvailable = visibleNeuralVoices
                            .isNeuralVoicePackageUpdateAvailable(visibleVoicePackage),
                        downloadSizeMb = visibleNeuralVoices
                            .mapNotNull { voice -> voice.downloadSizeBytes }
                            .firstOrNull()
                            ?.toNetworkMegabytes(),
                        isDownloaded = isPackageDownloaded,
                        isPreparing = preparingVoicePackage == visibleVoicePackage,
                        preparationProgress = preparationProgress,
                        didPreparationFail = failedVoicePackage == visibleVoicePackage,
                        isDeleting = deletingVoicePackage == visibleVoicePackage,
                        didDeletionFail = failedVoicePackageDeletion == visibleVoicePackage,
                        hasAcceptedTerms = hasAcceptedTerms,
                        onDownload = {
                            if (visibleVoicePackage == NeuralVoicePackage.SUPERTONIC &&
                                !hasAcceptedSupertonicTerms
                            ) {
                                showSupertonicTerms = true
                            } else {
                                onDownloadNeuralVoicePackage(visibleVoicePackage)
                            }
                        },
                        onRetryPreparation = {
                            onRetryVoicePreparation(visibleVoicePackage)
                        },
                        onUpdate = {
                            onUpdateNeuralVoicePackage(visibleVoicePackage)
                        },
                        onManage = {
                            voicePackagePendingDeletion = visibleVoicePackage
                        },
                        onViewLicense = {
                            returnToTermsAfterLicense = false
                            showSupertonicLicense = true
                        },
                    )
                }
                items(visibleNeuralVoices, key = { voice -> voice.id }) { voice ->
                    VoiceRow(
                        name = voice.displayName(),
                        supportingText = voice
                            .neuralVoiceDetails()
                            ?.let { details ->
                                stringResource(
                                    StringRes.reader_tts_voice_offline_details,
                                    details,
                                )
                            },
                        selected = voice.id == selectedVoiceId,
                        isPreviewing = previewingVoiceKey == voice.id,
                        isPreviewPlaying = isPreviewPlaying,
                        enabled = isPackageDownloaded &&
                                hasAcceptedTerms &&
                                !isPreparing &&
                                deletingVoicePackage == null,
                        onSelect = { onVoiceSelected(voice.id) },
                        onPreview = {
                            onPreviewVoice(
                                voice.id,
                                previewText.ifBlank { defaultPreviewText },
                            )
                        },
                        onStopPreview = onStopPreview,
                    )
                }
            } else {
                item {
                    Spacer(modifier = Modifier.height(8.dp))
                    VoiceRow(
                        name = stringResource(StringRes.reader_tts_system_voice),
                        supportingText = stringResource(
                            StringRes.reader_tts_device_default_description,
                        ),
                        selected = selectedVoiceId == null,
                        isPreviewing = previewingVoiceKey == TTS_SYSTEM_VOICE_KEY,
                        isPreviewPlaying = isPreviewPlaying,
                        onSelect = { onVoiceSelected(null) },
                        onPreview = {
                            onPreviewVoice(
                                null,
                                previewText.ifBlank { defaultPreviewText },
                            )
                        },
                        onStopPreview = onStopPreview,
                    )
                }

                systemVoiceGroups.forEach { group ->
                    item(key = "system-language-${group.languageCode}") {
                        VoiceGroupHeader(
                            title = group.languageLabel,
                            voiceCount = group.voices.size,
                            expanded = group.languageCode in expandedSystemLanguages,
                            onClick = {
                                expandedSystemLanguages = if (
                                    group.languageCode in expandedSystemLanguages
                                ) {
                                    expandedSystemLanguages - group.languageCode
                                } else {
                                    expandedSystemLanguages + group.languageCode
                                }
                            },
                        )
                    }

                    if (group.languageCode in expandedSystemLanguages) {
                        items(group.voices, key = { voice -> voice.id }) { voice ->
                            VoiceRow(
                                name = voice.displayName(),
                                selected = voice.id == selectedVoiceId,
                                isPreviewing = previewingVoiceKey == voice.id,
                                isPreviewPlaying = isPreviewPlaying,
                                onSelect = { onVoiceSelected(voice.id) },
                                onPreview = {
                                    onPreviewVoice(
                                        voice.id,
                                        previewText.ifBlank { defaultPreviewText },
                                    )
                                },
                                onStopPreview = onStopPreview,
                            )
                        }
                    }
                }
            }

        }
    }

    voicePackagePendingDeletion?.let { voicePackage ->
        val packageName = voicePackage.displayName()
        AlertDialog(
            onDismissRequest = { voicePackagePendingDeletion = null },
            title = {
                Text(
                    stringResource(
                        StringRes.reader_tts_delete_package_dialog_title,
                        packageName,
                    ),
                )
            },
            text = {
                Text(
                    stringResource(
                        StringRes.reader_tts_delete_package_dialog_message,
                        packageName,
                    ),
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        voicePackagePendingDeletion = null
                        onDeleteNeuralVoicePackage(voicePackage)
                    },
                ) {
                    Text(
                        text = stringResource(StringRes.reader_tts_delete),
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            },
            dismissButton = {
                TextButton(onClick = { voicePackagePendingDeletion = null }) {
                    Text(stringResource(StringRes.general_cancel))
                }
            },
        )
    }

    val updateAvailablePackage = neuralVoices
        .firstOrNull { voice -> voice.updateAvailable && voice.isDownloaded }
        ?.neuralVoicePackage
    if (
        updateAvailablePackage != null &&
        dismissedUpdatePackage != updateAvailablePackage &&
        attentionVoicePackage == null
    ) {
        VoicePackUpdateDialog(
            voicePackage = updateAvailablePackage,
            onDownload = {
                dismissedUpdatePackage = updateAvailablePackage
                onUpdateNeuralVoicePackage(updateAvailablePackage)
            },
            onLater = { dismissedUpdatePackage = updateAvailablePackage },
        )
    }

    if (showSupertonicTerms) {
        SupertonicTermsDialog(
            onDismiss = { showSupertonicTerms = false },
            onViewLicense = {
                showSupertonicTerms = false
                returnToTermsAfterLicense = true
                showSupertonicLicense = true
            },
            onAccept = {
                showSupertonicTerms = false
                onAcceptSupertonicTermsAndDownload()
            },
        )
    }

    if (showSupertonicLicense) {
        SupertonicLicenseDialog(
            licenseText = supertonicLicenseText,
            onDismiss = {
                showSupertonicLicense = false
                if (returnToTermsAfterLicense) {
                    showSupertonicTerms = true
                }
                returnToTermsAfterLicense = false
            },
        )
    }
}

@Composable
private fun SelectedVoiceCard(
    name: String,
    description: String,
    sourceLabel: String,
    isPreviewing: Boolean,
    isPreviewPlaying: Boolean,
    isPreviewEnabled: Boolean,
    onPreview: () -> Unit,
    onStopPreview: () -> Unit,
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 16.dp, top = 16.dp, end = 16.dp),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.secondaryContainer,
        ),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = stringResource(StringRes.reader_tts_selected_voice_label),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSecondaryContainer,
                    )
                    Spacer(modifier = Modifier.weight(1f))
                    Text(
                        text = sourceLabel,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onPrimaryContainer,
                        modifier = Modifier
                            .clip(RoundedCornerShape(50))
                            .background(MaterialTheme.colorScheme.primaryContainer)
                            .padding(horizontal = 8.dp, vertical = 2.dp),
                    )
                }
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = name,
                    style = MaterialTheme.typography.titleLarge,
                    color = MaterialTheme.colorScheme.onSecondaryContainer,
                )
                Text(
                    text = description,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.8f),
                )
            }
            Spacer(modifier = Modifier.width(12.dp))
            SelectedVoicePreviewButton(
                isPreviewing = isPreviewing,
                isPreviewPlaying = isPreviewPlaying,
                enabled = isPreviewEnabled,
                onPreview = onPreview,
                onStopPreview = onStopPreview,
            )
        }
    }
}

@Composable
private fun SelectedVoicePreviewButton(
    isPreviewing: Boolean,
    isPreviewPlaying: Boolean,
    enabled: Boolean,
    onPreview: () -> Unit,
    onStopPreview: () -> Unit,
) {
    when {
        isPreviewing && isPreviewPlaying -> {
            Button(onClick = onStopPreview) {
                Icon(
                    imageVector = Icons.Default.Stop,
                    contentDescription = stringResource(StringRes.mini_player_stop),
                    modifier = Modifier.size(18.dp),
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(stringResource(StringRes.reader_tts_stop_preview))
            }
        }

        isPreviewing -> {
            Button(
                onClick = {},
                enabled = false,
            ) {
                CircularProgressIndicator(
                    modifier = Modifier.size(18.dp),
                    strokeWidth = 2.dp,
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(stringResource(StringRes.reader_tts_preparing))
            }
        }

        else -> {
            Button(
                onClick = onPreview,
                enabled = enabled,
            ) {
                Icon(
                    imageVector = Icons.Default.PlayArrow,
                    contentDescription = stringResource(StringRes.mini_player_play),
                    modifier = Modifier.size(20.dp),
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(stringResource(StringRes.reader_tts_preview))
            }
        }
    }
}

@Composable
private fun PreviewTextEditor(
    value: String,
    defaultPreviewText: String,
    expanded: Boolean,
    onExpandedChange: (Boolean) -> Unit,
    onValueChange: (String) -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp),
    ) {
        TextButton(onClick = { onExpandedChange(!expanded) }) {
            Icon(
                imageVector = if (expanded) {
                    Icons.Default.KeyboardArrowUp
                } else {
                    Icons.Default.KeyboardArrowDown
                },
                contentDescription = if (expanded) {
                    stringResource(StringRes.books_detail_show_less)
                } else {
                    stringResource(StringRes.books_detail_show_more)
                },
            )
            Spacer(modifier = Modifier.width(4.dp))
            Text(stringResource(StringRes.reader_tts_preview_text))
        }
        if (expanded) {
            OutlinedTextField(
                value = value,
                onValueChange = onValueChange,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 8.dp),
                placeholder = { Text(defaultPreviewText) },
                maxLines = 3,
            )
        }
    }
}

@Composable
private fun VoiceSourceSelector(
    selectedSource: VoiceSource,
    onSourceSelected: (VoiceSource) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .selectableGroup()
            .padding(4.dp),
    ) {
        VoiceSource.entries.forEach { source ->
            val selected = source == selectedSource
            Box(
                modifier = Modifier
                    .weight(1f)
                    .height(64.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(
                        if (selected) {
                            MaterialTheme.colorScheme.secondaryContainer
                        } else {
                            MaterialTheme.colorScheme.surfaceVariant
                        },
                    )
                    .selectable(
                        selected = selected,
                        onClick = { onSourceSelected(source) },
                        role = Role.Tab,
                    )
                    .padding(horizontal = 12.dp),
                contentAlignment = Alignment.Center,
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        text = source.displayName(),
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = if (selected || source.isNeural) {
                            FontWeight.SemiBold
                        } else {
                            FontWeight.Normal
                        },
                        color = when {
                            selected -> MaterialTheme.colorScheme.onSecondaryContainer
                            source.isNeural -> MaterialTheme.colorScheme.primary
                            else -> MaterialTheme.colorScheme.onSurfaceVariant
                        },
                    )
                    source.qualityLabel()?.let { qualityLabel ->
                        Spacer(modifier = Modifier.height(2.dp))
                        Text(
                            text = qualityLabel,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onPrimaryContainer,
                            modifier = Modifier
                                .clip(RoundedCornerShape(50))
                                .background(MaterialTheme.colorScheme.primaryContainer)
                                .padding(horizontal = 6.dp, vertical = 1.dp),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun NeuralVoicePackageStatus(
    voicePackage: NeuralVoicePackage,
    voiceCount: Int,
    isUpdateAvailable: Boolean,
    downloadSizeMb: Int?,
    isDownloaded: Boolean,
    isPreparing: Boolean,
    preparationProgress: TtsPreparationProgress?,
    didPreparationFail: Boolean,
    isDeleting: Boolean,
    didDeletionFail: Boolean,
    hasAcceptedTerms: Boolean,
    onDownload: () -> Unit,
    onRetryPreparation: () -> Unit,
    onUpdate: () -> Unit,
    onManage: () -> Unit,
    onViewLicense: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
    ) {
        Text(
            text = voicePackage.displayName(),
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
        )
        Spacer(modifier = Modifier.height(2.dp))
        Text(
            text = stringResource(StringRes.reader_tts_neural_benefit),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.primary,
        )
        if (voicePackage == NeuralVoicePackage.SUPERTONIC) {
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = stringResource(StringRes.reader_tts_ai_generated_disclosure),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = stringResource(
                    if (hasAcceptedTerms) {
                        StringRes.reader_tts_supertonic_terms_accepted
                    } else {
                        StringRes.reader_tts_supertonic_terms_required
                    },
                ),
                style = MaterialTheme.typography.bodySmall,
                color = if (hasAcceptedTerms) {
                    MaterialTheme.colorScheme.onSurfaceVariant
                } else {
                    MaterialTheme.colorScheme.error
                },
            )
            TextButton(onClick = onViewLicense) {
                Text(stringResource(StringRes.reader_tts_view_full_license))
            }
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = when {
                    isDownloaded && downloadSizeMb != null -> stringResource(
                        StringRes.reader_tts_neural_voice_pack_downloaded_compact,
                        voiceCount,
                        downloadSizeMb,
                    )

                    isDownloaded -> stringResource(
                        StringRes.reader_tts_neural_voice_pack_downloaded,
                        voiceCount,
                    )

                    downloadSizeMb != null -> stringResource(
                        StringRes.reader_tts_neural_voice_pack_not_downloaded_compact,
                        voiceCount,
                        downloadSizeMb,
                    )

                    else -> stringResource(
                        StringRes.reader_tts_neural_voice_pack_not_downloaded,
                        voiceCount,
                    )
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f),
            )
            when {
                isDownloaded && !isPreparing && !isDeleting -> {
                    if (isUpdateAvailable) {
                        Button(onClick = onUpdate) {
                            Text(stringResource(StringRes.reader_tts_update))
                        }
                    }
                    TextButton(onClick = onManage) {
                        Text(stringResource(StringRes.reader_tts_manage))
                    }
                }

                !isPreparing && !isDeleting -> Button(onClick = onDownload) {
                    Text(stringResource(StringRes.reader_tts_download))
                }
            }
        }
        when {
            isDeleting -> DeletingVoicePackageStatus()
            isPreparing -> TtsPreparationStatus(progress = preparationProgress)
        }
        if (didPreparationFail) {
            VoiceOperationError(
                message = stringResource(StringRes.reader_tts_download_failed),
                onRetry = onRetryPreparation,
            )
        }
        if (didDeletionFail) {
            VoiceOperationError(
                message = stringResource(StringRes.reader_tts_delete_failed),
                onRetry = onManage,
            )
        }
    }
}

@Composable
private fun VoicePackUpdateDialog(
    voicePackage: NeuralVoicePackage,
    onDownload: () -> Unit,
    onLater: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onLater,
        title = {
            Text(stringResource(StringRes.reader_tts_update_available_title))
        },
        text = {
            Text(
                stringResource(
                    StringRes.reader_tts_update_available_message,
                    voicePackage.displayName(),
                ),
            )
        },
        confirmButton = {
            TextButton(onClick = onDownload) {
                Text(stringResource(StringRes.reader_tts_update_now))
            }
        },
        dismissButton = {
            TextButton(onClick = onLater) {
                Text(stringResource(StringRes.reader_tts_update_later))
            }
        },
    )
}

@Composable
private fun SupertonicTermsDialog(
    onDismiss: () -> Unit,
    onViewLicense: () -> Unit,
    onAccept: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(stringResource(StringRes.reader_tts_supertonic_terms_title))
        },
        text = {
            Column {
                Text(stringResource(StringRes.reader_tts_supertonic_terms_message))
                TextButton(onClick = onViewLicense) {
                    Text(stringResource(StringRes.reader_tts_view_full_license))
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onAccept) {
                Text(stringResource(StringRes.reader_tts_agree_and_download))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(StringRes.general_cancel))
            }
        },
    )
}

@Composable
private fun SupertonicLicenseDialog(
    licenseText: String,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(stringResource(StringRes.reader_tts_model_license_title))
        },
        text = {
            Column(
                modifier = Modifier
                    .heightIn(max = 480.dp)
                    .verticalScroll(rememberScrollState()),
            ) {
                Text(
                    text = licenseText.ifBlank {
                        stringResource(StringRes.reader_tts_model_license_loading)
                    },
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(StringRes.general_close))
            }
        },
    )
}

@Composable
private fun TtsPreparationStatus(progress: TtsPreparationProgress?) {
    when (progress) {
        is TtsPreparationProgress.Downloading -> DownloadProgress(progress)
        TtsPreparationProgress.Finalizing, null -> FinalizingVoiceStatus()
    }
}

@Composable
private fun DownloadProgress(progress: TtsPreparationProgress.Downloading) {
    val totalBytes = progress.totalBytes
    val fraction = progress.fraction
    val percentage = progress.percentage
    val progressText = if (totalBytes != null && fraction != null && percentage != null) {
        stringResource(
            StringRes.reader_tts_downloading_progress,
            percentage,
            progress.downloadedBytes.toDownloadedMegabytes(),
            totalBytes.toNetworkMegabytes(),
        )
    } else {
        stringResource(
            StringRes.reader_tts_downloading_unknown_size,
            progress.downloadedBytes.toDownloadedMegabytes(),
        )
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 12.dp),
    ) {
        Text(
            text = progressText,
            style = MaterialTheme.typography.bodyMedium,
        )
        Spacer(modifier = Modifier.height(8.dp))
        if (fraction == null) {
            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
        } else {
            LinearProgressIndicator(
                progress = { fraction },
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

@Composable
private fun FinalizingVoiceStatus() {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 12.dp),
    ) {
        Text(
            text = stringResource(StringRes.reader_tts_finalizing_voice_pack),
            style = MaterialTheme.typography.bodyMedium,
        )
        Spacer(modifier = Modifier.height(8.dp))
        LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
    }
}

@Composable
private fun DeletingVoicePackageStatus() {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        CircularProgressIndicator(
            modifier = Modifier.size(18.dp),
            strokeWidth = 2.dp,
        )
        Spacer(modifier = Modifier.width(12.dp))
        Text(
            text = stringResource(StringRes.reader_tts_deleting_voices),
            style = MaterialTheme.typography.bodyMedium,
        )
    }
}

@Composable
private fun VoiceOperationError(
    message: String,
    onRetry: () -> Unit,
) {
    Column(modifier = Modifier.padding(top = 12.dp)) {
        Text(
            text = message,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.error,
        )
        TextButton(onClick = onRetry) {
            Text(stringResource(StringRes.general_retry))
        }
    }
}

@Composable
private fun SectionHeader(
    title: String,
    topPadding: Dp = 16.dp,
) {
    Text(
        text = title,
        style = MaterialTheme.typography.titleMedium,
        color = MaterialTheme.colorScheme.onBackground,
        modifier = Modifier.padding(start = 16.dp, top = topPadding, bottom = 8.dp),
    )
}

@Composable
private fun PreviewButton(
    isPreviewing: Boolean,
    isPreviewPlaying: Boolean,
    enabled: Boolean = true,
    onPreview: () -> Unit,
    onStopPreview: () -> Unit,
) {
    when {
        isPreviewing && isPreviewPlaying -> {
            IconButton(onClick = onStopPreview) {
                Icon(
                    imageVector = Icons.Default.Stop,
                    contentDescription = stringResource(StringRes.reader_tts_stop_preview),
                    modifier = Modifier.size(22.dp),
                )
            }
        }

        isPreviewing -> {
            Box(
                modifier = Modifier.size(48.dp),
                contentAlignment = Alignment.Center,
            ) {
                CircularProgressIndicator(
                    modifier = Modifier.size(20.dp),
                    strokeWidth = 2.dp,
                )
            }
        }

        else -> {
            IconButton(
                onClick = onPreview,
                enabled = enabled,
            ) {
                Icon(
                    imageVector = Icons.Default.PlayArrow,
                    contentDescription = stringResource(StringRes.reader_tts_preview_voice),
                    modifier = Modifier.size(24.dp),
                )
            }
        }
    }
}

@Composable
private fun VoiceRow(
    name: String,
    supportingText: String? = null,
    selected: Boolean,
    isPreviewing: Boolean,
    isPreviewPlaying: Boolean,
    enabled: Boolean = true,
    onSelect: () -> Unit,
    onPreview: () -> Unit,
    onStopPreview: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(
                if (selected) {
                    MaterialTheme.colorScheme.secondaryContainer.copy(
                        alpha = SELECTED_ROW_CONTAINER_ALPHA,
                    )
                } else {
                    MaterialTheme.colorScheme.surface.copy(alpha = 0f)
                },
            )
            .alpha(if (enabled) 1f else DISABLED_CONTENT_ALPHA)
            .selectable(
                selected = selected,
                enabled = enabled,
                onClick = onSelect,
                role = Role.RadioButton,
            )
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(
            selected = selected,
            onClick = null,
            enabled = enabled,
        )
        Spacer(modifier = Modifier.width(8.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = name,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
            )
            supportingText?.let { text ->
                Text(
                    text = text,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        PreviewButton(
            isPreviewing = isPreviewing,
            isPreviewPlaying = isPreviewPlaying,
            enabled = enabled,
            onPreview = onPreview,
            onStopPreview = onStopPreview,
        )
    }
}

@Composable
private fun VoiceGroupHeader(
    title: String,
    subtitle: String? = null,
    voiceCount: Int,
    expanded: Boolean,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(
            modifier = Modifier.weight(1f),
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleSmall,
            )
            subtitle?.let { text ->
                Text(
                    text = text,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Text(
            text = voiceCount.toString(),
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier
                .clip(RoundedCornerShape(50))
                .background(MaterialTheme.colorScheme.surfaceVariant)
                .padding(horizontal = 10.dp, vertical = 2.dp),
        )
        Spacer(modifier = Modifier.width(8.dp))
        Icon(
            imageVector = if (expanded) {
                Icons.Default.KeyboardArrowUp
            } else {
                Icons.Default.KeyboardArrowDown
            },
            contentDescription = if (expanded) {
                stringResource(StringRes.books_detail_show_less)
            } else {
                stringResource(StringRes.books_detail_show_more)
            },
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun ReadingControls(
    rate: Float,
    pitch: Float,
    isPitchControlExpanded: Boolean,
    onPitchControlExpandedChange: (Boolean) -> Unit,
    onRateChanged: (Float) -> Unit,
    onPitchChanged: (Float) -> Unit,
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        SectionHeader(
            title = stringResource(StringRes.reader_tts_speed),
            topPadding = 8.dp,
        )
        ValueSlider(
            value = rate,
            onValueChange = onRateChanged,
            label = stringResource(StringRes.reader_tts_speed),
            showRangeLabels = true,
        )
        TextButton(
            onClick = {
                onPitchControlExpandedChange(!isPitchControlExpanded)
            },
            modifier = Modifier.padding(start = 8.dp),
        ) {
            Icon(
                imageVector = if (isPitchControlExpanded) {
                    Icons.Default.KeyboardArrowUp
                } else {
                    Icons.Default.KeyboardArrowDown
                },
                contentDescription = if (isPitchControlExpanded) {
                    stringResource(StringRes.books_detail_show_less)
                } else {
                    stringResource(StringRes.books_detail_show_more)
                },
            )
            Spacer(modifier = Modifier.width(4.dp))
            Text(stringResource(StringRes.reader_tts_pitch))
        }
        if (isPitchControlExpanded) {
            ValueSlider(
                value = pitch,
                onValueChange = onPitchChanged,
                label = stringResource(StringRes.reader_tts_pitch),
            )
        }
    }
}

@Composable
private fun ValueSlider(
    value: Float,
    onValueChange: (Float) -> Unit,
    label: String,
    showRangeLabels: Boolean = false,
) {
    var dragValue by remember { mutableStateOf<Float?>(null) }
    val displayValue = dragValue ?: value
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "${formatRate(displayValue.coerceIn(MIN_SPEECH_VALUE, MAX_SPEECH_VALUE))}x",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier
                    .clip(RoundedCornerShape(50))
                    .background(MaterialTheme.colorScheme.surfaceVariant)
                    .padding(horizontal = 10.dp, vertical = 2.dp),
            )
            Spacer(modifier = Modifier.weight(1f))
            if (abs(displayValue - DEFAULT_SPEECH_VALUE) > 0.001f) {
                TextButton(onClick = { onValueChange(DEFAULT_SPEECH_VALUE) }) {
                    Text(stringResource(StringRes.reader_tts_reset))
                }
            }
        }
        Slider(
            value = displayValue.coerceIn(MIN_SPEECH_VALUE, MAX_SPEECH_VALUE),
            onValueChange = { newValue -> dragValue = newValue },
            onValueChangeFinished = {
                dragValue?.let { pendingValue -> onValueChange(pendingValue) }
                dragValue = null
            },
            valueRange = MIN_SPEECH_VALUE..MAX_SPEECH_VALUE,
            steps = SPEECH_VALUE_STEPS,
            modifier = Modifier
                .fillMaxWidth()
                .semantics { contentDescription = label },
        )
        if (showRangeLabels) {
            Row(modifier = Modifier.fillMaxWidth()) {
                Text(
                    text = stringResource(StringRes.reader_tts_slower),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(modifier = Modifier.weight(1f))
                Text(
                    text = stringResource(StringRes.reader_tts_normal),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(modifier = Modifier.weight(1f))
                Text(
                    text = stringResource(StringRes.reader_tts_faster),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

private fun formatRate(value: Float): String {
    val rounded = (value * 100).toInt() / 100.0
    return rounded.toString()
}

private fun Long.toNetworkMegabytes(): Int =
    (this / BYTES_PER_MEGABYTE).roundToInt()

private fun Long.toDownloadedMegabytes(): Int =
    (this / BYTES_PER_MEGABYTE).toInt()

internal fun List<TtsVoice>.isNeuralVoicePackageDownloaded(
    voicePackage: NeuralVoicePackage,
): Boolean {
    val packageVoices = filter { voice -> voice.neuralVoicePackage == voicePackage }
    return packageVoices.isNotEmpty() && packageVoices.all { voice -> voice.isDownloaded }
}

internal fun List<TtsVoice>.isNeuralVoicePackageUpdateAvailable(
    voicePackage: NeuralVoicePackage,
): Boolean = any { voice ->
    voice.neuralVoicePackage == voicePackage && voice.updateAvailable
}

internal fun List<TtsVoice>.toSystemVoiceGroups(): List<SystemVoiceGroup> =
    filterNot { voice -> voice.isNeural }
        .groupBy { voice -> voice.locale.substringBefore('-').ifBlank { voice.id } }
        .map { (languageCode, voices) ->
            SystemVoiceGroup(
                languageCode = languageCode,
                languageLabel = voices.first().languageLabel(),
                voices = voices.sortedBy { voice -> voice.name },
            )
        }
        .sortedBy { group -> group.languageLabel }

private fun TtsVoice.languageLabel(): String =
    name.substringBefore(" (")
        .substringBefore(" - ")

internal fun TtsVoice.displayName(): String =
    if (isNeural) name.substringBefore(" (") else name

@Composable
private fun NeuralVoicePackage.displayName(): String = when (this) {
    NeuralVoicePackage.KOKORO -> stringResource(StringRes.reader_tts_kokoro_pack_name)
    NeuralVoicePackage.SUPERTONIC -> stringResource(StringRes.reader_tts_supertonic_pack_name)
}

internal fun TtsVoice.neuralVoiceDetails(): String? {
    if (!isNeural) return null
    val rawDetails = name
        .substringAfter(" (", "")
        .substringBeforeLast(')')
        .trim()
    if (rawDetails.isEmpty()) return null

    val detailParts = rawDetails.split(' ')
    val voiceType = detailParts
        .last()
        .replaceFirstChar { character -> character.uppercase() }
    val region = detailParts
        .dropLast(1)
        .joinToString(" ")
    return listOf(region, voiceType)
        .filter { detail -> detail.isNotBlank() }
        .joinToString(" · ")
}

internal data class SystemVoiceGroup(
    val languageCode: String,
    val languageLabel: String,
    val voices: List<TtsVoice>,
)

private enum class VoiceSource(
    val neuralVoicePackage: NeuralVoicePackage?,
) {
    DEVICE(null),
    KOKORO(NeuralVoicePackage.KOKORO),
    SUPERTONIC(NeuralVoicePackage.SUPERTONIC),

    ;

    val isNeural: Boolean
        get() = neuralVoicePackage != null
}

@Composable
private fun VoiceSource.displayName(): String = when (this) {
    VoiceSource.DEVICE -> stringResource(StringRes.reader_tts_device)
    VoiceSource.KOKORO -> stringResource(StringRes.reader_tts_kokoro_tab)
    VoiceSource.SUPERTONIC -> stringResource(StringRes.reader_tts_supertonic_tab)
}

@Composable
private fun VoiceSource.qualityLabel(): String? = when (this) {
    VoiceSource.DEVICE -> null
    VoiceSource.KOKORO -> stringResource(StringRes.reader_tts_high_quality)
    VoiceSource.SUPERTONIC -> stringResource(StringRes.reader_tts_best_quality)
}

private const val MIN_SPEECH_VALUE = TtsSpeechRate.MIN
private const val MAX_SPEECH_VALUE = TtsSpeechRate.MAX
private const val DEFAULT_SPEECH_VALUE = 1.0f
private const val SPEECH_VALUE_STEPS = 14
private const val BYTES_PER_MEGABYTE = 1_000_000.0
private const val DISABLED_CONTENT_ALPHA = 0.5f
private const val SELECTED_ROW_CONTAINER_ALPHA = 0.45f
private const val SUPERTONIC_LICENSE_RESOURCE = "files/supertonic_openrail_m_license.txt"
