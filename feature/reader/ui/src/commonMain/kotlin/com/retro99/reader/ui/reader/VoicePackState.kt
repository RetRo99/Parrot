package com.retro99.reader.ui.reader

import com.retro99.reader.ui.tts.NeuralVoicePackage
import com.retro99.reader.ui.tts.TtsPreparationProgress
import com.retro99.reader.ui.tts.TtsVoice

/** What a natural-voice pack card shows. Every state applies to all voices of the pack at once. */
internal sealed interface PackUiState {

    data class NotDownloaded(val sizeMb: Int?) : PackUiState

    data class TermsRequired(val sizeMb: Int?) : PackUiState

    data class Downloading(
        val percent: Int?,
        val downloadedMb: Int?,
        val totalMb: Int?,
    ) : PackUiState

    data object Finishing : PackUiState

    data object Downloaded : PackUiState

    data class UpdateAvailable(val updateSizeMb: Int?) : PackUiState

    data object Failed : PackUiState

    data object Deleting : PackUiState
}

/** Voices can be picked and previewed only while the pack is installed and not changing. */
internal val PackUiState.areVoicesSelectable: Boolean
    get() = this is PackUiState.Downloaded || this is PackUiState.UpdateAvailable

@Suppress("LongParameterList")
internal fun derivePackState(
    voicePackage: NeuralVoicePackage,
    voices: List<TtsVoice>,
    preparingPackage: NeuralVoicePackage?,
    progress: TtsPreparationProgress?,
    failedPackage: NeuralVoicePackage?,
    deletingPackage: NeuralVoicePackage?,
    hasAcceptedTerms: Boolean,
): PackUiState {
    val packageVoices = voices.filter { voice -> voice.neuralVoicePackage == voicePackage }
    val totalBytes = packageVoices.firstNotNullOfOrNull { voice -> voice.downloadSizeBytes }
    val sizeMb = totalBytes?.toNetworkMegabytes()
    val isDownloaded = voices.isNeuralVoicePackageDownloaded(voicePackage)
    return when {
        deletingPackage == voicePackage -> PackUiState.Deleting
        preparingPackage == voicePackage -> when (progress) {
            is TtsPreparationProgress.Downloading -> PackUiState.Downloading(
                percent = progress.percentage,
                downloadedMb = progress.downloadedBytes.toNetworkMegabytes(),
                totalMb = (progress.totalBytes ?: totalBytes)?.toNetworkMegabytes(),
            )
            else -> PackUiState.Finishing
        }
        failedPackage == voicePackage -> PackUiState.Failed
        isDownloaded && voices.isNeuralVoicePackageUpdateAvailable(voicePackage) ->
            PackUiState.UpdateAvailable(
                updateSizeMb = packageVoices
                    .firstNotNullOfOrNull { voice -> voice.updateSizeBytes }
                    ?.toNetworkMegabytes(),
            )
        isDownloaded -> PackUiState.Downloaded
        voicePackage == NeuralVoicePackage.SUPERTONIC && !hasAcceptedTerms ->
            PackUiState.TermsRequired(sizeMb)
        else -> PackUiState.NotDownloaded(sizeMb)
    }
}

/** A system voice with its position among the voices of the same region. */
internal data class SystemVoiceRow(val voice: TtsVoice, val number: Int)

internal data class SystemRegionGroup(val regionLabel: String, val voices: List<SystemVoiceRow>)

/**
 * Groups voices by region (blank regions last). Voices keep a stable order and are numbered
 * within their region, because the engine only exposes technical ids as names.
 */
internal fun List<TtsVoice>.toRegionGroups(): List<SystemRegionGroup> =
    groupBy { voice -> voice.regionLabel }
        .entries
        .sortedWith(
            compareBy<Map.Entry<String, List<TtsVoice>>> { entry -> entry.key.isBlank() }
                .thenBy { entry -> entry.key },
        )
        .map { (region, regionVoices) ->
            SystemRegionGroup(
                regionLabel = region,
                voices = regionVoices
                    .sortedBy { voice -> voice.id }
                    .mapIndexed { index, voice -> SystemVoiceRow(voice, number = index + 1) },
            )
        }

/** BCP 47 tag or locale string reduced to its lowercase primary language ("sq-AL" → "sq"). */
internal fun String.primaryLanguageCode(): String =
    substringBefore('-').substringBefore('_').lowercase()

/**
 * The language the system-voice list starts on: the book's, else the phone's, else English —
 * preferring the first of those that actually has voices, never an alphabetical first.
 */
internal fun defaultVoiceLanguage(
    availableCodes: Set<String>,
    bookLanguage: String?,
    phoneLanguage: String?,
): String {
    val candidates = listOfNotNull(
        bookLanguage?.primaryLanguageCode()?.takeIf { code -> code.isNotBlank() },
        phoneLanguage?.primaryLanguageCode()?.takeIf { code -> code.isNotBlank() },
        ENGLISH_LANGUAGE_CODE,
    )
    return candidates.firstOrNull { code -> code in availableCodes } ?: candidates.first()
}

internal const val ENGLISH_LANGUAGE_CODE = "en"
