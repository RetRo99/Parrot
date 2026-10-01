package com.retro99.server.api

import com.retro99.base.result.AppResult
import com.retro99.base.result.CompletableResult
import kotlinx.coroutines.flow.Flow

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
     * Get all books from this server.
     */
    fun getBooks(): Flow<AppResult<List<ServerBook>>>

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
    // Used to suggest that books on different sources are the same book.
    val language: String? = null,
    val isbn: String? = null,
    val asin: String? = null,
)

enum class RemoteFileAvailability {
    None,
    Available,
    UploadPending,
    Uploading,
    UploadFailed,
    Deleting,
    ;

    companion object {
        /** Maps a Parrot Cloud file status (`cloud_book_file_state.status`). */
        fun fromFileStatus(status: String): RemoteFileAvailability = when (status) {
            "available" -> Available
            "upload_pending" -> UploadPending
            "uploading" -> Uploading
            "upload_failed" -> UploadFailed
            "deleting" -> Deleting
            else -> None
        }
    }
}

data class ServerBookSeries(
    val id: String?,
    val name: String,
    val sequence: Float?,
)
