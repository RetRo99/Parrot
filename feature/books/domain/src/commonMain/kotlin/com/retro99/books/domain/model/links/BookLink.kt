package com.retro99.books.domain.model.links

/** Copies of one book on different sources. A copy belongs to at most one link. */
data class BookLink(val linkId: String, val members: Set<CopyKey>)
