package com.retro99.books.domain.model.links

import com.retro99.books.domain.model.BookDomainModel
import com.retro99.books.domain.model.BookHome
import com.retro99.books.domain.model.BookType
import com.retro99.books.domain.model.hasDeviceCopy
import com.retro99.books.domain.model.home

/** Another copy of a linked book that this device can open. */
data class LinkedCopy(
    val key: CopyKey,
    val serverId: String,
    val uuid: String,
    val title: String,
    val home: BookHome,
    val hasEbook: Boolean,
    val hasAudiobook: Boolean,
    val hasReadaloud: Boolean,
    val isDownloaded: Boolean,
)

/** The order homes are listed in, and preferred in, for a linked book. */
val BookHome.linkOrder: Int
    get() = when (this) {
        BookHome.ParrotCloud -> 0
        BookHome.ThisDevice -> 1
        BookHome.Storyteller -> 2
        BookHome.Audiobookshelf -> 3
    }

/**
 * [isDownloaded] is known here only for library books (a device copy); callers that know a
 * server book's download cache pass it in.
 */
fun BookDomainModel.toLinkedCopy(
    isDownloaded: Boolean = this is BookDomainModel.LibraryBook && mediaResources.hasDeviceCopy(),
): LinkedCopy = LinkedCopy(
    key = copyKey(),
    serverId = serverId,
    uuid = uuid,
    title = title,
    home = home,
    hasEbook = hasMedia(BookType.EBOOK),
    hasAudiobook = hasMedia(BookType.AUDIOBOOK),
    hasReadaloud = hasMedia(BookType.READALOUD),
    isDownloaded = isDownloaded,
)

private fun BookDomainModel.hasMedia(bookType: BookType): Boolean = when (this) {
    is BookDomainModel.LibraryBook ->
        mediaResources.any { resource -> resource.mediaType == bookType.value }
    is BookDomainModel.StorytellerBook -> when (bookType) {
        BookType.EBOOK -> ebook != null
        BookType.AUDIOBOOK -> audiobook != null
        BookType.READALOUD -> readaloud != null
    }
}

/**
 * The other copies [book] is linked to, among [books]. A linked copy that no configured
 * source lists (the link came from another device) is left out.
 */
fun linkedCopiesOf(
    book: BookDomainModel,
    books: List<BookDomainModel>,
    links: List<BookLink>,
): List<LinkedCopy> {
    val key = book.copyKey()
    val link = links.firstOrNull { candidate -> key in candidate.members } ?: return emptyList()
    return (link.members - key)
        .mapNotNull { member -> books.firstOrNull { other -> other.copyKey() == member } }
        .map { other -> other.toLinkedCopy() }
        .sortedWith(compareBy({ copy -> copy.home.linkOrder }, { copy -> copy.key.value }))
}

/** Books [book] can be linked to: from another source, and not linked to it already. */
fun linkPickerCandidates(
    book: BookDomainModel,
    books: List<BookDomainModel>,
    links: List<BookLink>,
): List<BookDomainModel> {
    val key = book.copyKey()
    val linked = links.firstOrNull { link -> key in link.members }?.members.orEmpty()
    return books
        .filter { other -> other.copyKey().source != key.source && other.copyKey() !in linked }
        .distinctBy { other -> other.copyKey() }
}
