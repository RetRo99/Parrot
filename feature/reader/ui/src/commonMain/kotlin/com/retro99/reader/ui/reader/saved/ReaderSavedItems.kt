package com.retro99.reader.ui.reader.saved

import com.retro99.base.ui.sharing.FileSharer
import com.retro99.reader.domain.usecase.ResolveSavedBookUseCase
import com.retro99.reader.domain.usecase.SavedBookIdentity
import com.retro99.reader.ui.model.PositionUiModel
import com.retro99.reader.ui.navigator.BookController
import com.retro99.reader.ui.navigator.PageAnchor
import com.retro99.reader.ui.navigator.PageMark
import com.retro99.reader.ui.navigator.PageText
import com.retro99.reader.ui.navigator.SavedPageScript
import com.retro99.reader.ui.reader.ReaderSearchResult
import com.retro99.saved.domain.HighlightMerger
import com.retro99.saved.domain.PendingSavedJump
import com.retro99.saved.domain.SavedItemsExport
import com.retro99.saved.domain.model.HighlightColor
import com.retro99.saved.domain.model.SavedAudioPosition
import com.retro99.saved.domain.model.SavedBookRef
import com.retro99.saved.domain.model.SavedItem
import com.retro99.saved.domain.model.SavedItemLimits
import com.retro99.saved.domain.model.SavedItemType
import com.retro99.saved.domain.model.SavedLocation
import com.retro99.saved.domain.model.TextAnchor
import com.retro99.saved.domain.usecase.DeleteSavedItemUseCase
import com.retro99.saved.domain.usecase.ObserveBookSavedItemsUseCase
import com.retro99.saved.domain.usecase.ObserveSavedSyncStateUseCase
import com.retro99.saved.domain.usecase.RestoreSavedItemUseCase
import com.retro99.saved.domain.usecase.SaveSavedItemsUseCase
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import kotlin.time.Clock
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

/** What the reader knows that saved items need, read fresh each time. */
internal data class ReaderSavedContext(
    val position: PositionUiModel?,
    val bookTitle: String,
    val bookAuthor: String?,
    val isListening: Boolean,
    /** The table-of-contents title for a resource, when there is one. */
    val chapterTitleFor: (href: String) -> String?,
)

/** The sentence being read aloud, for a bookmark made while listening. */
internal data class ListeningSentence(
    val href: String,
    val mediaType: String?,
    /** Read-aloud sentence element, when the book has one. */
    val elementId: String?,
    /** The sentence's text, for device-voice reading. */
    val text: String?,
    /** Narration time; device voices have none. */
    val audioMs: Long?,
)

/**
 * Bookmarks, highlights and notes inside the reader. The ViewModel owns this and
 * forwards [SavedAction]s; state lives in [ReaderSavedState] inside the reader's state.
 * Page work goes through [SavedPageScript], so Android and iOS behave the same.
 */
@OptIn(ExperimentalUuidApi::class)
internal class ReaderSavedItems(
    private val scope: CoroutineScope,
    private val bookController: () -> BookController,
    private val state: () -> ReaderSavedState,
    private val update: ((ReaderSavedState) -> ReaderSavedState) -> Unit,
    private val context: () -> ReaderSavedContext,
    private val listeningSentence: suspend () -> ListeningSentence?,
    /** Jumps to a place, keeping audio going and offering "Back to where I was". */
    private val navigate: (ReaderSearchResult) -> Unit,
    /** Opens in-book search for a query. */
    private val openSearch: (String) -> Unit,
    private val observeBookItems: ObserveBookSavedItemsUseCase,
    private val saveItems: SaveSavedItemsUseCase,
    private val deleteItem: DeleteSavedItemUseCase,
    private val restoreItem: RestoreSavedItemUseCase,
    private val observeSyncState: ObserveSavedSyncStateUseCase,
    private val resolveBook: ResolveSavedBookUseCase,
    private val fileSharer: FileSharer,
    private val pendingJump: PendingSavedJump,
    private val onError: (Throwable, String) -> Unit,
) {
    private var identity: SavedBookIdentity? = null
    private var serverId = ""
    private var bookUuid = ""
    private var decorationStyle: SavedMarkStyle? = null
    private var pageJob: Job? = null
    private var selectionPollJob: Job? = null
    private var barJob: Job? = null
    private var serial = 0L
    private var barPageKey: String? = null
    private var chapterStarts: List<Double>? = null
    private val resolvingSnippets = mutableSetOf<String>()

    fun start(serverId: String, bookUuid: String) {
        this.serverId = serverId
        this.bookUuid = bookUuid
        scope.launch {
            val book = identity()
            var jumpId = pendingJump.take(bookUuid)
            observeBookItems(book.keys, book.uuids).collect { items ->
                update { saved ->
                    saved.copy(
                        items = items,
                        bookmarkTicks = items.filter { item -> item.type == SavedItemType.Bookmark }
                            .mapNotNull { item -> item.location.totalProgression },
                    )
                }
                refreshDecorations()
                refreshPage()
                // Opened from Notes & highlights: go to the item once it has loaded.
                jumpId?.let { id -> items.firstOrNull { item -> item.id == id } }?.let { item ->
                    jumpId = null
                    goTo(item)
                }
            }
        }
        scope.launch { observeSyncState().collect { sync -> update { it.copy(syncState = sync) } } }
        scope.launch {
            bookController().selectionChanges.collectLatest { present ->
                if (present) {
                    // Handles report every move; read the selection once they settle.
                    delay(SELECTION_SETTLE_MS)
                    readSelection()
                } else {
                    update { it.copy(selection = null) }
                }
            }
        }
        scope.launch { bookController().savedDecorationTaps.collect { id -> openDetail(id) } }
        scope.launch { bookController().pageReloads.collect { refreshDecorations() } }
    }

    fun handle(action: SavedAction) {
        when (action) {
            SavedAction.ToggleBookmark -> toggleBookmark()
            SavedAction.BookmarkSentence -> addBookmark()
            SavedAction.BarUndo -> undoBar()
            SavedAction.BarAddNote -> (state().bar as? SavedBar.BookmarkAdded)?.let { bar ->
                update { it.copy(bar = null, noteEditorId = bar.itemId) }
            }
            SavedAction.BarDismiss -> update { it.copy(bar = null) }

            is SavedAction.Highlight -> scope.launch { highlightSelection(action.color) }
            SavedAction.NoteSelection -> scope.launch {
                val id = highlightSelection(state().lastColor)
                if (id != null) update { it.copy(noteEditorId = id) }
            }
            SavedAction.CopySelection -> withSelection { text -> copy(text.quote.oneLine()) }
            SavedAction.ShareSelection -> withSelection { text -> share(text.quote.oneLine()) }
            SavedAction.SearchSelection -> withSelection { text -> openSearch(text.quote.oneLine().take(SEARCH_MAX_CHARS)) }
            SavedAction.DismissSelection -> clearSelection()

            is SavedAction.OpenDetail -> openDetail(action.id)
            SavedAction.CloseDetail -> update { it.copy(detailId = null) }
            is SavedAction.SetColor -> edit(action.id) { item -> item.copy(color = action.color) }
                .also { update { it.copy(lastColor = action.color) } }
            is SavedAction.EditNote -> update { it.copy(detailId = null, noteEditorId = action.id) }
            is SavedAction.SaveNote -> {
                edit(action.id) { item ->
                    item.copy(note = action.text.trim().take(SavedItemLimits.MAX_NOTE_CHARS).takeIf { it.isNotEmpty() })
                }
                update { it.copy(noteEditorId = null) }
            }
            SavedAction.CloseNoteEditor -> update { it.copy(noteEditorId = null) }
            is SavedAction.Remove -> remove(action.id)
            is SavedAction.Copy -> item(action.id)?.let { item -> copy(item.shareText()) }
            is SavedAction.Share -> item(action.id)?.let { item -> share(item.shareText()) }

            is SavedAction.SetFilter -> update { it.copy(filter = action.filter) }
            SavedAction.ToggleEditing -> update { it.copy(isEditing = !it.isEditing) }
            is SavedAction.GoTo -> item(action.id)?.let(::goTo)
            SavedAction.OpenExport -> update { it.copy(isExportVisible = true) }
            SavedAction.CloseExport -> update { it.copy(isExportVisible = false) }
            is SavedAction.Export -> export(action.format, action.labels)

            is SavedAction.EffectHandled -> update { saved ->
                if (saved.effect?.serial == action.serial) saved.copy(effect = null) else saved
            }
            is SavedAction.UpdateDecorationStyle -> if (action.style != decorationStyle) {
                decorationStyle = action.style
                refreshDecorations()
                scope.launch { applySelectionStyle() }
            }
        }
    }

    /** Called on every page change: the ribbon, migrated bookmarks and the e-ink bar. */
    fun onPageChanged(position: PositionUiModel) {
        val key = position.pageKey()
        val bar = state().bar
        if (bar != null && decorationStyle?.eink == true && barPageKey != null && barPageKey != key) {
            // E-ink keeps the bar still until the next page turn.
            update { it.copy(bar = null) }
            barPageKey = null
        }
        if (state().selection != null) update { it.copy(selection = null) }
        pageJob?.cancel()
        pageJob = scope.launch {
            delay(PAGE_SETTLE_MS)
            applySelectionStyle()
            refreshPage()
        }
    }

    // ==================== Bookmarks ====================

    private fun toggleBookmark() {
        val existing = state().pageBookmark
        if (existing != null) {
            remove(existing.id)
            return
        }
        addBookmark()
    }

    private fun addBookmark() {
        scope.launch {
            try {
                val bookmark = newBookmark() ?: return@launch
                saveItems(bookmark)
                update { it.copy(onPageIds = it.onPageIds + bookmark.id) }
                showBar(SavedBar.BookmarkAdded(bookmark.id, nextSerial()))
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                onError(error, "ReaderSavedItems: failed to save bookmark")
            }
        }
    }

    private suspend fun newBookmark(): SavedItem? {
        val ctx = context()
        val position = ctx.position ?: return null
        if (ctx.isListening) {
            listeningSentence()?.let { listening -> return listeningBookmark(listening, position) }
        }
        val sentence = SavedPageScript.parseAnchor(bookController().runPageScript(SavedPageScript.firstSentence()))
        return newItem(
            type = SavedItemType.Bookmark,
            href = position.href,
            mediaType = position.type,
            text = sentence,
            fallbackPosition = position,
        )
    }

    private suspend fun listeningBookmark(listening: ListeningSentence, position: PositionUiModel): SavedItem {
        val controller = bookController()
        val onPage = listening.href.sameResource(position.href)
        val sentence = when {
            !onPage -> null
            listening.elementId != null ->
                SavedPageScript.parseAnchor(controller.runPageScript(SavedPageScript.elementSentence(listening.elementId)))
            listening.text != null ->
                SavedPageScript.parseAnchor(controller.runPageScript(SavedPageScript.sentenceFor(PageAnchor(quote = listening.text))))
            else -> null
        } ?: listening.text?.let { text -> PageText(quote = text.take(SavedItemLimits.MAX_QUOTE_CHARS)) }
        return newItem(
            type = SavedItemType.Bookmark,
            href = listening.href,
            mediaType = listening.mediaType ?: position.type,
            text = sentence,
            fallbackPosition = position.takeIf { onPage },
        ).copy(audio = listening.audioMs?.let { ms -> SavedAudioPosition(href = listening.href, offsetMs = ms) })
    }

    // ==================== Selection and highlights ====================

    private suspend fun readSelection() {
        val position = context().position ?: return
        val text = bookController().selectionForToolbar()
        update { it.copy(selection = text?.let { selection -> ReaderTextSelection(position.href, position.type, selection) }) }
        if (text != null) watchSelection()
    }

    /** iOS doesn't say when a selection goes away; neither does a tap outside it on Android. */
    private fun watchSelection() {
        if (selectionPollJob?.isActive == true) return
        selectionPollJob = scope.launch {
            while (state().selection != null) {
                delay(SELECTION_POLL_MS)
                val text = bookController().selectionForToolbar()
                update { saved -> saved.copy(selection = saved.selection?.let { current ->
                    text?.let { current.copy(text = it) }
                }) }
            }
        }
    }

    private fun withSelection(block: (PageText) -> Unit) {
        val selection = state().selection ?: return
        block(selection.text)
        clearSelection()
    }

    private fun clearSelection() {
        update { it.copy(selection = null) }
        bookController().clearSelection()
        scope.launch { bookController().runPageScript(SavedPageScript.clearSelection()) }
    }

    /** Highlights the selection, joining any highlight it touches. Returns the highlight's id. */
    private suspend fun highlightSelection(color: HighlightColor): String? {
        val selection = state().selection ?: return null
        clearSelection()
        if (selection.text.tooLong) {
            showBar(SavedBar.TooLong(nextSerial()))
            return null
        }
        return try {
            val existing = state().items.filter { item ->
                item.type == SavedItemType.Highlight && item.anchor != null && item.location.href.sameResource(selection.href)
            }
            val merged = if (existing.isEmpty()) {
                null
            } else {
                SavedPageScript.parseAnchor(
                    bookController().runPageScript(
                        SavedPageScript.merge(selection.text.toPageAnchor(), existing.mapNotNull { item -> item.toPageAnchor() }),
                    ),
                )
            }
            val range = merged ?: selection.text
            if (range.tooLong) {
                showBar(SavedBar.TooLong(nextSerial()))
                return null
            }
            val fresh = newItem(SavedItemType.Highlight, selection.href, selection.mediaType, selection.text, null)
                .copy(color = color)
            val result = HighlightMerger.merge(
                fresh = fresh,
                overlapping = existing.filter { item -> item.id in range.ids },
                mergedAnchor = range.toTextAnchor(),
                mergedLocation = fresh.location.copy(
                    progression = range.progression,
                    totalProgression = totalProgression(selection.href, range.progression) ?: fresh.location.totalProgression,
                ),
                pickedColor = color,
                now = Clock.System.now(),
            )
            saveItems(result.survivor)
            result.absorbedIds.forEach { id -> deleteItem(id) }
            update { it.copy(lastColor = color) }
            result.survivor.id
        } catch (error: CancellationException) {
            throw error
        } catch (error: Throwable) {
            onError(error, "ReaderSavedItems: failed to save highlight")
            null
        }
    }

    // ==================== Details, notes, removal ====================

    private fun openDetail(id: String) {
        if (item(id) == null) return
        update { it.copy(detailId = id, selection = null) }
    }

    private fun edit(id: String, change: (SavedItem) -> SavedItem) {
        val current = item(id) ?: return
        scope.launch { saveItems(change(current)) }
    }

    private fun remove(id: String) {
        val removed = item(id) ?: return
        update { it.copy(detailId = null, onPageIds = it.onPageIds - id) }
        scope.launch {
            deleteItem(id)
            showBar(SavedBar.Removed(listOf(removed), nextSerial()))
        }
    }

    private fun undoBar() {
        val bar = state().bar ?: return
        update { it.copy(bar = null) }
        scope.launch {
            when (bar) {
                is SavedBar.BookmarkAdded -> deleteItem(bar.itemId)
                is SavedBar.Removed -> bar.items.forEach { item -> restoreItem(item) }
                is SavedBar.Highlighted -> {
                    bar.before?.let { item -> restoreItem(item) } ?: deleteItem(bar.itemId)
                    bar.absorbed.forEach { item -> restoreItem(item) }
                }
                is SavedBar.TooLong -> Unit
            }
        }
    }

    private fun showBar(bar: SavedBar) {
        barJob?.cancel()
        barPageKey = context().position?.pageKey()
        update { it.copy(bar = bar) }
        if (decorationStyle?.eink == true) return
        barJob = scope.launch {
            delay(BAR_VISIBLE_MS)
            update { saved -> if (saved.bar?.serial == bar.serial) saved.copy(bar = null) else saved }
        }
    }

    // ==================== Navigation and export ====================

    private fun goTo(item: SavedItem) {
        update { it.copy(isEditing = false) }
        navigate(item.toSearchResult())
    }

    private fun export(format: SavedExportFormat, labels: SavedItemsExport.Labels) {
        val ctx = context()
        val items = state().items
        update { it.copy(isExportVisible = false) }
        when (format) {
            SavedExportFormat.CopyAll -> copy(SavedItemsExport.plainText(ctx.bookTitle, ctx.bookAuthor, items, labels))
            SavedExportFormat.ShareText -> runShare {
                fileSharer.shareText(SavedItemsExport.plainText(ctx.bookTitle, ctx.bookAuthor, items, labels), ctx.bookTitle)
            }
            SavedExportFormat.Markdown -> runShare {
                fileSharer.shareTextAsFile(
                    fileName = "${ctx.bookTitle.fileSlug()}.md",
                    text = SavedItemsExport.markdown(ctx.bookTitle, ctx.bookAuthor, items, labels),
                    mimeType = "text/markdown",
                    title = ctx.bookTitle,
                )
            }
        }
    }

    private fun copy(text: String) {
        update { it.copy(effect = SavedEffect.Copy(text, nextSerial())) }
    }

    private fun share(text: String) = runShare { fileSharer.shareText(text, context().bookTitle) }

    private fun runShare(block: () -> Unit) {
        try {
            block()
        } catch (error: Throwable) {
            onError(error, "ReaderSavedItems: share failed")
        }
    }

    // ==================== Page state ====================

    private fun refreshDecorations() {
        val style = decorationStyle ?: return
        val marks = SavedMarks.marks(state().items, style)
        bookController().applySavedDecorations(marks)
        scope.launch { drawMarks(marks, style) }
    }

    /**
     * Draws the rules and the edge bars for [marks], and learns which of them start on the
     * page being shown.
     */
    private suspend fun drawMarks(marks: List<PageMark>, style: SavedMarkStyle) {
        if (marks.isEmpty()) return
        val raw = bookController().runPageScript(SavedPageScript.page(marks, style.options()))
        val ids = SavedPageScript.parseIds(raw).toSet()
        update { saved -> if (saved.onPageIds == ids) saved else saved.copy(onPageIds = ids) }
    }

    private suspend fun refreshPage() {
        val position = context().position ?: return
        val inChapter = state().items.filter { item -> item.location.href.sameResource(position.href) }
        resolveSnippets(inChapter.filter { item -> item.snippetPending && item.anchor == null })
        // A different chapter (or a reflow) needs the marks drawn again. Nothing is drawn
        // until the theme has said what colour they are: drawing earlier would paint them
        // transparent and wipe the ones already on the page.
        if (decorationStyle != null) refreshDecorations()
    }

    /** Bookmarks from before sentences were kept get theirs the first time their chapter is open. */
    private suspend fun resolveSnippets(pending: List<SavedItem>) {
        val ctx = context()
        pending.filter { item -> resolvingSnippets.add(item.id) }.forEach { item ->
            val sentence = SavedPageScript.parseAnchor(
                bookController().runPageScript(SavedPageScript.sentenceAt(item.location.progression ?: 0.0)),
            ) ?: return@forEach
            val tocTitle = ctx.chapterTitleFor(item.location.href)
            val oldTitle = item.location.chapterTitle?.trim()?.takeIf { title -> title.isNotEmpty() }
            // An old bookmark's title was the chapter, unless the reader renamed it: keep a
            // renamed title as the note so nothing they typed is lost.
            val renamed = oldTitle != null && tocTitle != null && oldTitle != tocTitle.trim()
            saveItems(
                item.copy(
                    anchor = sentence.toTextAnchor(),
                    snippetPending = false,
                    note = item.note ?: oldTitle.takeIf { renamed },
                    location = item.location.copy(
                        chapterTitle = tocTitle ?: item.location.chapterTitle,
                        progression = sentence.progression,
                    ),
                ),
            )
        }
    }

    private suspend fun applySelectionStyle() {
        val eink = decorationStyle?.eink ?: return
        bookController().runPageScript(SavedPageScript.setSelectionStyle(eink))
    }

    // ==================== Helpers ====================

    private suspend fun newItem(
        type: SavedItemType,
        href: String,
        mediaType: String?,
        text: PageText?,
        fallbackPosition: PositionUiModel?,
    ): SavedItem {
        val ctx = context()
        val book = identity()
        val now = Clock.System.now()
        val progression = text?.progression ?: fallbackPosition?.progression
        return SavedItem(
            id = Uuid.random().toString(),
            book = SavedBookRef(
                key = book.selfKey,
                uuid = book.selfUuid,
                title = ctx.bookTitle,
                author = ctx.bookAuthor,
            ),
            type = type,
            location = SavedLocation(
                href = href,
                mediaType = mediaType,
                progression = progression,
                totalProgression = totalProgression(href, progression) ?: fallbackPosition?.totalProgression,
                position = fallbackPosition?.position,
                chapterTitle = ctx.chapterTitleFor(href) ?: fallbackPosition?.title,
            ),
            anchor = text?.toTextAnchor(),
            color = if (type == SavedItemType.Highlight) HighlightColor.Default else null,
            note = null,
            audio = null,
            snippetPending = text == null,
            createdAt = now,
            updatedAt = now,
            remoteRevision = null,
        )
    }

    /** Whole-book fraction of a point in a chapter, from where each chapter starts. */
    private suspend fun totalProgression(href: String, progression: Double?): Double? {
        if (progression == null) return null
        val controller = bookController()
        val starts = chapterStarts ?: controller.chapterStartProgressions().also { chapterStarts = it }
        val index = controller.searchReadingOrder().indexOfFirst { candidate -> candidate.sameResource(href) }
        if (index < 0 || index >= starts.size) return null
        val start = starts[index]
        val end = starts.getOrNull(index + 1) ?: 1.0
        return (start + (end - start) * progression).coerceIn(0.0, 1.0)
    }

    private suspend fun identity(): SavedBookIdentity =
        identity ?: resolveBook(serverId, bookUuid).also { resolved -> identity = resolved }

    private fun item(id: String): SavedItem? = state().items.firstOrNull { item -> item.id == id }

    private fun nextSerial(): Long = ++serial

    private fun SavedItem.toPageAnchor(): PageAnchor? = anchor?.let { text ->
        PageAnchor(id = id, quote = text.quote, before = text.before, after = text.after, progression = location.progression)
    }

    private fun SavedItem.shareText(): String = buildString {
        append("“").append(text?.oneLine() ?: location.chapterTitle.orEmpty()).append("”")
        val title = context().bookTitle
        if (title.isNotBlank()) append(" — ").append(title)
        if (hasNote) append("\n\n").append(note!!.trim())
    }

    /** A Readium locator with the item's text, so the jump lands on the sentence itself. */
    private fun SavedItem.toSearchResult(): ReaderSearchResult {
        val json = buildJsonObject {
            put("href", location.href)
            put("type", location.mediaType ?: "application/xhtml+xml")
            location.chapterTitle?.let { title -> put("title", title) }
            putJsonObject("locations") {
                location.progression?.let { value -> put("progression", value) }
                location.totalProgression?.let { value -> put("totalProgression", value) }
                location.position?.let { value -> put("position", value) }
            }
            anchor?.let { text ->
                putJsonObject("text") {
                    text.before?.let { value -> put("before", value) }
                    put("highlight", text.quote)
                    text.after?.let { value -> put("after", value) }
                }
            }
        }
        return ReaderSearchResult(
            href = location.href,
            type = location.mediaType ?: "application/xhtml+xml",
            title = location.chapterTitle,
            progression = location.progression,
            position = location.position,
            totalProgression = location.totalProgression,
            before = anchor?.before,
            match = anchor?.quote,
            after = anchor?.after,
            index = -1,
            locatorJson = json.toString(),
        )
    }

    private companion object {
        const val SELECTION_SETTLE_MS = 150L
        const val SELECTION_POLL_MS = 80L
        const val PAGE_SETTLE_MS = 250L
        const val BAR_VISIBLE_MS = 5_000L
        const val SEARCH_MAX_CHARS = 120
    }
}

private fun PageText.toTextAnchor() = TextAnchor(before = before.ifEmpty { null }, quote = quote, after = after.ifEmpty { null })

private fun PageText.toPageAnchor() = PageAnchor(quote = quote, before = before, after = after, progression = progression)

private fun String.oneLine(): String = replace(Regex("\\s+"), " ").trim()

private fun PositionUiModel.pageKey(): String = "$href@${progression ?: 0.0}"

/** Whether two hrefs name the same resource, ignoring fragments and leading path noise. */
internal fun String.sameResource(other: String): Boolean {
    fun normalize(value: String) = value.substringBefore('#').removePrefix("./").trimStart('/')
    val a = normalize(this)
    val b = normalize(other)
    return a == b || a.endsWith("/$b") || b.endsWith("/$a")
}

private fun String.fileSlug(): String =
    lowercase().replace(Regex("[^a-z0-9]+"), "-").trim('-').take(60).ifEmpty { "notes" }
