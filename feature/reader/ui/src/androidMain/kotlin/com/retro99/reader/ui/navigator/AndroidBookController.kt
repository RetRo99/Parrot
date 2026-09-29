package com.retro99.reader.ui.navigator

import com.retro99.reader.domain.model.ReaderSettingsDomainModel.Companion.DEFAULT_DOUBLE_TAP_TIMEOUT_MS
import com.retro99.reader.domain.model.ReaderSettingsDomainModel.Companion.DEFAULT_HIGHLIGHT_COLOR
import com.retro99.reader.domain.model.ReaderSettingsDomainModel.Companion.DEFAULT_UNDERLINE_COLOR
import com.retro99.reader.ui.di.ReaderScope
import com.retro99.reader.ui.model.ChapterInfo
import com.retro99.reader.ui.model.LocatorState
import com.retro99.reader.ui.model.PositionUiModel
import com.retro99.reader.ui.model.ReadAloudHighlightStyle
import com.retro99.reader.ui.model.ReaderSettingsUiModel
import com.retro99.reader.ui.model.ReaderTextAlignUi
import com.retro99.reader.ui.model.ReaderThemeUi
import com.retro99.reader.ui.reader.ReaderSearchResult
import com.retro99.reader.ui.tts.TtsSentence
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.launch
import org.readium.r2.shared.ExperimentalReadiumApi
import org.koin.core.annotation.Scope
import org.koin.core.annotation.Scoped
import org.readium.r2.navigator.DecorableNavigator
import org.readium.r2.navigator.Decoration
import org.readium.r2.navigator.epub.EpubNavigatorFragment
import org.readium.r2.navigator.epub.EpubPreferences
import org.readium.r2.navigator.preferences.FontFamily
import org.readium.r2.navigator.preferences.TextAlign
import org.readium.r2.navigator.preferences.Theme
import org.readium.r2.shared.publication.Link
import org.readium.r2.shared.publication.Locator
import org.readium.r2.shared.publication.Publication
import org.readium.r2.shared.publication.services.search.search
import org.readium.r2.shared.util.Url
import org.readium.r2.shared.util.mediatype.MediaType

/** Decoration group name for ReadAloud text highlighting */
private const val READALOUD_DECORATION_GROUP = "readaloud"

/**
 * Android implementation of [BookController] using Readium's EpubNavigatorFragment.
 *
 * This controller wraps the Readium navigator fragment and provides navigation
 * and settings functionality. It is created by the View after the publication
 * is ready and the navigator fragment is instantiated.
 *
 * For ReadAloud books with media overlays, this controller delegates audio playback
 * to [AndroidAudioController] and handles text highlighting in the navigator.
 *
 * @param context Android context for creating the media player
 * @param analytics Analytics instance for logging errors and events
 * @param smilParser Parser for SMIL media overlay files
 * @param quickScanner Quick scanner for SMIL file indexing
 */
@Scope(ReaderScope::class)
@Scoped(binds = [BookController::class])
class AndroidBookController internal constructor() : BookController {

    private val _navigator = MutableStateFlow<EpubNavigatorFragment?>(null)
    private var publication: Publication? = null
    private var controllerScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var pendingPageTurnJob: Job? = null

    /**
     * Queue of actions to execute when the navigator becomes available.
     * Actions are executed in order and cleared after execution.
     */
    private val pendingActions = mutableListOf<(EpubNavigatorFragment) -> Unit>()

    /**
     * Current reader settings, used for highlight color (ARGB Int).
     */
    private var highlightColorArgb: Int = DEFAULT_HIGHLIGHT_COLOR

    /**
     * Current reader settings, used for underline color (ARGB Int).
     */
    private var underlineColorArgb: Int = DEFAULT_UNDERLINE_COLOR

    /**
     * Current highlight style setting.
     */
    private var highlightStyle: ReadAloudHighlightStyle = ReadAloudHighlightStyle.HIGHLIGHT

    /**
     * Current double-tap timeout in milliseconds.
     */
    private var doubleTapTimeoutMs: Int = DEFAULT_DOUBLE_TAP_TIMEOUT_MS

    private val sentenceDoubleTapRecognizer = SentenceDoubleTapRecognizer()

    /**
     * Current highlighted locator, used to refresh decoration when settings change.
     */
    private var currentHighlightedLocator: Locator? = null

    /**
     * Tracks the last sentence that triggered a page turn to avoid duplicate turns.
     */
    private var lastPageTurnSentenceId: String? = null

    /**
     * Cache for chapter word counts, keyed by chapter href.
     * Word count is static per chapter, so we only fetch it once per chapter.
     */
    private val chapterWordCountCache = mutableMapOf<String, Int>()

    /**
     * Cancels any pending page turn and resets the tracking state.
     */
    private fun cancelPendingPageTurn() {
        pendingPageTurnJob?.cancel()
        lastPageTurnSentenceId = null
    }

    /**
     * SharedFlow for emitting double-tap events on sentence elements.
     * Gesture events are not replayed to late subscribers.
     */
    private val _sentenceDoubleTapEvents = MutableSharedFlow<SentenceDoubleTapEvent>(
        extraBufferCapacity = 1,
    )

    /**
     * Flow of double-tap events on sentence elements.
     * Emits when the user double-taps on a sentence in the EPUB content.
     */
    override val sentenceDoubleTapEvents: Flow<SentenceDoubleTapEvent> =
        _sentenceDoubleTapEvents.asSharedFlow()

    /**
     * Whether this book has media overlays (ReadAloud capability).
     * Used to determine if double-tap detection should be enabled.
     */
    override var hasMediaOverlays: Boolean = false
        private set

    /**
     * Returns the navigator if initialized, null otherwise.
     */
    private val navigator: EpubNavigatorFragment?
        get() = _navigator.value

    /**
     * Safely executes an action with the navigator if it's initialized.
     * If the navigator is not yet initialized, the action is queued and will be
     * executed when [init] is called.
     */
    private fun withNavigator(action: (EpubNavigatorFragment) -> Unit) {
        val nav = navigator
        if (nav != null) {
            action(nav)
        } else {
            pendingActions.add(action)
        }
    }

    /*
     * Suspend version of [withNavigatorOrNull] for use in coroutines.
     * Returns null if the navigator hasn't been initialized yet.
     */
    private inline fun <T> withNavigatorOrNull(
        action: (EpubNavigatorFragment) -> T
    ): T? {
        return navigator?.let(action)
    }

    fun init(
        navigator: EpubNavigatorFragment,
        publication: Publication,
        hasMediaOverlays: Boolean = false,
    ) {
        _navigator.value = navigator
        this.publication = publication
        this.hasMediaOverlays = hasMediaOverlays

        // Execute any pending actions that were queued before initialization
        executePendingActions(navigator)

        // Media-overlay books already contain addressable sentence elements.
        if (hasMediaOverlays) {
            enableSentenceTapDetection()
        }
    }

    @OptIn(ExperimentalReadiumApi::class)
    override suspend fun search(query: String): List<ReaderSearchResult> {
        val currentPublication = publication ?: return emptyList()
        val iterator = currentPublication.search(query) ?: return emptyList()
        return try {
            val results = mutableListOf<ReaderSearchResult>()
            iterator.forEach { collection ->
                collection.locators.forEach { locator ->
                    val text = locator.text
                    val snippet = listOfNotNull(text.before, text.highlight, text.after)
                        .joinToString(" ") { it.trim() }
                        .replace(Regex("\\s+"), " ")
                    results += ReaderSearchResult(
                        href = locator.href.toString(),
                        type = locator.mediaType.toString(),
                        title = locator.title,
                        progression = locator.locations.progression,
                        position = locator.locations.position,
                        totalProgression = locator.locations.totalProgression,
                        snippet = snippet,
                    )
                }
            }
            results
        } finally {
            iterator.close()
        }
    }

    /**
     * Executes all pending actions and clears the queue.
     */
    private fun executePendingActions(navigator: EpubNavigatorFragment) {
        pendingActions.forEach { action ->
            action(navigator)
        }
        pendingActions.clear()
    }

    /**
     * Injects the tap detection JavaScript into the navigator's WebView.
     * This script listens for click events and calls back to native code.
     * Native code handles double-tap detection timing.
     *
     * Uses a small delay to ensure the WebView content is loaded.
     * The script has built-in protection against multiple injections.
     */
    override fun enableSentenceTapDetection() {
        controllerScope.launch {
            // Small delay to ensure WebView content is loaded
            delay(SCRIPT_INJECTION_DELAY_MS)
            val script = DoubleTapDetector.getTapDetectionScript("SentenceTap")
            withNavigatorOrNull { it.evaluateJavascript(script) }
        }
    }

    /**
     * Called from JavaScript when a sentence element is tapped.
     * This method is invoked via the JavaScript interface from a background thread.
     * We handle double-tap detection natively for consistent timing control.
     */
    fun onSentenceTap(fragmentId: String) {
        val isDoubleTap = sentenceDoubleTapRecognizer.registerTap(
            fragmentId = fragmentId,
            timeoutMs = doubleTapTimeoutMs,
        )
        if (!isDoubleTap) return

        controllerScope.launch {
            // Access navigator state on Main thread for thread safety
            val currentHref = withNavigatorOrNull { navigator ->
                navigator.currentLocator.value.href.toString()
            }
            _sentenceDoubleTapEvents.emit(
                SentenceDoubleTapEvent(
                    fragmentId = fragmentId,
                    chapterHref = currentHref,
                ),
            )
        }
    }

    /**
     * Flow of current reading position/locator changes.
     * Converts Readium's Locator to the common LocatorState model.
     * Enriches the state with chapter info (page position and cached word count).
     * Delays the first emission by 500ms.
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    override val currentLocator: Flow<LocatorState> = _navigator.flatMapLatest { navigator ->
        navigator?.currentLocator?.map { locator ->
            val href = locator.href.toString()

            // Get or fetch chapter word count (cached per chapter)
            val cachedWordCount = getOrFetchChapterWordCount(href)

            // Fetch chapter info with page position and word count
            val chapterInfo = fetchChapterInfo(cachedWordCount)

            val cssSelector = fetchFirstVisibleCssSelector(navigator)

            LocatorState(
                href = href,
                type = locator.mediaType.toString(),
                title = locator.title,
                progression = locator.locations.progression,
                position = locator.locations.position,
                totalProgression = locator.locations.totalProgression,
                fragments = locator.locations.fragments,
                chapterInfo = chapterInfo,
                cssSelector = cssSelector,
            )
        } ?: flowOf()
    }.onStart {
        delay(500)
    }.onEach {
        cancelPendingPageTurn()
    }

    private suspend fun fetchFirstVisibleCssSelector(navigator: EpubNavigatorFragment): String? {
        val enrichedLocator = navigator.firstVisibleElementLocator() ?: return null
        return enrichedLocator.locations.otherLocations["cssSelector"] as? String
    }

    /**
     * Fetches the chapter info from the WebView.
     * This is called on every locator change since page info depends on scroll position.
     * Word count is passed in from the cache.
     */
    private suspend fun fetchChapterInfo(cachedWordCount: Int?): ChapterInfo? {
        return withNavigatorOrNull { nav ->
            val script = ChapterPageCalculator.getPageCalculationScript()
            val rawResult =
                nav.evaluateJavascript(script) ?: return@withNavigatorOrNull null
            val cleanJson = cleanWebViewJson(rawResult)
            ChapterPageCalculator.parsePageResult(cleanJson, cachedWordCount)
        }
    }

    /**
     * Gets the cached word count for a chapter, or fetches it if not cached.
     * Word count is static per chapter, so we only fetch it once.
     */
    private suspend fun getOrFetchChapterWordCount(href: String): Int? {
        // Return cached value if available
        chapterWordCountCache[href]?.let { return it }

        // Fetch and cache the word count
        return withNavigatorOrNull { nav ->
            val script = ChapterWordCountCalculator.getWordCountScript()
            val rawResult = nav.evaluateJavascript(script)
                ?: return@withNavigatorOrNull null
            val cleanJson = cleanWebViewJson(rawResult)
            val wordCount = ChapterWordCountCalculator.parseWordCountResult(cleanJson)

            // Cache the result
            if (wordCount != null) {
                chapterWordCountCache[href] = wordCount
            }

            wordCount
        }
    }

    override fun goToNextPage() {
        withNavigator { it.goForward() }
    }

    override fun goToPreviousPage() {
        withNavigator { it.goBackward() }
    }

    override fun goToChapter(href: String) {
        val url = Url(href) ?: return
        val link = Link(href = url)
        withNavigator { it.go(link) }
    }

    override fun goToLocator(locator: LocatorState) {
        val androidLocator = locator.toAndroidLocator() ?: return
        withNavigator { it.go(androidLocator) }
    }

    override fun setSettings(settings: ReaderSettingsUiModel) {
        val highlightColorChanged = highlightColorArgb != settings.highlightColor
        val underlineColorChanged = underlineColorArgb != settings.underlineColor
        val styleChanged = highlightStyle != settings.highlightStyle

        highlightColorArgb = settings.highlightColor
        underlineColorArgb = settings.underlineColor
        highlightStyle = settings.highlightStyle
        doubleTapTimeoutMs = settings.doubleTapTimeoutMs

        withNavigator { nav ->
            // Save the current position before applying settings
            // Font size changes cause re-pagination which can lose the position
            val currentPosition = nav.currentLocator.value
            nav.submitPreferences(settings.toEpubPreferences())

            // Restore the position after settings are applied
            // We need a delay because submitPreferences triggers async re-pagination in the WebView
            if (nav.settings.value.fontSize != settings.fontSize) {
                currentPosition.let { position ->
                    controllerScope.launch {
                        delay(SETTINGS_REPAGINATION_DELAY_MS)
                        nav.go(position)
                    }
                }
            }
        }

        // Refresh current decoration if highlight color, underline color, or style changed
        if ((highlightColorChanged || underlineColorChanged || styleChanged) && currentHighlightedLocator != null) {
            refreshCurrentDecoration()
        }
    }

    /**
     * Refreshes the current decoration with updated highlight color/style.
     */
    private fun refreshCurrentDecoration() {
        val locator = currentHighlightedLocator ?: return
        controllerScope.launch {
            withNavigatorOrNull { nav ->
                val decorableNavigator = nav as? DecorableNavigator ?: return@withNavigatorOrNull
                val decorations = createDecorations(locator)
                decorableNavigator.applyDecorations(decorations, READALOUD_DECORATION_GROUP)
            }
        }
    }

    override fun goToPosition(position: PositionUiModel) {
        val locator = position.toAndroidLocator() ?: return
        withNavigator { it.go(locator) }
    }

    /**
     * Applies a highlight decoration to the given locator and handles split sentences.
     *
     * For sentences that are split across pages, this method will:
     * 1. Navigate to the locator (ensuring the sentence is visible)
     * 2. Apply the highlight decoration
     * 3. Check if the sentence spans pages (partially visible)
     * 4. Schedule a page turn after the visible portion has been read
     */
    override suspend fun applyHighlightWithPageTurn(
        locator: LocatorState,
        sentenceDurationMs: Long,
    ) {
        withNavigatorOrNull { nav ->
            val decorableNavigator =
                nav as? DecorableNavigator ?: return@withNavigatorOrNull
            val androidLocator = locator.toAndroidLocator() ?: return@withNavigatorOrNull
            val fragmentId = locator.fragments?.firstOrNull()

            // First navigate to the locator to ensure the sentence is visible
            nav.go(androidLocator)

            currentHighlightedLocator = androidLocator
            val decorations = createDecorations(androidLocator)
            decorableNavigator.applyDecorations(decorations, READALOUD_DECORATION_GROUP)

            // Cancel any pending page turn from a previous sentence
            cancelPendingPageTurn()

            // Check visibility and schedule page turn if sentence spans pages
            if (fragmentId != null) {
                val visibility = checkSentenceVisibility(fragmentId)
                if (visibility.needsPageTurn) {
                    // Calculate delay based on visible fraction
                    val delayMs = (visibility.visibleFraction * sentenceDurationMs).toLong()
                        .coerceAtLeast(MIN_PAGE_TURN_DELAY_MS)

                    lastPageTurnSentenceId = fragmentId
                    pendingPageTurnJob = controllerScope.launch {
                        delay(delayMs)
                        withNavigator { it.goForward() }
                    }
                }
            }
        }
    }

    /**
     * Checks the visibility of a sentence element on the current page using JavaScript.
     *
     * Uses `getClientRects()` to get the bounding rectangles for each line of the sentence.
     * In paginated EPUB mode, lines that are on the next virtual page will have their
     * left edge beyond the viewport width.
     *
     * A page turn is triggered if:
     * 1. The sentence is entirely on the next page (all lines off-screen)
     * 2. Less than 50% of the sentence is visible
     * 3. The sentence starts in the "awkward buffer" (last 10% of page width)
     *
     * @param elementId The ID of the sentence element to check
     * @return The visibility result including visible fraction and whether page turn is needed
     */
    override suspend fun checkSentenceVisibility(elementId: String): SentenceVisibilityResult {
        return withNavigatorOrNull { nav ->
            val script = SentenceVisibilityChecker.getVisibilityCheckScript(elementId)

            // Execute JS to get the element's geometry relative to the viewport
            val rawResult = nav.evaluateJavascript(script)
                ?: return@withNavigatorOrNull SentenceVisibilityResult.FULLY_VISIBLE

            val cleanJson = cleanWebViewJson(rawResult)
            SentenceVisibilityChecker.parseVisibilityResult(cleanJson, elementId)
        } ?: SentenceVisibilityResult.FULLY_VISIBLE
    }

    override suspend fun getChapterPageInfo(): ChapterInfo? {
        // For manual refresh, we don't have the href, so we can't include cached word count
        // This is fine since this method is mainly used for page info after settings changes
        return fetchChapterInfo(cachedWordCount = null)
    }

    override suspend fun getVisibleSentenceId(): String? {
        return withNavigatorOrNull { nav ->
            val script = VisibleSentenceDetector.getScript()
            val rawResult = nav.evaluateJavascript(script)
                ?: return@withNavigatorOrNull null

            val cleanJson = cleanWebViewJson(rawResult)
            VisibleSentenceDetector.parseResult(cleanJson)
        }
    }

    override suspend fun hasReadableContent(): Boolean {
        return withNavigatorOrNull { nav ->
            val rawResult = nav.evaluateJavascript(
                ChapterSentenceExtractor.getReadableContentCheckScript(),
            ) ?: return@withNavigatorOrNull false

            rawResult.trim().removeSurrounding("\"").equals("true", ignoreCase = true)
        } ?: false
    }

    override suspend fun getChapterSentences(): List<TtsSentence> {
        return withNavigatorOrNull { nav ->
            val script = ChapterSentenceExtractor.getScript()
            val rawResult = nav.evaluateJavascript(script)
                ?: return@withNavigatorOrNull emptyList()

            val cleanJson = cleanWebViewJson(rawResult)
            ChapterSentenceExtractor.parseResult(cleanJson)
        } ?: emptyList()
    }

    /**
     * Cleans JSON returned from WebView's evaluateJavascript.
     *
     * WebView returns strings as "\"{\\\"key\\\":\\\"val\\\"}\"".
     * We must strip the outer quotes and unescape.
     */
    private fun cleanWebViewJson(rawResult: String): String {
        return if (rawResult.startsWith("\"") && rawResult.endsWith("\"")) {
            rawResult.substring(1, rawResult.length - 1)
                .replace("\\\"", "\"")
                .replace("\\\\", "\\")
        } else {
            rawResult
        }
    }

    /**
     * Creates decorations based on the current highlight style setting.
     * Uses highlightColorArgb for highlight decorations and underlineColorArgb for underline decorations.
     */
    private fun createDecorations(locator: Locator): List<Decoration> {
        return when (highlightStyle) {
            ReadAloudHighlightStyle.HIGHLIGHT -> listOf(
                Decoration(
                    id = "readaloud-highlight",
                    locator = locator,
                    style = Decoration.Style.Highlight(
                        tint = highlightColorArgb,
                        isActive = false,
                    ),
                ),
            )

            ReadAloudHighlightStyle.UNDERLINE -> listOf(
                Decoration(
                    id = "readaloud-underline",
                    locator = locator,
                    style = Decoration.Style.Underline(
                        tint = underlineColorArgb,
                        isActive = false,
                    ),
                ),
            )

            ReadAloudHighlightStyle.HIGHLIGHT_UNDERLINE -> listOf(
                Decoration(
                    id = "readaloud-highlight",
                    locator = locator,
                    style = Decoration.Style.Highlight(
                        tint = highlightColorArgb,
                        isActive = false,
                    ),
                ),
                Decoration(
                    id = "readaloud-underline",
                    locator = locator,
                    style = Decoration.Style.Underline(
                        tint = underlineColorArgb,
                        isActive = false,
                    ),
                ),
            )
        }
    }

    override fun close() {
        pendingPageTurnJob?.cancel()
        pendingActions.clear()
        currentHighlightedLocator = null
        sentenceDoubleTapRecognizer.reset()
        // Note: No need to call getRemoveDoubleTapDetectorScript() here.
        // The WebView and its JavaScript context will be destroyed when the
        // fragment is removed, so the event listener will be cleaned up automatically.
        controllerScope.cancel()
        _navigator.value = null
        publication = null
    }

    private companion object Companion {
        /** Minimum delay before page turn to avoid jarring transitions */
        private const val MIN_PAGE_TURN_DELAY_MS = 200L

        /** Delay before injecting tap detection script to ensure WebView is ready */
        private const val SCRIPT_INJECTION_DELAY_MS = 500L

        /** Delay after settings change to allow WebView re-pagination before restoring position */
        private const val SETTINGS_REPAGINATION_DELAY_MS = 300L
    }
}

/**
 * Extension function to convert ReaderSettingsUiModel to EpubPreferences.
 * This is used for initial preferences when creating the navigator and for dynamic updates.
 */
fun ReaderSettingsUiModel.toEpubPreferences(): EpubPreferences {
    val fontWeightOverride = fontWeight.toReadiumFontWeightOverride()
    return EpubPreferences(
        fontSize = fontSize,
        fontFamily = fontFamily.toReadiumFontFamily(),
        fontWeight = fontWeightOverride,
        textNormalization = textNormalization,
        scroll = scrollMode,
        theme = theme.toReadiumTheme(),
        lineHeight = lineHeight.toDouble(),
        paragraphSpacing = paragraphSpacing,
        pageMargins = calculatePageMargins(),
        textAlign = textAlign.toReadiumTextAlign(),
        publisherStyles = publisherStyles,
    )
}

/**
 * Converts font family string to Readium's FontFamily.
 * Returns null for "default" to use publisher's font.
 */
private fun String.toReadiumFontFamily(): FontFamily? = when (this) {
    "default" -> null
    else -> FontFamily(this)
}

private fun Double.toReadiumFontWeightOverride(): Double? =
    takeUnless { it == 1.0 }

/**
 * Converts ReaderThemeUi to Readium's Theme.
 * Note: SYSTEM theme is not directly supported by Readium, so we return null to use default.
 */
private fun ReaderThemeUi.toReadiumTheme(): Theme? = when (this) {
    ReaderThemeUi.LIGHT -> Theme.LIGHT
    ReaderThemeUi.DARK -> Theme.DARK
    ReaderThemeUi.SEPIA -> Theme.SEPIA
    ReaderThemeUi.SYSTEM -> null // Let Readium use its default
}

/**
 * Converts ReaderTextAlignUi to Readium's TextAlign.
 */
private fun ReaderTextAlignUi.toReadiumTextAlign(): TextAlign = when (this) {
    ReaderTextAlignUi.START -> TextAlign.START
    ReaderTextAlignUi.END -> TextAlign.END
    ReaderTextAlignUi.CENTER -> TextAlign.CENTER
    ReaderTextAlignUi.JUSTIFY -> TextAlign.JUSTIFY
}

/**
 * Calculates page margins as a factor for Readium.
 * Readium's pageMargins is a multiplier applied to horizontal margins only.
 * We convert our dp-based horizontal margin to a factor based on a 16dp baseline.
 * Vertical margins are applied separately via Compose padding on the reader view.
 */
private fun ReaderSettingsUiModel.calculatePageMargins(): Double {
    // Baseline is 16dp, so 16dp = 1.0 factor
    return (marginHorizontal / 16.0).coerceIn(0.0, 4.0)
}

/**
 * Extension function to convert PositionUiModel to Readium Locator.
 * Returns null if the URL or mediaType cannot be parsed.
 */
fun PositionUiModel.toAndroidLocator(): Locator? {
    val url = Url(href) ?: return null
    val mediaType = MediaType(type) ?: return null

    val otherLocations = mutableMapOf<String, Any>()
    cssSelector?.let { otherLocations["cssSelector"] = it }

    return Locator(
        href = url,
        mediaType = mediaType,
        title = title,
        locations = Locator.Locations(
            progression = progression,
            position = position,
            totalProgression = totalProgression,
            otherLocations = otherLocations,
        ),
    )
}

fun LocatorState.toAndroidLocator(): Locator? {
    val url = Url(href) ?: return null
    val mediaType = MediaType(type) ?: return null

    return Locator(
        href = url,
        mediaType = mediaType,
        title = title,
        locations = Locator.Locations(
            progression = progression,
            position = position,
            totalProgression = totalProgression,
            fragments = fragments ?: emptyList(),
        ),
    )
}
