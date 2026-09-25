package com.retro99.books.domain.model

import com.retro99.library.domain.projection.LibraryBookGroup
import com.retro99.library.domain.projection.LibraryGroupMember
import com.retro99.server.api.MediaResource
import com.retro99.server.api.RemoteFileAvailability
import com.retro99.server.api.ServerBook
import com.retro99.server.api.ServerBookSeries
import com.retro99.server.api.ServerType
import com.retro99.server.api.library.LibraryAdapterId
import com.retro99.server.api.library.SourceBookCollection
import com.retro99.server.api.library.SourceBookKey
import com.retro99.server.api.library.SourceResourceAvailability
import com.retro99.server.api.library.SourcePresence

data class LibraryBookSourceFeed(
    val adapterId: LibraryAdapterId?,
    val connectionId: String,
    val books: List<ServerBook>,
)

data class UnifiedServerBook(
    val book: ServerBook,
    val groupId: String?,
    val memberUuids: List<String>,
    val alternateTitles: List<String>,
    val memberServerTypes: Set<ServerType>,
    val mediaTypes: Set<String>,
    val progressSources: List<UnifiedBookProgressSource> = emptyList(),
    val preferredMediaSourceKeys: Map<String, SourceBookKey> = emptyMap(),
    val collectionsBySource: Map<SourceBookKey, List<SourceBookCollection>> = emptyMap(),
)

/** A source-native owner for progress, kept separate from the displayed group row. */
data class UnifiedBookProgressSource(
    val sourceKey: SourceBookKey?,
    val serverId: String?,
    val bookUuid: String,
    val libraryBookId: String?,
    val mediaTypes: Set<String>,
)

fun projectUnifiedServerBooks(
    groups: List<LibraryBookGroup>,
    feeds: List<LibraryBookSourceFeed>,
): List<UnifiedServerBook> {
    val consumedBooks = mutableSetOf<Pair<String, String>>()
    val groupedBooks = groups.mapNotNull { group ->
        val visibleMembers = group.members.filter { member ->
            member.snapshot.status.presence != SourcePresence.Removed
        }
        if (visibleMembers.isEmpty()) return@mapNotNull null

        val liveBooksByMember = group.members.associateWith { member ->
            findLiveBooks(member, feeds)
        }
        val liveBooks = visibleMembers.flatMap { member ->
            liveBooksByMember.getValue(member).onEach { (feed, serverBook) ->
                consumedBooks += feed.connectionId to serverBook.uuid
            }
        }
        val displayMember = visibleMembers.first()
        val chosen = liveBooksByMember.getValue(displayMember).firstOrNull()
            ?: liveBooks.firstOrNull()
        val baseBook = chosen?.second ?: displayMember.toServerBook()
        val metadata = group.displayMetadata
        val snapshotMediaTypes = group.members
            .flatMap { member -> member.mediaTypes() }
            .toSet()
        val allMembers = group.members
        val books = group.members.flatMap { member ->
            liveBooksByMember.getValue(member).map { (_, serverBook) -> serverBook }
        }
        val progressSources = visibleMembers.flatMap { member ->
            val liveBooksForMember = liveBooksByMember.getValue(member)
            if (liveBooksForMember.isEmpty()) {
                listOf(
                    UnifiedBookProgressSource(
                        sourceKey = member.sourceKey,
                        serverId = member.snapshot.source.connectionId?.value,
                        bookUuid = member.sourceKey.nativeBookId.value,
                        libraryBookId = member.snapshot.source.legacyLibraryBookId?.value,
                        mediaTypes = member.mediaTypes(),
                    ),
                )
            } else {
                liveBooksForMember.map { (feed, serverBook) ->
                    UnifiedBookProgressSource(
                        sourceKey = member.sourceKey,
                        serverId = serverBook.serverId.ifBlank { feed.connectionId },
                        bookUuid = serverBook.uuid,
                        libraryBookId = serverBook.libraryBookId
                            ?: member.snapshot.source.legacyLibraryBookId?.value,
                        mediaTypes = serverBook.mediaTypes().ifEmpty {
                            member.mediaTypes()
                        },
                    )
                }
            }
        }.distinctBy { source ->
            Triple(source.sourceKey, source.serverId, source.bookUuid)
        }
        val mediaTypes = snapshotMediaTypes + books.flatMap { book -> book.mediaTypes() }
        val series = allMembers
            .flatMap { member -> member.snapshot.metadata.series }
            .distinctBy { item -> Triple(item.nativeId, item.name, item.sequence) }

        UnifiedServerBook(
            book = baseBook.copy(
                title = metadata.title,
                description = metadata.description,
                coverUrl = metadata.coverReference,
                authors = allMembers.flatMap { member -> member.snapshot.metadata.authors }
                    .distinct(),
                narrators = allMembers.flatMap { member -> member.snapshot.metadata.narrators }
                    .distinct(),
                series = series.map { item ->
                    ServerBookSeries(item.nativeId, item.name, item.sequence)
                },
                tags = allMembers.flatMap { member -> member.snapshot.metadata.tags }.distinct(),
                hasEbook = "ebook" in mediaTypes || books.any { item -> item.hasEbook },
                hasAudiobook = "audiobook" in mediaTypes || books.any { item -> item.hasAudiobook },
                hasReadaloud = "readaloud" in mediaTypes || books.any { item -> item.hasReadaloud },
                publicationDate = metadata.publicationDate,
                libraryBookId = baseBook.libraryBookId
                    ?: displayMember.snapshot.source.legacyLibraryBookId?.value,
            ),
            groupId = group.groupId.value,
            memberUuids = group.members.flatMap { member ->
                val memberBooks = liveBooksByMember.getValue(member)
                if (memberBooks.isEmpty()) {
                    listOf(member.sourceKey.nativeBookId.value)
                } else {
                    memberBooks.map { (_, serverBook) -> serverBook.uuid }
                }
            }.distinct(),
            alternateTitles = group.members
                .map { member -> member.snapshot.metadata.title }
                .filter { title -> title != metadata.title }
                .distinct(),
            memberServerTypes = allMembers.mapNotNull { member ->
                ServerType.fromIdentifier(member.sourceKey.adapterId.value)
            }.toSet() + books.mapNotNull { book -> book.serverType },
            mediaTypes = mediaTypes,
            progressSources = progressSources,
            preferredMediaSourceKeys = group.preferredMediaSourceKeys,
            collectionsBySource = allMembers.associate { member ->
                member.sourceKey to member.snapshot.metadata.collections
            },
        )
    }

    val legacyBooks = feeds.flatMap { feed ->
        feed.books.filter { book -> (feed.connectionId to book.uuid) !in consumedBooks }
    }.aggregateBookReplicas()

    val legacyProjections = legacyBooks.map { book ->
        UnifiedServerBook(
            book = book,
            groupId = null,
            memberUuids = listOf(book.uuid),
            alternateTitles = emptyList(),
            memberServerTypes = setOfNotNull(book.serverType),
            mediaTypes = buildSet {
                if (book.hasEbook) add("ebook")
                if (book.hasAudiobook) add("audiobook")
                if (book.hasReadaloud) add("readaloud")
            },
            progressSources = listOf(
                UnifiedBookProgressSource(
                    sourceKey = null,
                    serverId = book.serverId,
                    bookUuid = book.uuid,
                    libraryBookId = book.libraryBookId,
                    mediaTypes = book.mediaTypes(),
                ),
            ),
        )
    }

    return (groupedBooks + legacyProjections).sortedWith(
        compareBy<UnifiedServerBook> { item -> item.book.title.lowercase() }
            .thenBy { item -> item.groupId.orEmpty() },
    )
}

private fun ServerBook.mediaTypes(): Set<String> = buildSet {
    if (hasEbook) add("ebook")
    if (hasAudiobook) add("audiobook")
    if (hasReadaloud) add("readaloud")
    mediaResources.forEach { resource -> add(resource.mediaType.lowercase()) }
}

private fun LibraryGroupMember.mediaTypes(): Set<String> = buildSet {
    snapshot.metadata.mediaTypes.forEach { mediaType -> add(mediaType.lowercase()) }
    snapshot.resources.forEach { resource -> add(resource.mediaType.lowercase()) }
}

private fun findLiveBooks(
    member: LibraryGroupMember,
    feeds: List<LibraryBookSourceFeed>,
): List<Pair<LibraryBookSourceFeed, ServerBook>> {
    val connectionId = member.snapshot.source.connectionId?.value
    return feeds.asSequence()
        .filter { feed -> feed.adapterId == member.sourceKey.adapterId }
        .filter { feed -> connectionId == null || feed.connectionId == connectionId }
        .flatMap { feed ->
            feed.books.asSequence()
                .filter { book ->
                    book.uuid == member.sourceKey.nativeBookId.value ||
                        member.snapshot.resources.any { resource ->
                            resource.reference.nativeResourceId == book.uuid
                        }
                }
                .map { book -> feed to book }
        }
        .distinctBy { (feed, book) -> feed.connectionId to book.uuid }
        .sortedWith(
            compareBy<Pair<LibraryBookSourceFeed, ServerBook>> { pair -> pair.first.connectionId }
                .thenBy { pair -> pair.second.uuid },
        )
        .toList()
}

private fun LibraryGroupMember.toServerBook(): ServerBook {
    val metadata = snapshot.metadata
    val mediaTypes = metadata.mediaTypes.toSet()
    return ServerBook(
        uuid = sourceKey.nativeBookId.value,
        serverId = snapshot.source.connectionId?.value.orEmpty(),
        title = metadata.title,
        description = metadata.description,
        coverUrl = metadata.coverReference,
        authors = metadata.authors,
        narrators = metadata.narrators,
        series = metadata.series.map { item ->
            ServerBookSeries(item.nativeId, item.name, item.sequence)
        },
        tags = metadata.tags,
        hasEbook = "ebook" in mediaTypes,
        hasAudiobook = "audiobook" in mediaTypes,
        hasReadaloud = "readaloud" in mediaTypes,
        publicationDate = metadata.publicationDate,
        libraryBookId = snapshot.source.legacyLibraryBookId?.value,
        mediaResources = snapshot.resources.map { resource ->
            MediaResource(
                mediaType = resource.mediaType,
                remoteAvailability = when (resource.availability) {
                    SourceResourceAvailability.AvailableRemotely ->
                        RemoteFileAvailability.Available
                    else -> RemoteFileAvailability.None
                },
                size = resource.sizeBytes,
                nativeResourceId = resource.reference.nativeResourceId,
                resourceRevision = resource.reference.revision,
                format = resource.format,
            )
        },
    )
}
