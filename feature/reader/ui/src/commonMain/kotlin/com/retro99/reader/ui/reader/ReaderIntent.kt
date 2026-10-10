package com.retro99.reader.ui.reader

import com.retro99.base.ui.BaseIntent
import com.retro99.reader.ui.model.PositionUiModel
import com.retro99.reader.ui.model.ReaderSettingsUiModel
import com.retro99.reader.ui.tts.NeuralVoicePackage

sealed interface ReaderIntent : BaseIntent {
    data class PromptVisible(val operation: com.retro99.analytics.api.UsageOperation) : ReaderIntent
    data class FeatureVisible(val feature: com.retro99.analytics.api.UsageFeature, val available: Boolean = true) : ReaderIntent
    data object ToggleBookSearch : ReaderIntent

    /** The reader screen started or stopped (app foreground or background). */
    data class ReaderVisibilityChanged(val visible: Boolean) : ReaderIntent

    data class SearchBook(val query: String, val submitOnly: Boolean = false) : ReaderIntent
    data object SubmitBookSearch : ReaderIntent
    data object ClearBookSearchRecents : ReaderIntent
    data object CloseBookSearch : ReaderIntent
    data object PreviousSearchResult : ReaderIntent
    data object NextSearchResult : ReaderIntent
    data object ReturnToSearchOrigin : ReaderIntent
    data object ToggleFindBar : ReaderIntent

    /**
     * Reveals the matches after the spoiler boundary. With [stepNext], continues stepping
     * from the find bar's "Continue into the rest of the book?" prompt.
     */
    data class RevealSearchAhead(val stepNext: Boolean = false) : ReaderIntent

    data object DismissSearchContinuePrompt : ReaderIntent
    data class UpdateSearchDecorations(val accent: Int, val soft: Int, val onAccent: Int, val eink: Boolean) : ReaderIntent

    data class GoToSearchResult(val result: ReaderSearchResult) : ReaderIntent

    data class SeekToChapterProgress(val progression: Double) : ReaderIntent

    /** Returns to where the reader was before the last chapter or bookmark jump. */
    data object ReturnToJumpOrigin : ReaderIntent

    /** Shows the now-playing card for [source]; starts playback only when [autoPlay] is set. */
    data class StartListening(
        val source: ListenSource,
        val autoPlay: Boolean = true,
    ) : ReaderIntent

    /** Switches between narration and device voice on books that have both. */
    data class SwitchListenSource(val source: ListenSource) : ReaderIntent

    data object ToggleListenSheet : ReaderIntent

    data object StopListening : ReaderIntent

    data class UpdateSettings(
        val settings: ReaderSettingsUiModel,
    ) : ReaderIntent

    data object ToggleSettings : ReaderIntent

    data object Close : ReaderIntent

    /**
     * Retry loading the publication after an error.
     */
    data object Retry : ReaderIntent

    /**
     * Navigate to app settings screen.
     */
    data object OnSettingsClicked : ReaderIntent

    /**
     * Navigate to the next page.
     */
    data object GoToNextPage : ReaderIntent

    /**
     * Navigate to the previous page.
     */
    data object GoToPreviousPage : ReaderIntent

    /**
     * User chose to use the local position when a conflict was detected.
     */
    data object UseLocalPosition : ReaderIntent

    /**
     * User chose to use the remote position when a conflict was detected.
     */
    data object UseRemotePosition : ReaderIntent

    /**
     * The quiet settle bar reached its six seconds (or e-ink page turn is pending)
     * without an answer; nothing is written and no message is owed (spec §2).
     */
    data object DismissSettleBar : ReaderIntent

    /** "Continue" in the linked resume prompt: jump to the other copy's place. */
    data object ContinueLinkedResume : ReaderIntent

    /** "Stay here" in the linked resume prompt. */
    data object StayLinkedResume : ReaderIntent

    /** "Compare all" in the linked resume prompt: open the positions panel. */
    data object CompareLinkedPositions : ReaderIntent

    // Table of Contents intents

    /**
     * Toggle the Contents sheet on the Chapters tab (closes it when already open).
     */
    data object ToggleToc : ReaderIntent

    /** Expands or collapses a TOC group in the Contents sheet. */
    data class ToggleContentsGroup(val flatIndex: Int) : ReaderIntent

    /**
     * Navigate to a specific chapter from the TOC.
     *
     * @param href The href of the chapter to navigate to
     * @param currentPosition The current position before navigation (for the jump origin)
     */
    data class GoToChapter(
        val href: String,
        val currentPosition: PositionUiModel?,
    ) : ReaderIntent

    data object GoToNextChapter : ReaderIntent

    data object GoToPreviousChapter : ReaderIntent

    data class GoToChapterAndPlay(val href: String, val currentPosition: PositionUiModel?) : ReaderIntent

    data object GoToNextChapterAndPlay : ReaderIntent

    data object GoToPreviousChapterAndPlay : ReaderIntent

    // Media control intents for ReadAloud books

    /**
     * Toggle audio playback (play/pause).
     */
    data object TogglePlayback : ReaderIntent

    data class SelectTtsVoice(val voiceId: String?) : ReaderIntent

    data class DownloadNeuralVoicePackage(
        val voicePackage: NeuralVoicePackage,
    ) : ReaderIntent

    /** Download the latest manifest version of an already-installed neural voice pack. */
    data class UpdateNeuralVoicePackage(
        val voicePackage: NeuralVoicePackage,
    ) : ReaderIntent

    data class DeleteNeuralVoicePackage(
        val voicePackage: NeuralVoicePackage,
    ) : ReaderIntent

    data class RetryTtsVoicePreparation(
        val voicePackage: NeuralVoicePackage,
    ) : ReaderIntent

    data object AcceptSupertonicTermsAndDownload : ReaderIntent

    data class PreviewTtsVoice(val voiceId: String?, val text: String) : ReaderIntent

    data object StopTtsPreview : ReaderIntent

    data object OpenVoiceSettings : ReaderIntent

    data object CloseVoiceSettings : ReaderIntent

    /** Cancels an in-flight voice package download. */
    data object CancelTtsVoicePreparation : ReaderIntent

    /** Prepares the audio of the chapter on screen, so it plays with no waiting. */
    data object PrepareChapter : ReaderIntent

    data object CancelChapterPreparation : ReaderIntent

    data object DeletePreparedChapter : ReaderIntent

    /** Fetch a chapter another device prepared. Only ever from a press. */
    data object DownloadPreparedChapter : ReaderIntent

    /** To the cloud account screen, where the allowance and its breakdown are. */
    data object ManageCloudStorage : ReaderIntent

    /** Accepts the Supertonic terms, then downloads and selects the given voice. */
    data class AcceptSupertonicTermsAndSelect(val voiceId: String) : ReaderIntent

    data class SetTtsRate(val rate: Float) : ReaderIntent

    data class SetTtsPitch(val pitch: Float) : ReaderIntent

    data class SetTtsEnabled(val enabled: Boolean) : ReaderIntent

    /**
     * Toggle between the EPUB text view and the audiobook-style audio-only UI.
     */
    data object ToggleAudioOnlyMode : ReaderIntent

    /**
     * Seek to a specific audio position.
     *
     * @param audioTimestampMs The audio position in milliseconds
     */
    data class SeekTo(val audioTimestampMs: Long) : ReaderIntent

    /**
     * Set the playback speed.
     *
     * @param speed The playback speed (e.g., 0.5, 1.0, 1.5, 2.0)
     */
    data class SetPlaybackSpeed(val speed: Float) : ReaderIntent

    /**
     * Start or replace the active sleep timer.
     *
     * @param durationMs The duration in milliseconds before playback pauses.
     */
    data class StartSleepTimer(val durationMs: Long) : ReaderIntent

    /**
     * Cancel the active sleep timer.
     */
    data object CancelSleepTimer : ReaderIntent

    /**
     * Dismiss the sleep timer ending soon prompt without changing the timer.
     */
    data object DismissSleepTimerWarning : ReaderIntent

    /**
     * Skip forward by a specified amount.
     *
     * @param milliseconds The amount to skip forward in milliseconds
     */
    data class SkipForward(val milliseconds: Long = 10_000L) : ReaderIntent

    /**
     * Skip backward by a specified amount.
     *
     * @param milliseconds The amount to skip backward in milliseconds
     */
    data class SkipBackward(val milliseconds: Long = 10_000L) : ReaderIntent

    /**
     * Set the highlight color for ReadAloud text highlighting.
     *
     * @param colorArgb The highlight color as ARGB Int value
     */
    data class SetHighlightColor(val colorArgb: Int) : ReaderIntent

    /**
     * Dismiss the "no audio available" snackbar message.
     */
    data object DismissNoAudioMessage : ReaderIntent

    data object RetryTtsPlayback : ReaderIntent

    data object DismissTtsPlaybackFailed : ReaderIntent

    data object RetryPositionSave : ReaderIntent

    // Bookmarks, highlights and notes

    /** Toggle the Contents sheet on the Saved tab (closes it when already open). */
    data object ToggleBookmarks : ReaderIntent

    data class Saved(val action: com.retro99.reader.ui.reader.saved.SavedAction) : ReaderIntent
}

enum class ListenSource { NARRATION, DEVICE_VOICE }
