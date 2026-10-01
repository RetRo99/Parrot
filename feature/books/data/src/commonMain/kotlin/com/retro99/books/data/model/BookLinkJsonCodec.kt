package com.retro99.books.data.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * Encodes the `book_link` and `book_link_decision` outbox payloads that Parrot Cloud's push
 * RPC reads.
 */
internal object BookLinkJsonCodec {
    private val json = Json {
        encodeDefaults = true
    }

    fun encodeLink(
        linkId: String,
        members: List<String>,
        deleted: Boolean,
        removedMembers: List<String> = emptyList(),
    ): String = json.encodeToString(BookLinkPayload(linkId, members, deleted, removedMembers))

    fun encodeDecision(pairKey: String, decision: String, decidedAt: String): String =
        json.encodeToString(BookLinkDecisionPayload(pairKey, decision, decidedAt))
}

@Serializable
private data class BookLinkPayload(
    @SerialName("link_id")
    val linkId: String,
    val members: List<String>,
    val deleted: Boolean,
    @SerialName("removed_members")
    val removedMembers: List<String> = emptyList(),
)

@Serializable
private data class BookLinkDecisionPayload(
    @SerialName("pair_key")
    val pairKey: String,
    val decision: String,
    @SerialName("decided_at")
    val decidedAt: String,
)
