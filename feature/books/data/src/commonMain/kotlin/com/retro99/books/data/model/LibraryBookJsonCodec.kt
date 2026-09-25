package com.retro99.books.data.model

import com.retro99.database.api.library.LibraryBookEntity
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

internal object LibraryBookJsonCodec {
    private val json = Json {
        encodeDefaults = true
    }

    fun encode(
        book: LibraryBookEntity,
        intentionalReimport: Boolean = false,
    ): String {
        return json.encodeToString(
            LibraryBookPayload(
                libraryBookId = book.libraryBookId,
                contentHash = book.contentHash,
                contentHashAlgorithm = book.contentHashAlgorithm,
                title = book.title,
                author = book.author,
                format = book.format,
                remoteRevision = book.remoteRevision,
                deletedAt = book.deletedAt,
                cloudBookId = book.cloudBookId,
                metadataJson = book.metadataJson,
                intentionalReimport = intentionalReimport,
            ),
        )
    }

    fun decode(payload: String): LibraryBookEntity {
        return json.decodeFromString<LibraryBookPayload>(payload)
    }
}

@Serializable
private data class LibraryBookPayload(
    @SerialName("library_book_id")
    override val libraryBookId: String,
    @SerialName("content_hash")
    override val contentHash: String?,
    @SerialName("content_hash_algorithm")
    override val contentHashAlgorithm: String?,
    override val title: String,
    override val author: String?,
    override val format: String,
    @SerialName("remote_revision")
    override val remoteRevision: Long?,
    @SerialName("deleted_at")
    override val deletedAt: String?,
    @SerialName("cloud_book_id")
    override val cloudBookId: String? = null,
    @SerialName("metadata_json")
    override val metadataJson: String? = null,
    @SerialName("intentional_reimport")
    val intentionalReimport: Boolean = false,
) : LibraryBookEntity
