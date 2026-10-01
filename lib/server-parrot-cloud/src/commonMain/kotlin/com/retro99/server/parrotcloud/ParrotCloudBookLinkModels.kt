package com.retro99.server.parrotcloud

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * A book link as Parrot Cloud sends and receives it: in pushed mutations, pulled changes
 * and conflict results.
 */
@Serializable
internal data class ParrotCloudBookLinkPayload(
    @SerialName("link_id")
    val linkId: String,
    val members: List<String> = emptyList(),
    val deleted: Boolean = false,
    @SerialName("remote_revision")
    val remoteRevision: Long? = null,
    @SerialName("created_at")
    val createdAt: String? = null,
)

/** A "not the same book" or "skip" decision as Parrot Cloud sends it. */
@Serializable
internal data class ParrotCloudBookLinkDecisionPayload(
    @SerialName("pair_key")
    val pairKey: String,
    val decision: String,
    @SerialName("decided_at")
    val decidedAt: String,
    @SerialName("remote_revision")
    val remoteRevision: Long? = null,
)
