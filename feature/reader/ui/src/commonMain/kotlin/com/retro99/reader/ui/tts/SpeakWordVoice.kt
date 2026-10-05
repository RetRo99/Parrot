package com.retro99.reader.ui.tts

import com.retro99.dictionary.isEnglish

/** Which voice speaks a dictionary word, or [Hidden], which hides the speaker button. */
sealed interface WordVoiceResolution {

    data class Usable(
        val voiceId: String,
        val name: String,
        val isNeural: Boolean,
    ) : WordVoiceResolution

    data object Hidden : WordVoiceResolution
}

/**
 * Picks the voice for one spoken word, in order:
 *
 * 1. The selected voice when it can speak now: an English system voice, or a neural voice whose
 *    pack is downloaded (cold engines load on demand) with Supertonic terms accepted and no
 *    download or preparation running for it. An update that is available but not installed does
 *    not disqualify it; the active version is authoritative.
 * 2. Only when the selected voice is not usable (pack missing or in progress, terms not
 *    accepted, or not English): the best offline English system voice, preferring the engine
 *    default when it is one. Rule 2 never returns a neural voice, so a speaker tap can never
 *    reach the download path.
 * 3. Otherwise [WordVoiceResolution.Hidden]: no voice can speak the word's language offline.
 *
 * English only, matching the dictionary pack.
 */
fun resolveWordVoice(
    selectedVoiceId: String?,
    voices: List<TtsVoice>,
    defaultSystemVoiceId: String?,
    hasAcceptedSupertonicTerms: Boolean,
    preparingPackage: NeuralVoicePackage?,
    language: String,
): WordVoiceResolution {
    if (!isEnglish(language)) return WordVoiceResolution.Hidden

    val selected = voices.firstOrNull { voice ->
        voice.id == selectedVoiceId && !voice.requiresNetwork && isEnglish(voice.locale)
    }
    if (selected != null) {
        when (val selectedPackage = selected.neuralVoicePackage) {
            null -> return WordVoiceResolution.Usable(
                voiceId = selected.id,
                name = selected.name,
                isNeural = false,
            )

            else -> {
                val isUsableNow = selected.isDownloaded &&
                    preparingPackage != selectedPackage &&
                    (selectedPackage != NeuralVoicePackage.SUPERTONIC || hasAcceptedSupertonicTerms)
                if (isUsableNow) {
                    return WordVoiceResolution.Usable(
                        voiceId = selected.id,
                        name = selected.name,
                        isNeural = true,
                    )
                }
            }
        }
    }

    val systemCandidates = voices.filter { voice ->
        voice.neuralVoicePackage == null && !voice.requiresNetwork && isEnglish(voice.locale)
    }
    val fallback = systemCandidates.firstOrNull { voice -> voice.id == defaultSystemVoiceId }
        ?: systemCandidates.sortedWith(
            compareByDescending<TtsVoice> { voice -> voice.isHighQuality }
                .thenBy { voice -> voice.latency }
                .thenBy { voice -> voice.name },
        ).firstOrNull()
    return if (fallback == null) {
        WordVoiceResolution.Hidden
    } else {
        WordVoiceResolution.Usable(
            voiceId = fallback.id,
            name = fallback.name,
            isNeural = false,
        )
    }
}
