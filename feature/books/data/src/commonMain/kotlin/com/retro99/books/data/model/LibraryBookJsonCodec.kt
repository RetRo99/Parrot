package com.retro99.books.data.model

import com.retro99.database.api.library.LibraryBookEntity
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/** Encodes the `library_book` outbox payload that Parrot Cloud's push RPC reads. */
internal object LibraryBookJsonCodec {
    private val json = Json {
        encodeDefaults = true
    }

    fun encode(book: LibraryBookEntity, format: String): String {
        return json.encodeToString(
            LibraryBookPayload(
                libraryBookId = book.libraryBookId,
                title = book.title,
                author = book.author,
                format = format,
                sourceContentHash = book.sourceContentHash,
                sourceContentHashAlgorithm = book.sourceContentHashAlgorithm,
                metadataJson = book.metadataJson,
                remoteRevision = book.remoteRevision,
            ),
        )
    }
}

@Serializable
private data class LibraryBookPayload(
    @SerialName("library_book_id")
    val libraryBookId: String,
    val title: String,
    val author: String?,
    val format: String,
    @SerialName("source_content_hash")
    val sourceContentHash: String?,
    @SerialName("source_content_hash_algorithm")
    val sourceContentHashAlgorithm: String?,
    @SerialName("metadata_json")
    val metadataJson: String?,
    @SerialName("remote_revision")
    val remoteRevision: Long?,
)
