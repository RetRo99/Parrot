package com.retro99.catalogue.domain

/**
 * What a catalogue's description is allowed to be on screen (CATALOGUE_PROMPT §1): paragraphs,
 * line breaks, bold, italic and lists. There is no place in it for an address, an image or
 * markup, so nothing a catalogue writes can become more than characters.
 */
data class CatalogueRichText(val blocks: List<Block>) {
    sealed interface Block

    data class Paragraph(val spans: List<Span>) : Block

    /** One list with its nested lists flattened into it; [ListItem.level] says how deep an item was. */
    data class ListBlock(val items: List<ListItem>) : Block

    data class ListItem(val spans: List<Span>, val level: Int, val marker: Marker)

    sealed interface Marker {
        data object Bullet : Marker
        data class Number(val value: Int) : Marker
    }

    /** A run of one style. A line break is a '\n' in [text]. */
    data class Span(val text: String, val bold: Boolean = false, val italic: Boolean = false)

    companion object {
        /** Longer descriptions are cut here before anything is read. */
        const val MAX_INPUT_CHARS = 100_000

        /** Lists nested deeper than this are shown at this indent. */
        const val MAX_LIST_LEVEL = 3

        val Empty = CatalogueRichText(emptyList())
    }
}

/** How the catalogue labelled the description. [Text] is never read as markup. */
enum class CatalogueDescriptionFormat { Text, Html, Xhtml }
