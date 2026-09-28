package com.retro99.home.ui.navigation

import com.retro99.base.ui.BaseIntent
import com.retro99.books.domain.model.BookType

/**
 * Intents for HomeNavigationViewModel.
 *
 * All navigation actions go through the ViewModel to maintain the intent dispatcher pattern.
 * The ViewModel emits [HomeNavigationEvent]s which are consumed by the composable
 * to update the navigation state.
 */
sealed interface HomeNavigationIntent : BaseIntent {
    // UI state intents
    data class UpdateBubblePosition(val side: BubbleSide, val yFraction: Float) : HomeNavigationIntent
    data object RetryBubblePositionSave : HomeNavigationIntent
    data object DismissBubblePositionSaveError : HomeNavigationIntent
    data object ClearCurrentlyReading : HomeNavigationIntent

    // Navigation intents
    data class NavigateTo(val destination: HomeDestination) : HomeNavigationIntent
    data class SwitchTab(val sourceTab: HomeTab, val tab: HomeTab) : HomeNavigationIntent
    data class GoBack(
        val sourceScreen: String? = null,
        val destinationScreen: String? = null,
        val entryPoint: String? = null,
    ) : HomeNavigationIntent

    /**
     * Request to open a book. Will check for playback conflicts first.
     * If another book is playing, shows a confirmation dialog.
     * Otherwise navigates directly to the reader.
     *
     * @param bookTitle Optional title for display in the conflict dialog
     */
    data class RequestOpenReader(
        val serverId: String,
        val bookUuid: String,
        val bookType: BookType,
        val bookTitle: String? = null,
    ) : HomeNavigationIntent

    /**
     * Navigate to reader directly without conflict check.
     * Used internally after dialog confirmation or when there's no conflict.
     */
    data class OpenReader(
        val serverId: String,
        val bookUuid: String,
        val bookType: BookType,
    ) : HomeNavigationIntent

    // Mini-player intents
    data object ToggleMiniPlayerPlayPause : HomeNavigationIntent
    data object StopMiniPlayerPlayback : HomeNavigationIntent

    // Playback conflict dialog intents
    /**
     * User chose to stop current playback and open the new book.
     */
    data object PlaybackConflictStopAndOpen : HomeNavigationIntent

    /**
     * User dismissed the dialog without making a choice.
     */
    data object PlaybackConflictDismiss : HomeNavigationIntent
}
