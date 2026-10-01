package com.retro99.server.parrotcloud

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * A library book as Parrot Cloud sends it: in pulled changes, and in duplicate and
 * conflict results. Its id is the client's book id.
 */
@Serializable
internal data class ParrotCloudBookPayload(
    @SerialName("library_book_id")
    val libraryBookId: String,
    @SerialName("source_content_hash")
    val sourceContentHash: String? = null,
    @SerialName("source_content_hash_algorithm")
    val sourceContentHashAlgorithm: String? = null,
    val title: String,
    val author: String? = null,
    val format: String? = null,
    @SerialName("metadata_json")
    val metadataJson: String? = null,
    @SerialName("remote_revision")
    val remoteRevision: Long? = null,
)
