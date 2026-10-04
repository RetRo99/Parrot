package com.retro99.saved.ui.library

import androidx.lifecycle.viewModelScope
import com.retro99.base.ui.BaseIntent
import com.retro99.base.ui.BaseViewModel
import com.retro99.base.ui.sharing.FileSharer
import com.retro99.books.domain.model.BookType
import com.retro99.reader.domain.usecase.ResolveSavedBookOpenUseCase
import com.retro99.reader.domain.usecase.SavedBookInfo
import com.retro99.reader.domain.usecase.SavedBookOpenTarget
import com.retro99.saved.domain.PendingSavedJump
import com.retro99.saved.domain.SavedItemsExport
import com.retro99.saved.domain.model.SavedCounts
import com.retro99.saved.domain.model.SavedFilter
import com.retro99.saved.domain.model.SavedItem
import com.retro99.saved.domain.model.SavedSyncState
import com.retro99.saved.domain.model.HighlightColor
import com.retro99.saved.domain.usecase.DeleteSavedItemUseCase
import com.retro99.saved.domain.usecase.ObserveAllSavedItemsUseCase
import com.retro99.saved.domain.usecase.ObserveSavedSyncStateUseCase
import com.retro99.saved.domain.usecase.RestoreSavedItemUseCase
import com.retro99.saved.domain.usecase.SaveSavedItemsUseCase
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import org.koin.core.annotation.KoinViewModel
import org.koin.core.annotation.InjectedParam
import org.koin.core.annotation.Provided

/** One book on the "By book" list. */
data class SavedBookSummary(
    val key: String,
    val title: String,
    val coverUrl: String?,
    val counts: SavedCounts,
)

data class NotesHighlightsViewState(
    /** Every item, most recently changed first. */
    val items: List<SavedItem> = emptyList(),
    val books: Map<String, SavedBookInfo> = emptyMap(),
    val query: String = "",
    val filter: SavedFilter = SavedFilter.All,
    /** A book's own list, full screen. */
    val bookKey: String? = null,
    val isEditing: Boolean = false,
    val syncState: SavedSyncState = SavedSyncState.Unknown,
    val downloadPrompt: DownloadPrompt? = null,
    val showUnavailable: Boolean = false,
    val removed: SavedItem? = null,
    val isExportVisible: Boolean = false,
    val copyText: String? = null,
    val detailId: String? = null,
    val noteEditorId: String? = null,
) {
    val detail: SavedItem? get() = items.firstOrNull { it.id == detailId }
    val noteEditor: SavedItem? get() = items.firstOrNull { it.id == noteEditorId }
    private fun matches(item: SavedItem): Boolean {
        if (!filter.matches(item)) return false
        val needle = query.trim()
        if (needle.isEmpty()) return true
        return item.text?.contains(needle, ignoreCase = true) == true ||
            item.note?.contains(needle, ignoreCase = true) == true ||
            item.location.chapterTitle?.contains(needle, ignoreCase = true) == true
    }

    /** "Latest", or every match while searching. */
    val latest: List<SavedItem>
        get() = items.filter(::matches).let { matching -> if (query.isBlank()) matching.take(LATEST_COUNT) else matching }

    val byBook: List<SavedBookSummary>
        get() = items.filter(::matches).groupBy { item -> item.book.key }.map { (key, bookItems) ->
            val info = books[key]
            SavedBookSummary(
                key = key,
                title = info?.title ?: bookItems.firstNotNullOfOrNull { item -> item.book.title }.orEmpty(),
                coverUrl = info?.coverUrl,
                counts = SavedCounts.of(bookItems),
            )
        }

    /** The open book's items, in book order. */
    val bookItems: List<SavedItem>
        get() = bookKey?.let { key ->
            items.filter { item -> item.book.key == key }
                .sortedWith(
                    compareBy<SavedItem> { item -> item.location.totalProgression ?: Double.MAX_VALUE }
                        .thenBy { item -> item.location.href }
                        .thenBy { item -> item.location.progression ?: 0.0 }
                        .thenBy { item -> item.createdAt },
                )
        }.orEmpty()

    val bookTitle: String
        get() = bookKey?.let { key -> books[key]?.title ?: items.firstOrNull { it.book.key == key }?.book?.title }.orEmpty()

    private companion object {
        const val LATEST_COUNT = 20
    }
}

data class DownloadPrompt(val serverId: String, val bookUuid: String, val title: String)

sealed interface NotesHighlightsIntent : BaseIntent {
    data class Search(val query: String) : NotesHighlightsIntent
    data class SetFilter(val filter: SavedFilter) : NotesHighlightsIntent
    data class OpenBook(val key: String) : NotesHighlightsIntent
    data object Back : NotesHighlightsIntent
    data class OpenItem(val item: SavedItem) : NotesHighlightsIntent
    data object ConfirmDownload : NotesHighlightsIntent
    data object DismissDownload : NotesHighlightsIntent
    data object DismissUnavailable : NotesHighlightsIntent
    data object ToggleEditing : NotesHighlightsIntent
    data class Delete(val item: SavedItem) : NotesHighlightsIntent
    data object UndoDelete : NotesHighlightsIntent
    data object DismissRemoved : NotesHighlightsIntent
    data object OpenExport : NotesHighlightsIntent
    data object CloseExport : NotesHighlightsIntent
    data class Export(val format: ExportFormat, val labels: SavedItemsExport.Labels) : NotesHighlightsIntent
    data object CopyHandled : NotesHighlightsIntent
    data class ShowDetail(val id: String) : NotesHighlightsIntent
    data object CloseDetail : NotesHighlightsIntent
    data class SetColor(val id: String, val color: HighlightColor) : NotesHighlightsIntent
    data class EditNote(val id: String) : NotesHighlightsIntent
    data object CloseNote : NotesHighlightsIntent
    data class SaveNote(val id: String, val text: String) : NotesHighlightsIntent
    data class CopyItem(val item: SavedItem) : NotesHighlightsIntent
    data class ShareItem(val item: SavedItem) : NotesHighlightsIntent
}

enum class ExportFormat { ShareText, CopyAll, Markdown }

/**
 * "Notes & highlights": every saved item across books, from sync, including books this
 * device hasn't downloaded. Opening one goes to the reader at that place.
 */
@KoinViewModel
class NotesHighlightsViewModel(
    @InjectedParam private val initialBookKey: String?,
    @InjectedParam private val onBack: () -> Unit,
    @InjectedParam private val onOpenReader: (serverId: String, bookUuid: String, bookType: BookType) -> Unit,
    @InjectedParam private val onOpenBookDetail: (serverId: String, bookUuid: String) -> Unit,
    @Provided private val observeAllSavedItems: ObserveAllSavedItemsUseCase,
    @Provided private val observeSyncState: ObserveSavedSyncStateUseCase,
    @Provided private val deleteSavedItem: DeleteSavedItemUseCase,
    @Provided private val restoreSavedItem: RestoreSavedItemUseCase,
    @Provided private val resolveBookOpen: ResolveSavedBookOpenUseCase,
    @Provided private val pendingJump: PendingSavedJump,
    @Provided private val fileSharer: FileSharer,
    @Provided private val saveSavedItems: SaveSavedItemsUseCase,
) : BaseViewModel<NotesHighlightsViewState, NotesHighlightsIntent>(
    NotesHighlightsViewState(bookKey = initialBookKey),
) {
    private var pendingItemId: String? = null

    init {
        observeAllSavedItems().onEach { items -> updateState { it.copy(items = items) } }.launchIn(viewModelScope)
        observeSyncState().onEach { sync -> updateState { it.copy(syncState = sync) } }.launchIn(viewModelScope)
        resolveBookOpen.observeBookInfo().onEach { books -> updateState { it.copy(books = books) } }.launchIn(viewModelScope)
    }

    override fun onIntent(intent: NotesHighlightsIntent) {
        when (intent) {
            is NotesHighlightsIntent.Search -> updateState { it.copy(query = intent.query) }
            is NotesHighlightsIntent.SetFilter -> updateState { it.copy(filter = intent.filter) }
            is NotesHighlightsIntent.OpenBook -> updateState { it.copy(bookKey = intent.key, isEditing = false) }
            NotesHighlightsIntent.Back -> back()
            is NotesHighlightsIntent.OpenItem -> open(intent.item)
            NotesHighlightsIntent.ConfirmDownload -> {
                val prompt = viewState.value.downloadPrompt ?: return
                updateState { it.copy(downloadPrompt = null) }
                pendingItemId?.let { id -> pendingJump.set(prompt.bookUuid, id) }
                onOpenBookDetail(prompt.serverId, prompt.bookUuid)
            }
            NotesHighlightsIntent.DismissDownload -> updateState { it.copy(downloadPrompt = null) }
            NotesHighlightsIntent.DismissUnavailable -> updateState { it.copy(showUnavailable = false) }
            NotesHighlightsIntent.ToggleEditing -> updateState { it.copy(isEditing = !it.isEditing) }
            is NotesHighlightsIntent.Delete -> viewModelScope.launch {
                updateState { it.copy(detailId = null, noteEditorId = null) }
                deleteSavedItem(intent.item.id)?.let { removed -> updateState { it.copy(removed = removed) } }
            }
            NotesHighlightsIntent.UndoDelete -> {
                val removed = viewState.value.removed ?: return
                updateState { it.copy(removed = null) }
                viewModelScope.launch { restoreSavedItem(removed) }
            }
            NotesHighlightsIntent.DismissRemoved -> updateState { it.copy(removed = null) }
            NotesHighlightsIntent.OpenExport -> updateState { it.copy(isExportVisible = true) }
            NotesHighlightsIntent.CloseExport -> updateState { it.copy(isExportVisible = false) }
            is NotesHighlightsIntent.Export -> export(intent.format, intent.labels)
            NotesHighlightsIntent.CopyHandled -> updateState { it.copy(copyText = null) }
            is NotesHighlightsIntent.ShowDetail -> updateState { it.copy(detailId = intent.id) }
            NotesHighlightsIntent.CloseDetail -> updateState { it.copy(detailId = null) }
            is NotesHighlightsIntent.SetColor -> edit(intent.id) { it.copy(color = intent.color) }
            is NotesHighlightsIntent.EditNote -> updateState { it.copy(detailId = null, noteEditorId = intent.id) }
            NotesHighlightsIntent.CloseNote -> updateState { it.copy(noteEditorId = null) }
            is NotesHighlightsIntent.SaveNote -> {
                edit(intent.id) { it.copy(note = intent.text.trim().ifEmpty { null }) }
                updateState { it.copy(noteEditorId = null) }
            }
            is NotesHighlightsIntent.CopyItem -> updateState { it.copy(copyText = itemText(intent.item)) }
            is NotesHighlightsIntent.ShareItem -> fileSharer.shareText(itemText(intent.item), intent.item.book.title.orEmpty())
        }
    }

    private fun edit(id: String, transform: (SavedItem) -> SavedItem) {
        val item = viewState.value.items.firstOrNull { it.id == id } ?: return
        viewModelScope.launch { saveSavedItems(transform(item)) }
    }

    private fun itemText(item: SavedItem): String =
        listOfNotNull(item.text, item.note?.takeIf { it.isNotBlank() }).joinToString("\n\n")

    private fun back() {
        val state = viewState.value
        if (state.bookKey != null && initialBookKey == null) {
            updateState { it.copy(bookKey = null, isEditing = false) }
        } else {
            onBack()
        }
    }

    private fun open(item: SavedItem) {
        viewModelScope.launch {
            when (val target = resolveBookOpen(item.book.key)) {
                is SavedBookOpenTarget.Open -> {
                    pendingJump.set(target.bookUuid, item.id)
                    onOpenReader(target.serverId, target.bookUuid, target.bookType)
                }
                is SavedBookOpenTarget.Download -> {
                    pendingItemId = item.id
                    updateState { it.copy(downloadPrompt = DownloadPrompt(target.serverId, target.bookUuid, target.title)) }
                }
                SavedBookOpenTarget.Unavailable -> updateState { it.copy(showUnavailable = true) }
            }
        }
    }

    private fun export(format: ExportFormat, labels: SavedItemsExport.Labels) {
        val state = viewState.value
        val title = state.bookTitle
        val author = state.bookItems.firstNotNullOfOrNull { item -> item.book.author }
        val items = state.bookItems
        updateState { it.copy(isExportVisible = false) }
        runCatching {
            when (format) {
                ExportFormat.CopyAll -> updateState {
                    it.copy(copyText = SavedItemsExport.plainText(title, author, items, labels))
                }
                ExportFormat.ShareText -> fileSharer.shareText(SavedItemsExport.plainText(title, author, items, labels), title)
                ExportFormat.Markdown -> fileSharer.shareTextAsFile(
                    fileName = "${title.lowercase().replace(Regex("[^a-z0-9]+"), "-").trim('-').ifEmpty { "notes" }}.md",
                    text = SavedItemsExport.markdown(title, author, items, labels),
                    mimeType = "text/markdown",
                    title = title,
                )
            }
        }
    }
}
