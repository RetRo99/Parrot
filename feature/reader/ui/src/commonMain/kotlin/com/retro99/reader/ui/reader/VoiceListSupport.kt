package com.retro99.reader.ui.reader

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.retro99.reader.ui.tts.NeuralVoicePackage
import com.retro99.reader.ui.tts.TtsVoice
import com.retro99.translations.StringRes
import org.jetbrains.compose.resources.stringResource
import resources.translations.general_cancel
import resources.translations.general_close
import resources.translations.reader_tts_agree_and_download
import resources.translations.reader_tts_kokoro_pack_name
import resources.translations.reader_tts_model_license_loading
import resources.translations.reader_tts_model_license_title
import resources.translations.reader_tts_supertonic_pack_name
import resources.translations.reader_tts_supertonic_terms_message
import resources.translations.reader_tts_supertonic_terms_title
import resources.translations.reader_tts_view_full_license
import kotlin.math.roundToInt

@Composable
internal fun SupertonicTermsDialog(
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
internal fun SupertonicLicenseDialog(
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

internal fun Long.toNetworkMegabytes(): Int =
    (this / BYTES_PER_MEGABYTE).roundToInt()

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
internal fun NeuralVoicePackage.displayName(): String = when (this) {
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

private const val BYTES_PER_MEGABYTE = 1_000_000.0

internal const val SUPERTONIC_LICENSE_RESOURCE = "files/supertonic_openrail_m_license.txt"
