package com.retro99.reader.ui.reader.saved

import com.retro99.reader.ui.navigator.PageText
import com.retro99.saved.domain.model.HighlightColor
import com.retro99.saved.domain.model.SavedFilter
import com.retro99.saved.domain.model.SavedItem
import com.retro99.saved.domain.model.SavedItemType
import com.retro99.saved.domain.model.SavedSyncState

/** Bookmarks, highlights and notes of the open book, and the reader's saved-item UI. */
data class ReaderSavedState(
    /** This book's items (linked copies included), in book order. */
    val items: List<SavedItem> = emptyList(),
    /** Items whose text starts on the current page. */
    val onPageIds: Set<String> = emptySet(),
    /** The reader's text selection, while our toolbar shows. */
    val selection: ReaderTextSelection? = null,
    val detailId: String? = null,
    val noteEditorId: String? = null,
    val bar: SavedBar? = null,
    /** Remembered for the session. */
    val filter: SavedFilter = SavedFilter.All,
    val isEditing: Boolean = false,
    val isExportVisible: Boolean = false,
    val syncState: SavedSyncState = SavedSyncState.Unknown,
    /** The colour "Note" highlights in: the one used last. */
    val lastColor: HighlightColor = HighlightColor.Default,
    /** Something the UI must do once, like copying text. */
    val effect: SavedEffect? = null,
    /** Bookmark ticks on the progress strip: whole-book fractions. */
    val bookmarkTicks: List<Double> = emptyList(),
) {
    /** The bookmark on this page: fills the toolbar icon and shows the ribbon. */
    val pageBookmark: SavedItem?
        get() = items.firstOrNull { item -> item.type == SavedItemType.Bookmark && item.id in onPageIds }

    val detail: SavedItem? get() = detailId?.let { id -> items.firstOrNull { item -> item.id == id } }

    val noteEditorItem: SavedItem? get() = noteEditorId?.let { id -> items.firstOrNull { item -> item.id == id } }
}

/** A selection on the page: its text and where it is, for placing the toolbar. */
data class ReaderTextSelection(
    val href: String,
    val mediaType: String?,
    val text: PageText,
)

/** The bar above the progress strip. */
sealed interface SavedBar {
    val serial: Long

    /** "Bookmarked · Add note · Undo". */
    data class BookmarkAdded(val itemId: String, override val serial: Long) : SavedBar

    /** "Bookmark removed · Undo" or "Highlight removed · Undo". */
    data class Removed(val items: List<SavedItem>, override val serial: Long) : SavedBar

    /** A highlight merged others into it; Undo puts them back. */
    data class Highlighted(
        val itemId: String,
        val before: SavedItem?,
        val absorbed: List<SavedItem>,
        override val serial: Long,
    ) : SavedBar

    /** Highlights longer than Parrot Cloud allows are refused. */
    data class TooLong(override val serial: Long) : SavedBar
}

sealed interface SavedEffect {
    val serial: Long

    data class Copy(val text: String, override val serial: Long) : SavedEffect
}

/** Everything the reader can do with saved items. */
sealed interface SavedAction {
    data object ToggleBookmark : SavedAction

    /** From the listening panel: always adds a bookmark at the sentence being read. */
    data object BookmarkSentence : SavedAction
    data object BarUndo : SavedAction
    data object BarAddNote : SavedAction
    data object BarDismiss : SavedAction

    data class Highlight(val color: HighlightColor) : SavedAction
    data object NoteSelection : SavedAction
    data object CopySelection : SavedAction
    data object SearchSelection : SavedAction
    data object ShareSelection : SavedAction
    data object DismissSelection : SavedAction

    data class OpenDetail(val id: String) : SavedAction
    data object CloseDetail : SavedAction
    data class SetColor(val id: String, val color: HighlightColor) : SavedAction
    data class EditNote(val id: String) : SavedAction
    data class SaveNote(val id: String, val text: String) : SavedAction
    data object CloseNoteEditor : SavedAction
    data class Remove(val id: String) : SavedAction
    data class Copy(val id: String) : SavedAction
    data class Share(val id: String) : SavedAction

    data class SetFilter(val filter: SavedFilter) : SavedAction
    data object ToggleEditing : SavedAction
    data class GoTo(val id: String) : SavedAction
    data object OpenExport : SavedAction
    data object CloseExport : SavedAction
    data class Export(val format: SavedExportFormat, val labels: com.retro99.saved.domain.SavedItemsExport.Labels) : SavedAction

    data class EffectHandled(val serial: Long) : SavedAction

    /** Colours and words the page decorations need, from the theme. */
    data class UpdateDecorationStyle(
        val tints: Map<HighlightColor, Int>,
        val eink: Boolean,
        val noteLabel: String,
    ) : SavedAction
}

enum class SavedExportFormat { ShareText, CopyAll, Markdown }
