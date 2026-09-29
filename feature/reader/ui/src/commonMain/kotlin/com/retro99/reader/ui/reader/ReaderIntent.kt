package com.retro99.reader.ui.reader

import com.retro99.base.ui.BaseIntent
import com.retro99.reader.ui.model.BookmarkUiModel
import com.retro99.reader.ui.model.PositionUiModel
import com.retro99.reader.ui.model.ReaderSettingsUiModel
import com.retro99.reader.ui.tts.NeuralVoicePackage

sealed interface ReaderIntent : BaseIntent {
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

    // Table of Contents intents

    /**
     * Toggle the table of contents visibility.
     */
    data object ToggleToc : ReaderIntent

    /**
     * Navigate to a specific chapter from the TOC.
     *
     * @param href The href of the chapter to navigate to
     * @param currentPosition The current position before navigation (for undo functionality)
     */
    data class GoToChapter(
        val href: String,
        val currentPosition: PositionUiModel?,
    ) : ReaderIntent

    /**
     * Undo the last chapter navigation and return to the previous position.
     *
     * @param position The position to navigate back to
     */
    data class UndoChapterNavigation(val position: PositionUiModel) : ReaderIntent

    /**
     * Dismiss the chapter navigation undo snackbar.
     */
    data object DismissChapterNavigationUndo : ReaderIntent

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

    /**
     * Dismiss the "bookmark save failed" snackbar message.
     */
    data object DismissBookmarkSaveFailed : ReaderIntent

    data object RetryPositionSave : ReaderIntent

    // Bookmarks

    data object ToggleBookmarks : ReaderIntent

    data object AddBookmark : ReaderIntent

    data object DismissBookmarkAdded : ReaderIntent

    data object DismissBookmarkAlreadyExists : ReaderIntent

    data class UndoBookmark(val id: String) : ReaderIntent

    data class RenameBookmark(val id: String, val newTitle: String) : ReaderIntent

    data class ReorderBookmarks(val bookmarkIds: List<String>) : ReaderIntent

    data object GoToPreviousBookmark : ReaderIntent

    data object GoToNextBookmark : ReaderIntent

    data object DismissNoMoreBookmarks : ReaderIntent

    data class DeleteBookmark(val id: String) : ReaderIntent

    data class GoToBookmark(val bookmark: BookmarkUiModel) : ReaderIntent
}
