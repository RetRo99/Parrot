package com.retro99.opds.api

import com.retro99.opds.api.model.OpdsRejection

/**
 * What a fetched body is, from parsed content type plus (for generic XML or
 * JSON types) a bounded structural probe (plan §4 "Document detection": never
 * accept arbitrary HTML error pages or arbitrary JSON as catalogues).
 */
sealed interface OpdsContentType {
    object FEED : OpdsContentType

    object PUBLICATION : OpdsContentType

    /** OpenSearch description document (OPDS1 search discovery). */
    object OPEN_SEARCH_DESCRIPTION : OpdsContentType

    /** Well-parsed but not a catalogue (HTML page, RSS, browser-garbage). */
    object NotACatalogue : OpdsContentType

    /** Safety/structure rejections stop here before any deeper parsing. */
    data class Rejected(val rejection: OpdsRejection) : OpdsContentType
}
