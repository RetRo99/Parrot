package com.retro99.reader.ui.tts

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import org.koin.core.annotation.Single

@Single
class TtsVoicePreparationStateHolder {

    private val mutableState = MutableStateFlow<TtsVoicePreparationState>(
        TtsVoicePreparationState.Idle,
    )

    val state: StateFlow<TtsVoicePreparationState> = mutableState.asStateFlow()

    fun begin(
        voicePackage: NeuralVoicePackage,
        progress: TtsPreparationProgress,
    ): Boolean {
        while (true) {
            val currentState = mutableState.value
            if (currentState is TtsVoicePreparationState.Running) return false
            val started = mutableState.compareAndSet(
                currentState,
                TtsVoicePreparationState.Running(voicePackage, progress),
            )
            if (started) return true
        }
    }

    fun updateProgress(
        voicePackage: NeuralVoicePackage,
        progress: TtsPreparationProgress,
    ) {
        mutableState.update { currentState ->
            if (
                currentState is TtsVoicePreparationState.Running &&
                currentState.voicePackage == voicePackage
            ) {
                TtsVoicePreparationState.Running(voicePackage, progress)
            } else {
                currentState
            }
        }
    }

    fun markComplete(voicePackage: NeuralVoicePackage) {
        updateTerminalState(
            voicePackage = voicePackage,
            terminalState = TtsVoicePreparationState.Complete(voicePackage),
        )
    }

    fun markFailed(voicePackage: NeuralVoicePackage) {
        updateTerminalState(
            voicePackage = voicePackage,
            terminalState = TtsVoicePreparationState.Failed(voicePackage),
        )
    }

    fun markIdle(voicePackage: NeuralVoicePackage) {
        updateTerminalState(
            voicePackage = voicePackage,
            terminalState = TtsVoicePreparationState.Idle,
        )
    }

    /**
     * Drops a finished result (Complete or Failed) so a reader that opens later starts from
     * Idle instead of showing the outcome of a download it never started. A Running
     * preparation is left alone: it is still the holder's one active download.
     *
     * Not called when a download finishes: `awaitVoicePreparation` reads the result from here
     * and reads Idle as "cancelled", so clearing early would turn a success into one.
     */
    fun clearFinishedState() {
        mutableState.update { currentState ->
            if (currentState is TtsVoicePreparationState.Running) {
                currentState
            } else {
                TtsVoicePreparationState.Idle
            }
        }
    }

    private fun updateTerminalState(
        voicePackage: NeuralVoicePackage,
        terminalState: TtsVoicePreparationState,
    ) {
        mutableState.update { currentState ->
            if (
                currentState is TtsVoicePreparationState.Running &&
                currentState.voicePackage == voicePackage
            ) {
                terminalState
            } else {
                currentState
            }
        }
    }
}
