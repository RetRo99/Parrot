package com.retro99.reader.ui.reader

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.intl.Locale
import androidx.compose.ui.text.withStyle
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
import resources.translations.reader_tts_preview_default
import resources.translations.reader_tts_system_voice
import resources.translations.reader_voices_default_group
import resources.translations.reader_voices_delete_again
import resources.translations.reader_voices_delete_message
import resources.translations.reader_voices_delete_message_no_size
import resources.translations.reader_voices_delete_pack
import resources.translations.reader_voices_delete_title
import resources.translations.reader_voices_delete_using
import resources.translations.reader_voices_in_use
import resources.translations.reader_voices_in_use_caption
import resources.translations.reader_voices_language
import resources.translations.reader_voices_natural_english_only
import resources.translations.reader_voices_natural_header
import resources.translations.reader_voices_natural_intro
import resources.translations.reader_voices_needs_internet
import resources.translations.reader_voices_offline
import resources.translations.reader_voices_high_quality
import resources.translations.reader_voices_phone_filter_note
import resources.translations.reader_voices_phone_header
import resources.translations.reader_voices_region_other
import resources.translations.reader_voices_system_description
import resources.translations.reader_voices_title
import resources.translations.reader_voices_voice_numbered

/** A pending request to show the Supertonic terms; [voiceId] is selected once they are accepted. */
private data class TermsRequest(val voiceId: String?)

/**
 * Bottom sheet listing natural voice packs and the phone's own voices. A pack downloads, updates
 * and deletes as one object, so its state lives in the pack card header, never on voice rows.
 */
@Composable
internal fun VoicesSheet(
    voices: List<TtsVoice>,
    selectedVoiceId: String?,
    pendingVoiceId: String?,
    bookLanguage: String?,
    preparingVoicePackage: NeuralVoicePackage?,
    preparationProgress: TtsPreparationProgress?,
    failedVoicePackage: NeuralVoicePackage?,
    deletingVoicePackage: NeuralVoicePackage?,
    hasAcceptedSupertonicTerms: Boolean,
    previewingVoiceKey: String?,
    isPreviewPlaying: Boolean,
    isEink: Boolean,
    onVoiceSelected: (String?) -> Unit,
    onDownloadPackage: (NeuralVoicePackage) -> Unit,
    onUpdatePackage: (NeuralVoicePackage) -> Unit,
    onDeletePackage: (NeuralVoicePackage) -> Unit,
    onRetryPackage: (NeuralVoicePackage) -> Unit,
    onCancelPreparation: () -> Unit,
    onAcceptTermsAndDownload: () -> Unit,
    onAcceptTermsAndSelect: (String) -> Unit,
    onPreviewVoice: (String?, String) -> Unit,
    onStopPreview: () -> Unit,
    onClose: () -> Unit,
) {
    val colors = Ember.colors
    val previewText = stringResource(StringRes.reader_tts_preview_default)
    val systemGroups = remember(voices) { voices.toSystemVoiceGroups() }
    val neuralVoices = remember(voices) { voices.filter { voice -> voice.isNeural } }
    val packs = remember(neuralVoices) {
        NeuralVoicePackage.entries.filter { pack ->
            neuralVoices.any { voice -> voice.neuralVoicePackage == pack }
        }
    }
    val languages = remember(voices, bookLanguage) { voices.voiceLanguages(systemGroups, bookLanguage) }
    val phoneLanguage = remember { Locale.current.language }
    val defaultLanguage = remember(languages, bookLanguage, phoneLanguage) {
        defaultVoiceLanguage(
            availableCodes = systemGroups.map { group -> group.languageCode.lowercase() }.toSet(),
            bookLanguage = bookLanguage,
            phoneLanguage = phoneLanguage,
        )
    }
    var languageCode by remember(defaultLanguage) { mutableStateOf(defaultLanguage) }
    var termsRequest by remember { mutableStateOf<TermsRequest?>(null) }
    var packagePendingDeletion by remember { mutableStateOf<NeuralVoicePackage?>(null) }
    var showLicense by remember { mutableStateOf(false) }
    var licenseText by remember { mutableStateOf("") }
    LaunchedEffect(Unit) {
        licenseText = runCatching { Res.readBytes(SUPERTONIC_LICENSE_RESOURCE).decodeToString() }
            .getOrDefault("")
    }

    fun packState(pack: NeuralVoicePackage): PackUiState = derivePackState(
        voicePackage = pack,
        voices = voices,
        preparingPackage = preparingVoicePackage,
        progress = preparationProgress,
        failedPackage = failedVoicePackage,
        deletingPackage = deletingVoicePackage,
        hasAcceptedTerms = hasAcceptedSupertonicTerms,
    )

    val languageLabel = languages.firstOrNull { language -> language.code == languageCode }?.label
        ?: languageCode.uppercase()
    val noteLanguageCode = bookLanguage?.primaryLanguageCode()?.takeIf { code -> code.isNotBlank() }
        ?: languageCode
    val noteLanguageLabel = languages.firstOrNull { language -> language.code == noteLanguageCode }?.label
        ?: noteLanguageCode.uppercase()
    val phoneVoices = systemGroups.firstOrNull { group -> group.languageCode.lowercase() == languageCode }
        ?.voices.orEmpty()
    val regionGroups = remember(phoneVoices) { phoneVoices.toRegionGroups() }
    val selectedVoice = voices.firstOrNull { voice -> voice.id == selectedVoiceId }
    val inUseLabel = selectedVoice.inUseLabel(voices)

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
                    Column(Modifier.weight(1f).padding(start = 4.dp)) {
                        Text(
                            stringResource(StringRes.reader_voices_title),
                            style = Ember.type.screenTitle.copy(fontSize = 26.sp),
                            color = colors.ink,
                        )
                        Text(
                            stringResource(StringRes.reader_voices_in_use, inUseLabel),
                            color = colors.ink2,
                            fontSize = 13.sp,
                        )
                    }
                }
                LazyColumn(
                    Modifier.fillMaxWidth().selectableGroup(),
                    contentPadding = PaddingValues(start = 20.dp, end = 20.dp, bottom = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    item(key = "natural-header") {
                        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            VoicesSectionLabel(stringResource(StringRes.reader_voices_natural_header))
                            Text(
                                if (noteLanguageCode == ENGLISH_LANGUAGE_CODE) {
                                    stringResource(StringRes.reader_voices_natural_intro)
                                } else {
                                    stringResource(
                                        StringRes.reader_voices_natural_english_only,
                                        noteLanguageLabel,
                                    )
                                },
                                color = colors.ink2,
                                fontSize = 14.sp,
                            )
                        }
                    }
                    packs.forEach { pack ->
                        item(key = "pack-${pack.name}") {
                            val state = packState(pack)
                            val packVoices = neuralVoices.filter { voice -> voice.neuralVoicePackage == pack }
                            VoicePackCard(
                                voicePackage = pack,
                                voices = packVoices,
                                state = state,
                                hasAcceptedTerms = hasAcceptedSupertonicTerms,
                                selectedVoiceId = selectedVoiceId,
                                pendingVoiceId = pendingVoiceId,
                                previewingVoiceKey = previewingVoiceKey,
                                isPreviewPlaying = isPreviewPlaying,
                                isEink = isEink,
                                actions = VoicePackActions(
                                    onDownload = { onDownloadPackage(pack) },
                                    onReviewTerms = { termsRequest = TermsRequest(voiceId = null) },
                                    onUpdate = { onUpdatePackage(pack) },
                                    onDelete = { packagePendingDeletion = pack },
                                    onRetry = { onRetryPackage(pack) },
                                    onCancel = onCancelPreparation,
                                    onOpenLicence = { showLicense = true },
                                ),
                                onVoiceClick = { voice ->
                                    when {
                                        state.areVoicesSelectable -> onVoiceSelected(voice.id)
                                        state is PackUiState.TermsRequired ->
                                            termsRequest = TermsRequest(voiceId = voice.id)
                                        state is PackUiState.NotDownloaded -> onVoiceSelected(voice.id)
                                    }
                                },
                                onPreview = { voice -> onPreviewVoice(voice.id, previewText) },
                                onStopPreview = onStopPreview,
                            )
                        }
                    }
                    item(key = "phone-header") {
                        Row(
                            Modifier.fillMaxWidth().padding(top = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(Modifier.weight(1f).padding(end = 12.dp)) {
                                VoicesSectionLabel(stringResource(StringRes.reader_voices_phone_header))
                                Text(
                                    stringResource(StringRes.reader_voices_phone_filter_note, languageLabel),
                                    color = colors.ink2,
                                    fontSize = 14.sp,
                                )
                            }
                            LanguageChip(
                                languages = languages,
                                selectedCode = languageCode,
                                isEink = isEink,
                                onSelect = { code -> languageCode = code },
                            )
                        }
                    }
                    item(key = "phone-voices") {
                        PhoneVoicesCard(
                            regionGroups = regionGroups,
                            selectedVoiceId = selectedVoiceId,
                            previewingVoiceKey = previewingVoiceKey,
                            isPreviewPlaying = isPreviewPlaying,
                            isEink = isEink,
                            previewText = previewText,
                            onVoiceSelected = onVoiceSelected,
                            onPreviewVoice = onPreviewVoice,
                            onStopPreview = onStopPreview,
                        )
                    }
                }
            }
        }
    }

    termsRequest?.let { request ->
        SupertonicTermsSheet(
            sizeMb = neuralVoices
                .firstNotNullOfOrNull { voice ->
                    voice.takeIf { candidate -> candidate.neuralVoicePackage == NeuralVoicePackage.SUPERTONIC }
                        ?.downloadSizeBytes
                }
                ?.toNetworkMegabytes(),
            isEink = isEink,
            onDismiss = { termsRequest = null },
            onViewLicense = { showLicense = true },
            onAccept = {
                termsRequest = null
                val voiceId = request.voiceId
                if (voiceId != null) onAcceptTermsAndSelect(voiceId) else onAcceptTermsAndDownload()
            },
        )
    }

    packagePendingDeletion?.let { pack ->
        val packVoices = neuralVoices.filter { voice -> voice.neuralVoicePackage == pack }
        DeletePackDialog(
            packName = pack.shortName(),
            voiceCount = packVoices.size,
            sizeMb = packVoices.firstNotNullOfOrNull { voice -> voice.downloadSizeBytes }
                ?.toNetworkMegabytes(),
            voiceInUse = selectedVoice?.takeIf { voice -> voice.neuralVoicePackage == pack }?.displayName(),
            onDismiss = { packagePendingDeletion = null },
            onConfirm = {
                packagePendingDeletion = null
                onDeletePackage(pack)
            },
        )
    }

    if (showLicense) {
        SupertonicLicenseDialog(licenseText = licenseText, onDismiss = { showLicense = false })
    }
}

@Composable
private fun TtsVoice?.inUseLabel(allVoices: List<TtsVoice>): String {
    val phoneDefault = stringResource(StringRes.reader_tts_system_voice)
    if (this == null) return phoneDefault
    val pack = neuralVoicePackage
    if (pack != null) return "${pack.shortName()} · ${displayName()}"
    val number = allVoices
        .filter { voice -> !voice.isNeural && voice.languageCode() == languageCode() }
        .toRegionGroups()
        .flatMap { group -> group.voices }
        .firstOrNull { row -> row.voice.id == id }
        ?.number
    return if (number != null) {
        stringResource(StringRes.reader_voices_voice_numbered, number)
    } else {
        phoneDefault
    }
}

@Composable
private fun PhoneVoicesCard(
    regionGroups: List<SystemRegionGroup>,
    selectedVoiceId: String?,
    previewingVoiceKey: String?,
    isPreviewPlaying: Boolean,
    isEink: Boolean,
    previewText: String,
    onVoiceSelected: (String?) -> Unit,
    onPreviewVoice: (String?, String) -> Unit,
    onStopPreview: () -> Unit,
) {
    val colors = Ember.colors
    val shape = RoundedCornerShape(20.dp)
    val otherRegion = stringResource(StringRes.reader_voices_region_other)
    val offline = stringResource(StringRes.reader_voices_offline)
    val needsInternet = stringResource(StringRes.reader_voices_needs_internet)
    val highQuality = stringResource(StringRes.reader_voices_high_quality)
    Column(
        Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(colors.surface)
            .border(if (isEink) 2.dp else 1.dp, if (isEink) colors.ink else colors.line, shape),
    ) {
        GroupHeader(stringResource(StringRes.reader_voices_default_group))
        VoiceRow(
            title = stringResource(StringRes.reader_tts_system_voice),
            caption = if (selectedVoiceId == null) {
                stringResource(StringRes.reader_voices_in_use_caption)
            } else {
                stringResource(StringRes.reader_voices_system_description)
            },
            selected = selectedVoiceId == null,
            available = true,
            isEink = isEink,
            isPreviewing = previewingVoiceKey == TTS_SYSTEM_VOICE_KEY,
            isPreviewPlaying = isPreviewPlaying,
            onSelect = { onVoiceSelected(null) },
            onPreview = { onPreviewVoice(null, previewText) },
            onStopPreview = onStopPreview,
        )
        regionGroups.forEach { group ->
            Divider(isEink)
            GroupHeader(group.regionLabel.ifBlank { otherRegion })
            group.voices.forEach { row ->
                val voice = row.voice
                val facts = listOfNotNull(
                    if (voice.requiresNetwork) needsInternet else offline,
                    highQuality.takeIf { voice.isHighQuality },
                ).joinToString(" · ")
                VoiceRow(
                    title = stringResource(StringRes.reader_voices_voice_numbered, row.number),
                    caption = facts,
                    selected = voice.id == selectedVoiceId,
                    available = true,
                    isEink = isEink,
                    isPreviewing = previewingVoiceKey == voice.id,
                    isPreviewPlaying = isPreviewPlaying,
                    onSelect = { onVoiceSelected(voice.id) },
                    onPreview = { onPreviewVoice(voice.id, previewText) },
                    onStopPreview = onStopPreview,
                )
            }
        }
    }
}

@Composable
private fun GroupHeader(text: String) {
    Text(
        text,
        modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 4.dp),
        color = Ember.colors.ink2,
        fontSize = 13.sp,
        fontWeight = FontWeight.Bold,
    )
}

@Composable
private fun DeletePackDialog(
    packName: String,
    voiceCount: Int,
    sizeMb: Int?,
    voiceInUse: String?,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
) {
    val colors = Ember.colors
    val removes = if (sizeMb != null) {
        stringResource(StringRes.reader_voices_delete_message, voiceCount, packName, sizeMb)
    } else {
        stringResource(StringRes.reader_voices_delete_message_no_size, voiceCount, packName)
    }
    val using = voiceInUse?.let { name -> stringResource(StringRes.reader_voices_delete_using, name) }
    val again = stringResource(StringRes.reader_voices_delete_again)
    val boldRemoved = "all $voiceCount $packName voices"
    val message = buildAnnotatedString {
        appendWithBold(removes, boldRemoved)
        if (using != null && voiceInUse != null) {
            append(" ")
            appendWithBold(using, voiceInUse)
        }
        append(" ")
        append(again)
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = colors.surface,
        title = {
            Text(
                stringResource(StringRes.reader_voices_delete_title, packName),
                style = Ember.type.cardTitle,
                color = colors.ink,
            )
        },
        text = { Text(message, color = colors.ink2, fontSize = 16.sp) },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text(
                    stringResource(StringRes.reader_voices_delete_pack),
                    color = colors.destructive,
                    fontWeight = FontWeight.Bold,
                )
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(
                    stringResource(StringRes.general_cancel),
                    color = colors.ink,
                    fontWeight = FontWeight.Bold,
                )
            }
        },
    )
}

private fun AnnotatedString.Builder.appendWithBold(text: String, bold: String) {
    val start = text.indexOf(bold)
    if (start < 0) {
        append(text)
        return
    }
    append(text.substring(0, start))
    withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { append(bold) }
    append(text.substring(start + bold.length))
}

@Composable
private fun VoicesSectionLabel(text: String) {
    Text(
        text.uppercase(),
        style = Ember.type.eyebrow,
        color = Ember.colors.accentText,
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
                .border(if (isEink) 2.dp else 1.dp, if (isEink) colors.ink else colors.chipBorder, CircleShape)
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

private fun TtsVoice.languageCode(): String = locale.primaryLanguageCode()

/**
 * Languages the chip offers: those with system voices, plus the book's language so the default
 * is always selectable. Labels come from the device's own voice names where known.
 */
private fun List<TtsVoice>.voiceLanguages(
    systemGroups: List<SystemVoiceGroup>,
    bookLanguage: String?,
): List<VoiceLanguage> {
    val labels = systemGroups.associate { group -> group.languageCode.lowercase() to group.languageLabel }
    val codes = systemGroups.map { group -> group.languageCode.lowercase() } +
        listOfNotNull(bookLanguage?.primaryLanguageCode()?.takeIf { code -> code.isNotBlank() }) +
        ENGLISH_LANGUAGE_CODE
    return codes
        .distinct()
        .map { code ->
            VoiceLanguage(
                code = code,
                label = labels[code] ?: if (code == ENGLISH_LANGUAGE_CODE) "English" else code.uppercase(),
            )
        }
        .sortedBy { language -> language.label }
}
