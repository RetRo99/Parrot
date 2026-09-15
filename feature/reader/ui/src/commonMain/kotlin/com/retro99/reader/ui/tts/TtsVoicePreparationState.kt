package com.retro99.reader.ui.tts

sealed interface TtsVoicePreparationState {

    data object Idle : TtsVoicePreparationState

    data class Running(
        val voicePackage: NeuralVoicePackage,
        val progress: TtsPreparationProgress,
    ) : TtsVoicePreparationState

    data class Complete(
        val voicePackage: NeuralVoicePackage,
    ) : TtsVoicePreparationState

    data class Failed(
        val voicePackage: NeuralVoicePackage,
    ) : TtsVoicePreparationState
}
