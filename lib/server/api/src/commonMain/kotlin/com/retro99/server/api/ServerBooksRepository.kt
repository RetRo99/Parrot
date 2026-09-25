package com.retro99.server.api

import com.github.michaelbull.result.map
import com.retro99.base.result.AppResult
import com.retro99.base.result.CompletableResult
import com.retro99.server.api.library.LibraryAdapterId
import com.retro99.server.api.library.NativeBookId
import com.retro99.server.api.library.SourceAccountIdentity
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * Server-aware books repository interface.
 * Extends the base repository pattern with server context.
 */
interface ServerBooksRepository {
    /**
     * The server ID this repository is associated with.
     */
    val serverId: String

    /**
     * Generic library-adapter registration key. Legacy repositories may leave this unset
     * until they implement the additive source contract.
     */
    val libraryAdapterId: LibraryAdapterId?
        get() = null

    /**
     * Certified backend/account identity for portable grouping. Implementations must return null
     * unless both values come from a stable server-owned or authenticated account identifier.
     */
    suspend fun libraryAccountIdentity(): SourceAccountIdentity.Portable? = null

    /**
     * Get all books from this server.
     */
    fun getBooks(): Flow<AppResult<List<ServerBook>>>

    /**
     * Get a source listing with an explicit completeness guarantee.
     * Existing repositories default to partial until they can prove each emission is complete.
     */
    fun getLibraryListing(): Flow<AppResult<ServerBookListing>> = getBooks().map { result ->
        result.map { books ->
            ServerBookListing(
                books = books,
                completeness = ServerBookListingCompleteness.Partial,
            )
        }
    }

    /**
     * Get a specific book by UUID.
     */
    fun getBook(uuid: String): Flow<AppResult<ServerBook>>

    /**
     * Save a book to this server.
     */
    suspend fun saveBook(book: ServerBook): CompletableResult

    /**
     * Search for books on this server.
     */
    suspend fun searchBooks(query: String): AppResult<List<ServerBook>>
}

data class ServerBookListing(
    val books: List<ServerBook>,
    val completeness: ServerBookListingCompleteness,
    /** Explicit source-native removals from this successful listing or change feed. */
    val removedNativeBookIds: Set<NativeBookId> = emptySet(),
)

enum class ServerBookListingCompleteness {
    Complete,
    Partial,
}

/**
 * A book associated with a specific server.
 * This is a lightweight wrapper that includes server context.
 */
data class ServerBook(
    val uuid: String,
    val serverId: String,
    val title: String,
    val description: String?,
    val coverUrl: String?,
    val authors: List<String>,
    val narrators: List<String>,
    val series: List<ServerBookSeries>,
    val tags: List<String>,
    val hasEbook: Boolean,
    val hasAudiobook: Boolean,
    val hasReadaloud: Boolean,
    // File paths (nullable - Storyteller has paths for each media type, local books have one)
    val ebookFilepath: String? = null,
    val audiobookFilepath: String? = null,
    val readaloudFilepath: String? = null,
    // File sizes (optional)
    val ebookFileSize: Long? = null,
    val audiobookFileSize: Long? = null,
    val readaloudFileSize: Long? = null,
    // Timestamps
    val createdAt: String? = null,
    val lastOpenedAt: String? = null,
    val publicationDate: String? = null,
    // Local book flag
    val isLocal: Boolean = false,
    val serverType: ServerType? = null,
    val libraryBookId: String? = null,
    val contentHash: String? = null,
    val contentHashAlgorithm: String? = null,
    val remoteFileAvailability: RemoteFileAvailability = RemoteFileAvailability.None,
    val remoteRevision: Long? = null,
    val mediaResources: List<MediaResource> = emptyList(),
    val localSourceUuid: String? = null,
    val collections: List<ServerBookCollection> = emptyList(),
)

enum class RemoteFileAvailability {
    None,
    Available,
    UploadPending,
    Uploading,
    UploadFailed,
    Deleting,
}

data class ServerBookSeries(
    val id: String?,
    val name: String,
    val sequence: Float?,
)

data class ServerBookCollection(
    val id: String,
    val name: String,
    val createdAt: String? = null,
    val updatedAt: String? = null,
)
