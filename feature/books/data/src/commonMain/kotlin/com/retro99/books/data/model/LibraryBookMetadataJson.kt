package com.retro99.books.data.model

import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/** Reads and writes `library_books.metadata_json`: details of a book beyond its columns. */
internal object LibraryBookMetadataJson {
    private val json = Json {
        ignoreUnknownKeys = true
    }

    /** Null when there is nothing to store. */
    fun encode(isbn: String?): String? =
        isbn?.let { value -> json.encodeToString(LibraryBookMetadata(isbn = value)) }

    fun isbn(metadataJson: String?): String? {
        if (metadataJson.isNullOrBlank()) return null
        return try {
            json.decodeFromString<LibraryBookMetadata>(metadataJson).isbn
        } catch (_: SerializationException) {
            null
        } catch (_: IllegalArgumentException) {
            null
        }
    }
}

@Serializable
private data class LibraryBookMetadata(
    val isbn: String? = null,
)
