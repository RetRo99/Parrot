package com.retro99.home.ui.navigation

import com.retro99.books.domain.model.BookType
import com.retro99.reader.ui.playback.NowPlayingInfo

/**
 * UI state for the Home screen (non-navigation concerns).
 *
 * Navigation state is now managed by [HomeNavigationStateHolder] using Nav3's
 * [rememberNavBackStack] for automatic persistence across process death.
 *
 * @property currentlyReading The book that the user was last reading for at least 1 minute.
 * @property bubblePosition The persisted position of the continue reading bubble. Null until loaded.
 * @property nowPlayingInfo The currently playing audio book info for the mini-player. Null if nothing playing.
 * @property isAudioPlaying Whether audio is currently playing (not paused).
 * @property playbackConflictDialog Dialog state when trying to open a book while another is playing.
 */
data class HomeUiState(
    val currentlyReading: CurrentlyReadingUiModel? = null,
    val showContinueReading: Boolean = true,
    val bubblePosition: BubblePositionModel? = null,
    val nowPlayingInfo: NowPlayingInfo? = null,
    val isAudioPlaying: Boolean = false,
    val playbackConflictDialog: PlaybackConflictDialogState? = null,
)

/**
 * State for the playback conflict dialog.
 * Shown when user tries to open a different book while audio is playing.
 */
data class PlaybackConflictDialogState(
    /** Title of the currently playing book */
    val currentlyPlayingTitle: String,
    /** Title of the book the user wants to open */
    val targetBookTitle: String?,
    /** Server ID of the target book */
    val targetServerId: String,
    /** UUID of the target book */
    val targetBookUuid: String,
    /** Type of the target book */
    val targetBookType: BookType,
)

/**
 * Navigation events that the ViewModel emits to request navigation changes.
 * These are consumed by the composable which owns the navigation state.
 */
sealed interface HomeNavigationEvent {
    /**
     * Navigate to a destination within the current tab.
     */
    data class NavigateTo(val destination: HomeDestination) : HomeNavigationEvent

    /**
     * Switch to a different tab.
     */
    data class SwitchTab(
        val sourceTab: HomeTab,
        val tab: HomeTab,
        val correlationId: String,
    ) : HomeNavigationEvent

    /**
     * Go back in the navigation stack.
     */
    data class GoBack(
        val context: HomeNavigationBackContext? = null,
    ) : HomeNavigationEvent

    /**
     * Navigate to the reader screen, replacing any existing reader in the stack.
     * Used for deep links and "open last book on launch".
     */
    data class NavigateToReaderReplacing(
        val serverId: String,
        val bookUuid: String,
        val bookType: BookType,
        val tab: HomeTab = HomeTab.Books,
        val isLastBookOnLaunch: Boolean = false,
    ) : HomeNavigationEvent
}

/** Non-sensitive context captured when a user requests Back navigation. */
data class HomeNavigationBackContext(
    val sourceScreen: String,
    val destinationScreen: String,
    val entryPoint: String,
    val correlationId: String,
)
