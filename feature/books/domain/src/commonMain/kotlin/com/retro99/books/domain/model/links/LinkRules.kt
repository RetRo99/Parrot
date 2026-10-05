package com.retro99.books.domain.model.links

import com.retro99.books.domain.model.BookDomainModel
import com.retro99.server.api.ServerType
import kotlin.time.Instant

/** Order-independent key for a pair of copies. */
fun pairKey(first: CopyKey, second: CopyKey): String =
    listOf(first.value, second.value).sorted().joinToString("|")

/** True when two of these copies come from the same source, which a link can't hold. */
fun Set<CopyKey>.repeatsSource(): Boolean =
    map { key -> key.source }.distinct().size != size

/**
 * Merges links that share members, keeping [first]'s id. Null when the result would
 * repeat a source.
 */
fun mergeLinks(first: BookLink, second: BookLink): BookLink? {
    val members = first.members + second.members
    return if (members.repeatsSource()) null else BookLink(first.linkId, members)
}

/** The source that [this] set holds more than once, or null. */
fun Set<CopyKey>.repeatedSource(): CopySource? =
    groupBy { key -> key.source }.entries.firstOrNull { (_, keys) -> keys.size > 1 }?.key

/** Validate both complete groups, not just the two selected versions. */
fun repeatedMergeSource(first: CopyKey, second: CopyKey, links: List<BookLink>): CopySource? {
    val firstMembers = links.firstOrNull { first in it.members }?.members.orEmpty() + first
    val secondMembers = links.firstOrNull { second in it.members }?.members.orEmpty() + second
    return (firstMembers + secondMembers).repeatedSource()
}

/**
 * Which of two links survives a merge: the one created first, then the smaller id. A
 * creation time that can't be read counts as newest.
 */
fun olderLinkId(
    firstId: String,
    firstCreatedAt: String,
    secondId: String,
    secondCreatedAt: String,
): String {
    val first = Instant.parseOrNull(firstCreatedAt)
    val second = Instant.parseOrNull(secondCreatedAt)
    return when {
        first != null && second != null && first != second ->
            if (first < second) firstId else secondId
        first != null && second == null -> firstId
        first == null && second != null -> secondId
        else -> minOf(firstId, secondId)
    }
}

fun BookDomainModel.copyKey(): CopyKey = when (this) {
    is BookDomainModel.LibraryBook -> CopyKey(CopySource.Library, uuid)
    is BookDomainModel.StorytellerBook -> if (serverType == ServerType.Audiobookshelf) {
        CopyKey(CopySource.Audiobookshelf, uuid)
    } else {
        CopyKey(CopySource.Storyteller, uuid)
    }
}
