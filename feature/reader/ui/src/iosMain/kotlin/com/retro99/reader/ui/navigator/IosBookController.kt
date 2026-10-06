package com.retro99.reader.ui.navigator

import com.retro99.reader.domain.model.ReaderSettingsDomainModel.Companion.DEFAULT_DOUBLE_TAP_TIMEOUT_MS
import com.retro99.base.nowMillis
import com.retro99.reader.ui.bridge.AudioLocator
import com.retro99.reader.ui.bridge.EpubReaderBridge
import com.retro99.reader.ui.bridge.EpubWebViewGeometry
import com.retro99.reader.ui.bridge.EpubReaderSettings
import com.retro99.reader.ui.bridge.SavedDecorationLocator
import com.retro99.reader.ui.reader.ReaderSearchResult
import com.retro99.reader.ui.reader.ReaderSearchBatch
import com.retro99.reader.ui.reader.BookNotSearchableException
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.callbackFlow
import com.retro99.reader.ui.di.ReaderScope
import com.retro99.reader.ui.model.ChapterInfo
import com.retro99.reader.ui.model.LocatorState
import com.retro99.reader.ui.model.PositionUiModel
import com.retro99.reader.ui.model.ReaderSettingsUiModel
import com.retro99.server.api.TextAnchor
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.koin.core.annotation.Scope
import org.koin.core.annotation.Scoped
import kotlin.coroutines.resume
import kotlin.math.abs

/**
 * iOS implementation of [BookController].
 * Delegates navigation and settings operations to the [EpubReaderBridge].
 */
@Scope(ReaderScope::class)
@Scoped(binds = [BookController::class])
class IosBookController(
    private val bridge: EpubReaderBridge,
) : BookController {

    override val hasMediaOverlays: Boolean
        get() = bridge.hasMediaOverlays()

    private val _currentLocator = MutableSharedFlow<LocatorState>(
        replay = 1,
        extraBufferCapacity = 1,
    )
    override val currentLocator: Flow<LocatorState> = _currentLocator

    /**
     * SharedFlow for emitting double-tap events on sentence elements.
     * Gesture events are not replayed to late subscribers.
     */
    private val _sentenceDoubleTapEvents = MutableSharedFlow<SentenceDoubleTapEvent>(
        extraBufferCapacity = 1,
    )
    override val sentenceDoubleTapEvents: Flow<SentenceDoubleTapEvent> = _sentenceDoubleTapEvents

    private var controllerScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var pendingPageTurnJob: Job? = null
    private val locatorMutex = Mutex()

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
     * Current double-tap timeout in milliseconds.
     */
    private var doubleTapTimeoutMs: Int = DEFAULT_DOUBLE_TAP_TIMEOUT_MS

    private val sentenceDoubleTapRecognizer = SentenceDoubleTapRecognizer()

    /** Echo dedupe for [nativeReaderTaps]: last tap's fractions and down time. */
    private var lastTapX = Double.NaN
    private var lastTapY = Double.NaN
    private var lastTapAtMs = 0L

    private val _selectionChanges = MutableSharedFlow<Boolean>(extraBufferCapacity = 16)
    override val selectionChanges: Flow<Boolean> = _selectionChanges

    private val _nativeReaderTaps = MutableSharedFlow<NativeReaderTap>(extraBufferCapacity = 16)
    override val nativeReaderTaps: Flow<NativeReaderTap> = _nativeReaderTaps
    override val handlesNativeTaps: Boolean = true

    private val _savedDecorationTaps = MutableSharedFlow<String>(extraBufferCapacity = 4)
    override val savedDecorationTaps: Flow<String> = _savedDecorationTaps

    init {
        setupCallbacks()
        bridge.setOnSelectionChangedCallback { present -> _selectionChanges.tryEmit(present) }
        bridge.setOnSavedDecorationTapCallback { id -> _savedDecorationTaps.tryEmit(id) }
        bridge.setOnReaderTapCallback { tap ->
            // Readium surfaces one physical tap through both its touches pipeline and its
            // fallback tap recognizer; the second arrival is an echo of the first.
            val now = nowMillis()
            val isEcho = !lastTapX.isNaN() &&
                now - lastTapAtMs < NATIVE_TAP_ECHO_MS &&
                abs(tap.xFraction - lastTapX) < NATIVE_TAP_ECHO_SLOP &&
                abs(tap.yFraction - lastTapY) < NATIVE_TAP_ECHO_SLOP
            lastTapX = tap.xFraction
            lastTapY = tap.yFraction
            lastTapAtMs = now
            if (!isEcho) _nativeReaderTaps.tryEmit(tap)
        }
        // Only inject tap detection script for ReadAloud books
        if (bridge.hasMediaOverlays()) {
            enableSentenceTapDetection()
        }
    }

    private fun setupCallbacks() {
        bridge.setOnPositionChangedCallback { locator ->
            // The fair lock keeps emissions in page-turn order under fast flips.
            controllerScope.launch {
                locatorMutex.withLock {
                    // Get or fetch chapter word count (cached per chapter)
                    val cachedWordCount = getOrFetchChapterWordCount(locator.href)

                    // Fetch chapter info with page position and word count
                    val chapterInfo = fetchChapterInfo(cachedWordCount)
                    val textAnchor = fetchTextAnchor()

                    _currentLocator.emit(
                        LocatorState(
                            href = locator.href,
                            type = locator.type,
                            title = locator.title,
                            progression = locator.progression,
                            position = locator.position,
                            totalProgression = locator.totalProgression,
                            fragments = null,
                            chapterInfo = chapterInfo,
                            textAnchor = textAnchor,
                        ),
                    )
                }
            }
        }

        // Set up callback for tap events from JavaScript
        // Double-tap detection is handled natively for consistent timing control
        bridge.setOnSentenceTapCallback { fragmentId ->
            onSentenceTap(fragmentId)
        }
    }

    /**
     * Called from JavaScript when a sentence element is tapped.
     * We handle double-tap detection natively for consistent timing control.
     */
    private fun onSentenceTap(fragmentId: String) {
        val isDoubleTap = sentenceDoubleTapRecognizer.registerTap(
            fragmentId = fragmentId,
            timeoutMs = doubleTapTimeoutMs,
        )
        if (!isDoubleTap) return

        val currentHref = _currentLocator.replayCache.firstOrNull()?.href
        controllerScope.launch {
            _sentenceDoubleTapEvents.emit(
                SentenceDoubleTapEvent(
                    fragmentId = fragmentId,
                    chapterHref = currentHref,
                ),
            )
        }
    }

    /**
     * Fetches the chapter info from the WebView.
     * This is called on every locator change since page info depends on scroll position.
     * Word count is passed in from the cache.
     */
    private suspend fun fetchChapterInfo(cachedWordCount: Int?): ChapterInfo? {
        val script = ChapterPageCalculator.getPageCalculationScript()

        val rawResult: String? = suspendCancellableCoroutine { continuation ->
            bridge.evaluateJavaScript(script) { result ->
                continuation.resume(result)
            }
        }

        if (rawResult == null) {
            return null
        }

        return ChapterPageCalculator.parsePageResult(rawResult, cachedWordCount)
    }

    /** The text around the start of the visible page, for the position's text anchor. */
    private suspend fun fetchTextAnchor(): TextAnchor? {
        val script = ChapterSentenceExtractor.getTextAnchorScript()
        val rawResult: String? = suspendCancellableCoroutine { continuation ->
            bridge.evaluateJavaScript(script) { result ->
                continuation.resume(result)
            }
        }
        return rawResult?.let(ChapterSentenceExtractor::parseTextAnchor)
    }

    /**
     * Gets the cached word count for a chapter, or fetches it if not cached.
     * Word count is static per chapter, so we only fetch it once.
     */
    private suspend fun getOrFetchChapterWordCount(href: String): Int? {
        // Return cached value if available
        chapterWordCountCache[href]?.let { return it }

        // Fetch and cache the word count
        val script = ChapterWordCountCalculator.getWordCountScript()

        val rawResult: String? = suspendCancellableCoroutine { continuation ->
            bridge.evaluateJavaScript(script) { result ->
                continuation.resume(result)
            }
        }

        if (rawResult == null) {
            return null
        }

        val wordCount = ChapterWordCountCalculator.parseWordCountResult(rawResult)

        // Cache the result
        if (wordCount != null) {
            chapterWordCountCache[href] = wordCount
        }

        return wordCount
    }

    /**
     * Injects the tap detection JavaScript into the navigator's WebView.
     * Native code handles double-tap detection timing for consistent behavior.
     *
     * Uses a small delay to ensure the WebView content is loaded.
     * The script has built-in protection against multiple injections.
     */
    override fun enableSentenceTapDetection() {
        controllerScope.launch {
            // Small delay to ensure WebView content is loaded
            delay(SCRIPT_INJECTION_DELAY_MS)
            val script = DoubleTapDetector.getTapDetectionScript("SentenceTap")
            bridge.evaluateJavaScript(script) { _ -> }
        }
    }

    override fun goToNextPage() {
        bridge.goToNextPage()
    }

    override fun goToPreviousPage() {
        bridge.goToPreviousPage()
    }

    override fun goToChapter(href: String) {
        bridge.goToChapter(href)
    }

    override fun goToLocator(locator: LocatorState) {
        bridge.goToPosition(
            href = locator.href,
            type = locator.type,
            progression = locator.progression,
            position = locator.position,
            totalProgression = locator.totalProgression,
        )
    }

    override fun setSettings(settings: ReaderSettingsUiModel) {
        doubleTapTimeoutMs = settings.doubleTapTimeoutMs
        bridge.setSettings(settings = EpubReaderSettings.from(settings))
    }

    override fun goToPosition(position: PositionUiModel) {
        bridge.goToPosition(
            href = position.href,
            type = position.type,
            progression = position.progression,
            position = position.position,
            totalProgression = position.totalProgression,
        )
    }

    override val isSearchable: Boolean get() = bridge.isSearchable()
    override val searchIgnoresCaseAndAccents: Boolean get() = true
    override fun searchReadingOrder(): List<String> = bridge.searchReadingOrder()
    override suspend fun searchChapterBoundaries(): List<com.retro99.reader.ui.reader.SearchChapterBoundary> =
        suspendCancellableCoroutine { continuation ->
            bridge.searchChapterBoundaries { if (continuation.isActive) continuation.resume(it) }
        }
    private var searchSequence = 0

    override fun search(query: String): Flow<ReaderSearchBatch> = callbackFlow {
        val token = "search-${kotlin.random.Random.nextLong()}-${++searchSequence}"
        bridge.search(query, token, onBatch = { results, count, acknowledge ->
            launch(start = CoroutineStart.UNDISPATCHED) {
                try {
                    send(ReaderSearchBatch(results.map { result ->
                        ReaderSearchResult(
                            href = result.href,
                            type = result.type,
                            title = result.title,
                            progression = result.progression,
                            position = result.position,
                            totalProgression = result.totalProgression,
                            before = result.before,
                            match = result.match,
                            after = result.after,
                            index = result.index,
                            locatorJson = result.locatorJson,
                        )
                    }, count))
                } finally { acknowledge() }
            }
        }, onComplete = { count ->
            launch {
                send(ReaderSearchBatch(emptyList(), count, isComplete = true))
                close()
            }
        }, onError = { message, unavailable ->
            close(if (unavailable) BookNotSearchableException(noTextLayer = message == "no-text-layer") else IllegalStateException(message))
        })
        awaitClose { bridge.cancelSearch(token) }
    }

    override fun goToSearchResult(result: ReaderSearchResult) = bridge.goToSearchLocator(result.locatorJson)
    override fun decorateSearch(results: List<ReaderSearchResult>, selectedIndex: Int, accent: Int, soft: Int, onAccent: Int, eink: Boolean) {
        bridge.decorateSearch(results.map { it.locatorJson }, results.indexOfFirst { it.index == selectedIndex }, accent, soft, eink)
        results.firstOrNull { it.index == selectedIndex }?.let { result ->
            bridge.evaluateJavaScript(SearchAnchorResolver.script(result, if (eink) 0xff000000.toInt() else accent,
                if (eink) 0xffffffff.toInt() else onAccent)) {}
        }
    }
    override fun clearSearchDecorations() {
        bridge.clearSearchDecorations()
        bridge.evaluateJavaScript(SearchAnchorResolver.CLEAR) {}
    }
    override suspend fun searchSentenceId(result: ReaderSearchResult): String? = suspendCancellableCoroutine { continuation ->
        bridge.evaluateJavaScript(SearchAnchorResolver.script(result)) { value ->
            if (continuation.isActive) continuation.resume(value?.removeSurrounding("\"")?.takeUnless { it == "null" || it.isBlank() })
        }
    }

    /**
     * Applies a highlight decoration to the given locator and handles split sentences.
     *
     * For sentences that are split across pages, this method will:
     * 1. Apply the highlight immediately
     * 2. Check if the sentence is split (partially visible)
     * 3. Schedule a page turn after the visible portion has been read
     */
    override suspend fun applyHighlightWithPageTurn(
        locator: LocatorState,
        sentenceDurationMs: Long,
    ) {
        val fragmentId = locator.fragments?.firstOrNull()

        bridge.applyAudioHighlight(locator.toAudioLocator())

        // Check visibility and handle page turn if needed
        if (fragmentId != null) {
            val visibility = checkSentenceVisibility(fragmentId)

            if (visibility.needsPageTurn && fragmentId != lastPageTurnSentenceId) {
                // Cancel any pending page turn from a previous sentence
                pendingPageTurnJob?.cancel()

                // Calculate delay based on visible fraction
                val delayMs = (visibility.visibleFraction * sentenceDurationMs).toLong()
                    .coerceAtLeast(MIN_PAGE_TURN_DELAY_MS)

                lastPageTurnSentenceId = fragmentId
                pendingPageTurnJob = controllerScope.launch {
                    delay(delayMs)
                    bridge.goToNextPage()
                }
            } else if (!visibility.needsPageTurn) {
                // Sentence is fully visible, cancel any pending page turn
                pendingPageTurnJob?.cancel()
                lastPageTurnSentenceId = null
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
        val script = SentenceVisibilityChecker.getVisibilityCheckScript(elementId)

        val rawResult: String? = suspendCancellableCoroutine { continuation ->
            bridge.evaluateJavaScript(script) { result ->
                continuation.resume(result)
            }
        }

        if (rawResult == null) {
            return SentenceVisibilityResult.FULLY_VISIBLE
        }

        return SentenceVisibilityChecker.parseVisibilityResult(rawResult, elementId)
    }

    override suspend fun getChapterPageInfo(): ChapterInfo? {
        // For manual refresh, we don't have the href, so we can't include cached word count
        // This is fine since this method is mainly used for page info after settings changes
        return fetchChapterInfo(cachedWordCount = null)
    }

    override suspend fun getVisibleSentenceId(): String? {
        val script = VisibleSentenceDetector.getScript()

        val rawResult: String? = suspendCancellableCoroutine { continuation ->
            bridge.evaluateJavaScript(script) { result ->
                continuation.resume(result)
            }
        }

        if (rawResult == null) {
            return null
        }

        return VisibleSentenceDetector.parseResult(rawResult)
    }

    override suspend fun getVisibleTextRange(): VisibleTextRange? {
        val script = VisibleTextRangeDetector.getScript()
        val rawResult: String? = suspendCancellableCoroutine { continuation ->
            bridge.evaluateJavaScript(script) { result ->
                continuation.resume(result)
            }
        }
        return rawResult?.let(VisibleTextRangeDetector::parseResult)
    }

    override fun clearSelection() = bridge.clearSelection()

    /**
     * Maps the WebView-local selection rect onto the space the Compose toolbar is anchored
     * in, mirroring [com.retro99.reader.ui.navigator.AndroidBookController.selectionForToolbar].
     * The WKWebView carries its own frame and insets inside Readium's navigator, so the raw
     * getBoundingClientRect values would anchor the toolbar at the wrong position.
     */
    override suspend fun selectionForToolbar(): PageText? {
        val text = SavedPageScript.parseAnchor(runPageScript(SavedPageScript.selection())) ?: return null
        val rect = text.rect ?: return text
        val geometry = suspendCancellableCoroutine<EpubWebViewGeometry?> { continuation ->
            bridge.webViewGeometry { value -> if (continuation.isActive) continuation.resume(value) }
        } ?: return text
        if (geometry.width <= 0.0 || geometry.rootWidth <= 0.0) return text
        // viewport.width is the WebView's CSS px width, so this absorbs zoom; points are dp.
        val scale = geometry.width / (text.viewport?.width?.takeIf { it > 0.0 } ?: geometry.width)
        return text.copy(
            rect = PageRect(
                left = geometry.x + rect.left * scale,
                top = geometry.y + rect.top * scale,
                right = geometry.x + rect.right * scale,
                bottom = geometry.y + rect.bottom * scale,
            ),
            viewport = PageSize(width = geometry.rootWidth, height = geometry.rootHeight),
        )
    }

    override suspend fun runPageScript(script: String): String? = suspendCancellableCoroutine { continuation ->
        bridge.evaluateJavaScript(script) { value ->
            if (continuation.isActive) continuation.resume(value)
        }
    }

    override fun applySavedDecorations(marks: List<PageMark>) {
        bridge.applySavedDecorations(
            marks.filter { mark -> mark.tappable }.map { mark ->
                SavedDecorationLocator(
                    id = mark.id,
                    href = mark.href,
                    type = mark.mediaType ?: "application/xhtml+xml",
                    progression = mark.progression,
                    before = mark.before,
                    highlight = mark.quote,
                    after = mark.after,
                    fill = mark.fill,
                    darkPage = mark.darkPage,
                )
            },
        )
    }

    override fun close() {
        pendingPageTurnJob?.cancel()
        sentenceDoubleTapRecognizer.reset()
        bridge.setOnSelectionChangedCallback(null)
        bridge.setOnSavedDecorationTapCallback(null)
        bridge.setOnReaderTapCallback(null)
        // Note: No need to call getRemoveTapDetectorScript() here.
        // The WebView and its JavaScript context will be destroyed when the
        // navigator is closed, so the event listener will be cleaned up automatically.
        controllerScope.cancel()
        bridge.setOnPositionChangedCallback(null)
        bridge.setOnSentenceTapCallback(null)
    }

    private companion object {
        /** Minimum delay before page turn to avoid jarring transitions */
        private const val MIN_PAGE_TURN_DELAY_MS = 200L

        /** Delay before injecting tap detection script to ensure WebView is ready */
        private const val SCRIPT_INJECTION_DELAY_MS = 500L

        /** Echo dedupe for reader taps: max age and positional slop of an echo. */
        private const val NATIVE_TAP_ECHO_MS = 100L
        private const val NATIVE_TAP_ECHO_SLOP = 0.02
    }
}

private fun LocatorState.toAudioLocator(): AudioLocator {
    return AudioLocator(
        href = href,
        type = type,
        title = title,
        progression = progression,
        position = position,
        totalProgression = totalProgression,
        fragment = fragments?.firstOrNull(),
        sentenceDurationMs = 0L, // Not used for highlighting, only for audio locator emissions
    )
}
