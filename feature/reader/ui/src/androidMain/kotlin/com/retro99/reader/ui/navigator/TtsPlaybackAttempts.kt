package com.retro99.reader.ui.navigator

import com.retro99.reader.ui.tts.TtsPlaybackStartException
import com.retro99.reader.ui.tts.TtsReadAloudEngine
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.util.UUID

internal data class TtsPlaybackAttempt(
    val correlationId: String,
    val action: TtsPlaybackAction,
    val isRetry: Boolean,
    val startedAtMs: Long,
)

/**
 * One read-aloud start at a time: the `Attempted` event, exactly one terminal outcome, the
 * pending flag, the bounded start deadline — and the decision a voice, speed or pitch
 * change makes while a session is loaded.
 *
 * Separate from [AndroidTtsController] so it can be tested: the controller takes fifteen
 * dependencies, most of them concrete Android classes, while this takes the read-aloud
 * engine (which a host test can build behind its player seam) and a scope.
 */
internal class TtsPlaybackAttempts(
    private val scope: CoroutineScope,
    startupTimeoutMs: Long,
    private val engine: TtsReadAloudEngine,
    private val nowMs: () -> Long = System::currentTimeMillis,
    /** Restarts the given sentence with the settings in force right now. */
    private val restartAtIndex: suspend (Int) -> Unit,
) {

    private val _operations = MutableSharedFlow<TtsPlaybackOperation>(extraBufferCapacity = 8)
    val operations: SharedFlow<TtsPlaybackOperation> = _operations.asSharedFlow()

    private val startPending = MutableStateFlow(false)
    val isStartPending: StateFlow<Boolean> = startPending.asStateFlow()

    private var active: TtsPlaybackAttempt? = null
    private var previousAttemptFailed = false

    /**
     * Sentence a voice, speed or pitch change was made on while narration was paused. The
     * next play press restarts it rather than resuming audio made with the old setting.
     */
    private var pausedSettingsChangeIndex: Int? = null

    private val lifecycle = TtsPlaybackOperationLifecycle(
        scope = scope,
        startupTimeoutMs = startupTimeoutMs,
        onStartupTimeout = {
            active?.let { attempt ->
                finishFailed(
                    attempt = attempt,
                    reasonCode = TtsPlaybackFailureReason.START_TIMEOUT,
                    error = IllegalStateException(
                        "TTS playback did not become active within the startup deadline",
                    ),
                )
            }
        },
    )

    init {
        scope.launch {
            engine.isPlaying.collect { isPlaying ->
                if (isPlaying) onEngineStartedPlaying()
            }
        }
        scope.launch {
            engine.playbackFailures.collect(::onEngineFailure)
        }
    }

    /** Starts one tracked playback operation, or does nothing if one is already running. */
    fun request(
        action: TtsPlaybackAction,
        start: suspend (TtsPlaybackAttempt) -> TtsPlaybackFailureReason?,
    ) {
        val attempt = begin(action) ?: return
        lifecycle.launchRequest {
            try {
                when (val reason = start(attempt)) {
                    null -> awaitStart(attempt)
                    TtsPlaybackFailureReason.PERMISSION_DENIED,
                    TtsPlaybackFailureReason.OPERATION_CANCELLED ->
                        finishCancelled(attempt, reason)

                    else -> finishFailed(attempt, reason, error = null)
                }
            } catch (error: CancellationException) {
                finishCancelled(attempt, TtsPlaybackFailureReason.OPERATION_CANCELLED)
                throw error
            } catch (error: Exception) {
                val reason = (error as? TtsPlaybackStartException)?.reasonCode
                    ?: TtsPlaybackFailureReason.UNEXPECTED_ERROR
                finishFailed(attempt, reason, error)
            }
        }
    }

    /**
     * The user pressed play with nothing playing: either the engine still holds a sentence
     * and resumes it, or playback starts fresh.
     */
    fun onPlayPressed(
        resume: suspend (TtsPlaybackAttempt) -> TtsPlaybackFailureReason?,
        freshStart: suspend (TtsPlaybackAttempt) -> TtsPlaybackFailureReason?,
    ) {
        // Only while the engine still holds that sentence: a chapter change stops it.
        val pausedIndex = pausedSettingsChangeIndex
            ?.takeIf { index -> index == engine.currentSentenceIndex }
        pausedSettingsChangeIndex = null
        val isResuming = engine.currentSentence.value != null
        request(if (isResuming) TtsPlaybackAction.RESUME else TtsPlaybackAction.CONTROLS) { attempt ->
            when {
                // The audio in the player was made with the old setting: synthesise the
                // paused sentence and everything after it again, never resume (TTS-F06).
                pausedIndex != null -> restart(attempt, pausedIndex)

                isResuming -> resume(attempt)
                else -> freshStart(attempt)
            }
        }
    }

    /** The voice, the speed or the pitch changed while a chapter is loaded. */
    fun onSettingsChanged() {
        val index = engine.currentSentenceIndex
        if (index < 0) return

        // isSessionRunning, not isPlaying: a restart in flight has already stopped the old
        // audio, and a slow voice leaves gaps between sentences where nothing is audible
        // although narration is running (TTS-F26).
        if (engine.isSessionRunning.value || isRestartActive) {
            // One more change supersedes the restart in flight: it ends as cancelled, and
            // the new one gets its own attempt and its own single terminal outcome.
            if (isRestartActive) cancelActive()
            request(TtsPlaybackAction.SETTINGS_CHANGE) { attempt ->
                restart(attempt, index)
            }
        } else {
            // The position is kept: the next play starts this same sentence, from its
            // start, with the new setting (TTS-F06). engine.stop() threw it away.
            pausedSettingsChangeIndex = index
        }
    }

    private val isRestartActive: Boolean
        get() = active?.action == TtsPlaybackAction.SETTINGS_CHANGE

    /**
     * Synthesises [index] again with the settings in force now, as a tracked start: the
     * deadline covers it and the engine labels its own failures with this attempt.
     */
    private suspend fun restart(
        attempt: TtsPlaybackAttempt,
        index: Int,
    ): TtsPlaybackFailureReason? {
        armStartupTimeout(attempt)
        engine.setPlaybackOperationCorrelationId(attempt.correlationId)
        restartAtIndex(index)
        return null
    }

    fun armStartupTimeout(attempt: TtsPlaybackAttempt) {
        if (active != attempt) return
        lifecycle.armStartupTimeout()
    }

    private fun onEngineStartedPlaying() {
        active?.let(::finishSucceeded)
    }

    private fun onEngineFailure(failure: TtsReadAloudEngine.PlaybackFailure) {
        val attempt = active
        if (attempt != null) {
            finishFailed(
                attempt = attempt,
                reasonCode = failure.reasonCode,
                error = failure.error,
            )
        } else {
            previousAttemptFailed = true
            _operations.tryEmit(
                TtsPlaybackOperation.Failed(
                    correlationId = failure.correlationId ?: UUID.randomUUID().toString(),
                    action = TtsPlaybackAction.ACTIVE_PLAYBACK,
                    isRetry = false,
                    durationMs = 0L,
                    reasonCode = failure.reasonCode,
                    error = failure.error,
                ),
            )
        }
    }

    fun cancelActive() {
        pausedSettingsChangeIndex = null
        val attempt = active ?: return
        lifecycle.cancelPendingRequest()
        finishCancelled(attempt, TtsPlaybackFailureReason.OPERATION_CANCELLED)
    }

    private fun begin(action: TtsPlaybackAction): TtsPlaybackAttempt? {
        if (active != null) return null
        pausedSettingsChangeIndex = null
        val attempt = TtsPlaybackAttempt(
            correlationId = UUID.randomUUID().toString(),
            action = action,
            isRetry = previousAttemptFailed,
            startedAtMs = nowMs(),
        )
        active = attempt
        startPending.value = true
        _operations.tryEmit(
            TtsPlaybackOperation.Attempted(
                correlationId = attempt.correlationId,
                action = attempt.action,
                isRetry = attempt.isRetry,
            ),
        )
        return attempt
    }

    private fun awaitStart(attempt: TtsPlaybackAttempt) {
        if (active != attempt) return
        if (engine.isPlaying.value) {
            finishSucceeded(attempt)
            return
        }
        if (lifecycle.hasStartupTimeout) return
        armStartupTimeout(attempt)
    }

    private fun finishSucceeded(attempt: TtsPlaybackAttempt) {
        if (active != attempt) return
        clear()
        previousAttemptFailed = false
        _operations.tryEmit(
            TtsPlaybackOperation.Succeeded(
                correlationId = attempt.correlationId,
                action = attempt.action,
                isRetry = attempt.isRetry,
                durationMs = elapsedSince(attempt.startedAtMs),
            ),
        )
    }

    private fun finishFailed(
        attempt: TtsPlaybackAttempt,
        reasonCode: TtsPlaybackFailureReason,
        error: Throwable?,
    ) {
        if (active != attempt) return
        lifecycle.cancelPendingRequest()
        engine.stop()
        clear()
        previousAttemptFailed = true
        _operations.tryEmit(
            TtsPlaybackOperation.Failed(
                correlationId = attempt.correlationId,
                action = attempt.action,
                isRetry = attempt.isRetry,
                durationMs = elapsedSince(attempt.startedAtMs),
                reasonCode = reasonCode,
                error = error,
            ),
        )
    }

    private fun finishCancelled(
        attempt: TtsPlaybackAttempt,
        reasonCode: TtsPlaybackFailureReason,
    ) {
        if (active != attempt) return
        clear()
        _operations.tryEmit(
            TtsPlaybackOperation.Cancelled(
                correlationId = attempt.correlationId,
                action = attempt.action,
                isRetry = attempt.isRetry,
                durationMs = elapsedSince(attempt.startedAtMs),
                reasonCode = reasonCode,
            ),
        )
    }

    private fun clear() {
        active = null
        lifecycle.cancelStartupTimeout()
        startPending.value = false
    }

    private fun elapsedSince(startedAtMs: Long): Long =
        (nowMs() - startedAtMs).coerceAtLeast(0L)
}
