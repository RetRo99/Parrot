package com.retro99.books.domain.model.links

import com.retro99.books.domain.model.BookDomainModel
import com.retro99.books.domain.model.home

/**
 * The copy a linked book's card opens:
 * 1. the copy being read, else the copy opened most recently on this device;
 * 2. otherwise by home: Parrot Cloud, This device, Storyteller, Audiobookshelf;
 * 3. ties break on the key, so the choice is stable.
 *
 * [lastOpened] holds when each copy was last opened, in epoch milliseconds.
 */
fun choosePrimary(
    copies: List<BookDomainModel>,
    lastOpened: Map<CopyKey, Long>,
    currentlyReading: CopyKey?,
): BookDomainModel {
    require(copies.isNotEmpty()) { "A linked book has at least one copy" }
    copies.firstOrNull { copy -> copy.copyKey() == currentlyReading }?.let { copy -> return copy }
    return copies.sortedWith(
        compareByDescending<BookDomainModel> { copy -> lastOpened[copy.copyKey()] ?: Long.MIN_VALUE }
            .thenBy { copy -> copy.home.linkOrder }
            .thenBy { copy -> copy.copyKey().value },
    ).first()
}

/**
 * Turns the copies of each link into one entry: the primary copy, carrying the others as
 * `linkedCopies`. Books that aren't linked pass through unchanged, and so does a book whose
 * linked copies no configured source lists.
 *
 * [downloaded] names server copies with a cached download; library copies know their own.
 */
fun groupLinkedBooks(
    books: List<BookDomainModel>,
    links: List<BookLink>,
    lastOpened: Map<CopyKey, Long> = emptyMap(),
    currentlyReading: CopyKey? = null,
    downloaded: Set<CopyKey> = emptySet(),
): List<BookDomainModel> {
    if (links.isEmpty()) return books
    val linkByMember = links
        .flatMap { link -> link.members.map { member -> member to link.linkId } }
        .toMap()
    val copiesByLink = books
        .filter { book -> book.copyKey() in linkByMember }
        .groupBy { book -> linkByMember.getValue(book.copyKey()) }
    val emittedLinks = mutableSetOf<String>()
    return books.mapNotNull { book ->
        val linkId = linkByMember[book.copyKey()] ?: return@mapNotNull book
        // The group takes the place of its first copy in the list.
        if (!emittedLinks.add(linkId)) return@mapNotNull null
        val copies = copiesByLink.getValue(linkId)
        val primary = choosePrimary(copies, lastOpened, currentlyReading)
        val others = copies
            .filter { copy -> copy.copyKey() != primary.copyKey() }
            .distinctBy { copy -> copy.copyKey() }
            .map { copy ->
                val linkedCopy = copy.toLinkedCopy()
                linkedCopy.copy(
                    isDownloaded = linkedCopy.isDownloaded || copy.copyKey() in downloaded,
                )
            }
            .sortedWith(compareBy({ copy -> copy.home.linkOrder }, { copy -> copy.key.value }))
        if (others.isEmpty()) primary else primary.withLinkedCopies(others)
    }
}
