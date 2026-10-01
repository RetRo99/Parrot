package com.retro99.reader.ui.model

import com.retro99.server.api.TextAnchor

/**
 * Combined information about the current chapter including page position and word count.
 *
 * This class combines page info (which changes on every page turn) with word count
 * (which is static per chapter and cached). The page info is based on the actual
 * viewport display, unlike the EPUB position which uses fixed 1024-character blocks.
 *
 * @param currentPage The current page number within the chapter (1-based)
 * @param totalPages The total number of pages in the current chapter
 * @param totalWords The total number of words in the chapter (null if not yet fetched)
 */
data class ChapterInfo(
    val currentPage: Int,
    val totalPages: Int,
    val totalWords: Int? = null,
)

/**
 * Common data class representing the current reading position/locator.
 *
 * This is a platform-agnostic representation of the reading position that can be
 * used in the common interface. Platform implementations convert their native
 * locator types to this common model.
 *
 * @param href The href of the current resource
 * @param type The media type of the resource
 * @param title The title of the current section, if available
 * @param progression The progression within the resource (0.0 to 1.0)
 * @param position The position index, if available
 * @param totalProgression The total progression through the publication (0.0 to 1.0)
 * @param fragments The list of fragment identifiers (e.g., sentence IDs)
 * @param chapterInfo Combined chapter info including page position and word count
 */
data class LocatorState(
    val href: String,
    val type: String,
    val title: String?,
    val progression: Double?,
    val position: Int?,
    val totalProgression: Double?,
    val fragments: List<String>?,
    val chapterInfo: ChapterInfo? = null,
    val cssSelector: String? = null,
    /** Text around the start of the visible page, for matching this place in other copies. */
    val textAnchor: TextAnchor? = null,
)

/**
 * Extended locator state for audio playback that includes timing information.
 * Used for coordinating text highlighting with audio playback, including
 * handling sentences that are split across pages.
 *
 * @param locator The base locator state with position information
 * @param sentenceDurationMs The duration of the current sentence in milliseconds.
 *                           Used to calculate when to turn the page for split sentences.
 */
data class AudioLocatorState(
    val locator: LocatorState,
    val sentenceDurationMs: Long,
)

