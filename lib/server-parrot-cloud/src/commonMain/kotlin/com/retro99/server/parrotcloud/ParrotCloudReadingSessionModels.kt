package com.retro99.server.parrotcloud

import com.retro99.database.api.statistics.ReadingSessionEntity
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Wire payload for a reading statistics session in Parrot Cloud.
 *
 * Sessions are an append-only ledger: rows are never updated, so they need no
 * remote revision handling. [sessionId] is derived from the session's
 * immutable fields instead of a local column (the local database cannot grow
 * one without a destructive schema reset), which keeps uploads idempotent and
 * lets a re-sweep of the same local row re-derive the same cloud identity.
 *
 * [bookUuid] travels verbatim in both directions so that re-derivation on any
 * device reproduces the same session id.
 */
@Serializable
internal data class ParrotCloudReadingSessionPayload(
    @SerialName("session_id")
    val sessionId: String,
    @SerialName("book_uuid")
    val bookUuid: String,
    @SerialName("book_title")
    val bookTitle: String,
    @SerialName("book_type")
    val bookType: String,
    @SerialName("start_time")
    val startTime: Long,
    @SerialName("end_time")
    val endTime: Long,
    @SerialName("duration_ms")
    val durationMs: Long,
    @SerialName("pages_read")
    val pagesRead: Int? = null,
    @SerialName("start_progression")
    val startProgression: Double? = null,
    @SerialName("end_progression")
    val endProgression: Double? = null,
    @SerialName("reading_speed_wpm")
    val readingSpeedWpm: Int? = null,
)

/**
 * Deterministic cloud identity for a reading session.
 *
 * Derived from the same fields as the local natural key so that every copy of
 * a session row (the original and any applied from a pull) maps to one cloud
 * row, and replayed uploads are no-ops.
 */
internal fun readingSessionSyncId(
    bookUuid: String,
    bookType: String,
    startTime: Long,
    endTime: Long,
    durationMs: Long,
): String {
    return "rs1|" +
        "${escapeIdField(bookUuid)}|" +
        "${escapeIdField(bookType)}|" +
        "$startTime|$endTime|$durationMs"
}

// Escaping keeps the encoding injective: a separator inside a field can never
// be confused with the id's own separators, so two distinct sessions can
// never collapse into one cloud identity.
private fun escapeIdField(value: String): String {
    return value.replace("\\", "\\\\").replace("|", "\\|")
}

internal fun ReadingSessionEntity.toParrotCloudReadingSessionPayload(): ParrotCloudReadingSessionPayload {
    return ParrotCloudReadingSessionPayload(
        sessionId = readingSessionSyncId(
            bookUuid = bookUuid,
            bookType = bookType,
            startTime = startTime,
            endTime = endTime,
            durationMs = durationMs,
        ),
        bookUuid = bookUuid,
        bookTitle = bookTitle,
        bookType = bookType,
        startTime = startTime,
        endTime = endTime,
        durationMs = durationMs,
        pagesRead = pagesRead,
        startProgression = startProgression,
        endProgression = endProgression,
        readingSpeedWpm = readingSpeedWpm,
    )
}

internal fun ParrotCloudReadingSessionPayload.toReadingSessionEntity(): ReadingSessionEntity {
    return ParrotCloudReadingSessionEntity(
        bookUuid = bookUuid,
        bookTitle = bookTitle,
        bookType = bookType,
        startTime = startTime,
        endTime = endTime,
        durationMs = durationMs,
        pagesRead = pagesRead,
        startProgression = startProgression,
        endProgression = endProgression,
        readingSpeedWpm = readingSpeedWpm,
    )
}

private data class ParrotCloudReadingSessionEntity(
    override val bookUuid: String,
    override val bookTitle: String,
    override val bookType: String,
    override val startTime: Long,
    override val endTime: Long,
    override val durationMs: Long,
    override val pagesRead: Int?,
    override val startProgression: Double?,
    override val endProgression: Double?,
    override val readingSpeedWpm: Int?,
) : ReadingSessionEntity {
    override val id: Long = 0L
}
