package com.retro99.reader.ui.reader.saved

import com.retro99.reader.ui.navigator.PageMark
import com.retro99.reader.ui.navigator.PageMarkOptions
import com.retro99.saved.domain.model.HighlightColor
import com.retro99.saved.domain.model.SavedItem
import com.retro99.saved.domain.model.SavedItemType

/**
 * Colours the page marks are drawn in, taken from the theme of the page itself rather than
 * the app's chrome: a sepia page and a night page need different tones.
 */
data class SavedMarkStyle(
    /** Fill behind the text per colour; 0 where the theme draws no fill (e-ink). */
    val fills: Map<HighlightColor, Int> = emptyMap(),
    /** Rule under a highlighted range: the colour's darker tone on a light page, its lighter
     *  tone on a dark one, black on e-ink. */
    val rules: Map<HighlightColor, Int> = emptyMap(),
    /** No colours and no fills: highlights become rules instead. */
    val eink: Boolean = false,
    /** Whether the page under the fills is dark; see [PageMark.darkPage]. */
    val darkPage: Boolean = false,
    /** Accent colour of the bar at the edge of the page; black on e-ink. */
    val barColor: Int = 0,
    /** Page margins in dp, one per side: the bar is dropped on a side with no room for it. */
    val marginLeftDp: Int = 0,
    val marginRightDp: Int = 0,
    /** Scrolled pages have no columns. */
    val scroll: Boolean = false,
) {
    fun options(): PageMarkOptions = PageMarkOptions(
        marginLeft = marginLeftDp,
        marginRight = marginRightDp,
        scroll = scroll,
    )
}

/**
 * Decides which marks the page draws for a book's saved items.
 *
 * Only highlights are marked in the text. A bookmark with a note gets nothing there: the
 * ribbon at the top of the page is its entry point. The marks themselves are drawn page
 * side by [com.retro99.reader.ui.navigator.SavedPageScript.page], which also keeps the
 * geometry — this only says what exists and in which colour.
 */
internal object SavedMarks {

    /**
     * One [PageMark] per item that has text. Items without marks still appear, so the page
     * can say which of them start on the page it is showing.
     */
    fun marks(items: List<SavedItem>, style: SavedMarkStyle): List<PageMark> = items.mapNotNull { item ->
        if (item.type == SavedItemType.Word) return@mapNotNull null
        val anchor = item.anchor ?: return@mapNotNull null
        val isHighlight = item.type == SavedItemType.Highlight
        val color = item.color ?: HighlightColor.Default
        val hasNote = item.hasNote
        PageMark(
            id = item.id,
            href = item.location.href,
            mediaType = item.location.mediaType,
            quote = anchor.quote,
            before = anchor.before,
            after = anchor.after,
            progression = item.location.progression,
            fill = if (isHighlight) style.fills[color] ?: 0 else 0,
            darkPage = style.darkPage,
            tappable = isHighlight,
            ruleColor = if (isHighlight) style.rules[color] ?: 0 else 0,
            ruleCount = rules(style, isHighlight, hasNote),
            barColor = if (isHighlight && hasNote) style.barColor else 0,
        )
    }

    /**
     * A plain highlight is filled and carries no rule, except on e-ink where the rule is all
     * there is. A highlight with a note adds a rule in the colour's tone; on e-ink the pair
     * of rules is what says "note", so it gets two.
     */
    internal fun rules(style: SavedMarkStyle, isHighlight: Boolean, hasNote: Boolean): Int = when {
        !isHighlight -> 0
        style.eink -> if (hasNote) 2 else 1
        hasNote -> 1
        else -> 0
    }
}
