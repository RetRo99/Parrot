package com.retro99.reader.ui.reader

import com.retro99.base.result.AppError
import com.retro99.books.domain.model.BookType
import com.retro99.reader.domain.linked.LinkedResumeOffer
import com.retro99.reader.ui.model.ChapterInfo
import com.retro99.reader.ui.model.ChapterReadingTimeInfo
import com.retro99.reader.ui.model.PositionConflictUiModel
import com.retro99.reader.ui.model.PositionUiModel
import com.retro99.reader.ui.model.TocItemUiModel
import com.retro99.reader.ui.publication.PublicationState
import com.retro99.reader.ui.tts.NeuralVoicePackage
import com.retro99.reader.ui.tts.TtsPreparationProgress
import com.retro99.reader.ui.tts.TtsVoice

data class ReaderViewState(
    val bookType: BookType,
    val bookUuid: String,
    val bookTitle: String = "",
    val bookAuthor: String = "",
    val bookCoverUrl: String? = null,
    val localFilePath: String? = null,
    val publicationState: PublicationState? = null,
    val positionConflict: PositionConflictUiModel? = null,
    val isResolvingConflict: Boolean = false,
    val conflictResolutionError: AppError? = null,
    val conflictServerName: String = "",
    /** A newer reading in another linked copy, offered in place of [positionConflict]. */
    val linkedResumeOffer: LinkedResumeOffer? = null,
    val isSettingsVisible: Boolean = false,
    val error: AppError? = null,
    // Current time formatted according to user's locale (updated every minute)
    val currentTime: String = "",
    // Media playback state for ReadAloud books
    val isPlaying: Boolean = false,
    val isNarrationLoading: Boolean = false,
    val isNarrationStartPending: Boolean = false,
    val currentAudioPositionMs: Long = 0L,
    val totalDurationMs: Long? = null,
    // Whether the media player is ready (for ReadAloud books)
    val isAudioPlayerReady: Boolean = false,
    // Table of contents
    val tableOfContents: List<TocItemUiModel> = emptyList(),
    // Contents sheet (Chapters / Saved share one visibility flag)
    val isContentsVisible: Boolean = false,
    // Tab the sheet opens on; the tab switch itself is local to the sheet.
    val contentsInitialTab: ContentsTab = ContentsTab.CHAPTERS,
    // Flat indices of the expanded TOC groups; kept for the reading session.
    val contentsExpandedGroups: Set<Int> = emptySet(),
    // "Back to where I was" after a chapter or bookmark jump; ages out after a few page turns.
    val jumpOrigin: PositionUiModel? = null,
    val jumpOriginPageTurns: Int = 0,
    // Current chapter info (page position and word count) based on actual viewport display
    val chapterInfo: ChapterInfo? = null,
    // Estimated reading time for the current chapter
    val chapterReadingTimeInfo: ChapterReadingTimeInfo? = null,
    // Flag to show snackbar when ReadAloud book has no media overlays
    val showNoAudioMessage: Boolean = false,
    val showTtsPlaybackFailed: Boolean = false,
    val showPositionSaveFailed: Boolean = false,
    // Sleep timer state for ReadAloud playback. Null means no active timer.
    val sleepTimerRemainingMs: Long? = null,
    // Shows a one-time prompt when the sleep timer is close to ending.
    val showSleepTimerWarningPrompt: Boolean = false,
    // Bookmarks, highlights and notes, and their UI in the reader.
    val saved: com.retro99.reader.ui.reader.saved.ReaderSavedState = com.retro99.reader.ui.reader.saved.ReaderSavedState(),
    // When true, shows the audiobook-style audio-only UI instead of the EPUB text view
    val isAudioOnlyMode: Boolean = false,
    val isTtsReadAloud: Boolean = false,
    val ttsVoices: List<TtsVoice> = emptyList(),
    val selectedTtsVoiceId: String? = null,
    val isTtsVoicePreparing: Boolean = false,
    val preparingTtsVoicePackage: NeuralVoicePackage? = null,
    val ttsVoicePreparationProgress: TtsPreparationProgress? = null,
    val failedTtsVoicePackage: NeuralVoicePackage? = null,
    val deletingTtsVoicePackage: NeuralVoicePackage? = null,
    val failedTtsVoicePackageDeletion: NeuralVoicePackage? = null,
    val hasAcceptedSupertonicTerms: Boolean = false,
    val ttsPreviewingVoiceId: String? = null,
    val isTtsPreviewPlaying: Boolean = false,
    val isVoiceSettingsVisible: Boolean = false,
    /** Voice to select once its pack finishes downloading. */
    val pendingTtsVoiceId: String? = null,
    /** Language of the open book as a BCP 47 tag, when the publication declares one. */
    val bookLanguage: String? = null,
    val isListening: Boolean = false,
    // Source the user chose while a book has both; null means the book's default source.
    val activeSource: ListenSource? = null,
    // Zero-based index and total of the sentence being read by the device voice.
    val ttsSentenceIndex: Int = 0,
    val ttsSentenceCount: Int = 0,
    val isListenSheetVisible: Boolean = false,
    val isBookSearchVisible: Boolean = false,
    val bookSearchQuery: String = "",
    val bookSearchSessionId: Long = 0,
    val bookSearchResults: List<ReaderSearchResult> = emptyList(),
    val isBookSearchLoading: Boolean = false,
    val bookSearchFailed: Boolean = false,
    val bookSearchComplete: Boolean = false,
    /** Number of kept matches; equals [bookSearchResults].size. */
    val bookSearchCount: Int = 0,
    /** True when matches were dropped at the result limit. */
    val bookSearchCapped: Boolean = false,
    val bookSearchAvailable: Boolean = true,
    val bookSearchNoTextLayer: Boolean = false,
    val bookSearchIgnoresCaseAndAccents: Boolean = false,
    val bookSearchRecents: List<RecentBookSearch> = emptyList(),
    val bookSearchReadingOrder: List<String> = emptyList(),
    val bookSearchBoundaries: List<SearchChapterBoundary> = emptyList(),
    val selectedSearchIndex: Int? = null,
    val isFindBarVisible: Boolean = false,
    val searchOrigin: PositionUiModel? = null,
    val searchPageTurns: Int = 0,
    // Spoiler protection: everything beyond the boundary is hidden until explicitly revealed.
    /** The furthest point reached in this book; null means nothing is hidden. */
    val searchBoundary: SearchBoundaryMark? = null,
    /** Reveal applies to the current query only. */
    val searchAheadRevealed: Boolean = false,
    /** Book-search results index where the "After your page" section starts, once revealed. */
    val searchAheadSplitIndex: Int? = null,
    val isSearchAheadLoading: Boolean = false,
    val searchAheadComplete: Boolean = false,
    /** Find-bar prompt offering to search past the boundary. */
    val showSearchContinuePrompt: Boolean = false,
) {
    /**
     * Whether this is a ReadAloud book with media overlay support.
     * Both conditions must be true: the book type must be READALOUD and
     * the publication must have media overlays.
     */
    val isReadAloud: Boolean
        get() = bookType == BookType.READALOUD && publicationState?.publication?.hasMediaOverlays == true

    /** Which audio system drives playback: recorded narration is the default when the book has it. */
    val listenSource: ListenSource
        get() = activeSource ?: if (isReadAloud) ListenSource.NARRATION else ListenSource.DEVICE_VOICE

    /** True when the book has recorded narration and the device can also read it aloud. */
    val canSwitchListenSource: Boolean
        get() = isReadAloud && isTtsReadAloud

    /**
     * Convenience accessor for the current reader settings.
     * Returns null if no publication is loaded.
     */
    val currentSettings get() = publicationState?.settings

    /**
     * Convenience accessor for the current reading position.
     * Returns null if no publication is loaded or position is not set.
     */
    val currentPosition get() = publicationState?.position
}

/** Tabs of the reader Contents sheet. */
enum class ContentsTab { CHAPTERS, SAVED }
