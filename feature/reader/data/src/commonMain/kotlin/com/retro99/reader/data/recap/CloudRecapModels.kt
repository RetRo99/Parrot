package com.retro99.reader.data.recap

import kotlinx.serialization.Serializable

@Serializable
data class CloudRecapPage(val items: List<CloudRecapRecord>, val nextCursor: Long, val hasMore: Boolean, val consentEnabled: Boolean = true)

@Serializable
data class CloudRecapPosition(val href: String? = null, val progression: Double? = null, val totalProgression: Double? = null)

@Serializable
data class CloudRecapRecord(
    val sessionId: String,
    val cloudBookId: String? = null,
    val state: String,
    val language: String? = null,
    val endedAt: Long? = null,
    val position: CloudRecapPosition? = null,
    val summary: String? = null,
    val model: String? = null,
    val errorCode: String? = null,
    val changeId: Long = 0,
    val expiresAt: Long,
) {
    override fun toString() = "CloudRecapRecord(state=$state, changeId=$changeId)"
}
