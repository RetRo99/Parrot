package com.retro99.server.parrotcloud

import com.retro99.database.api.statistics.ReadingSessionDatabase
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.decodeFromJsonElement
import org.koin.core.annotation.Provided
import org.koin.core.annotation.Single

/**
 * Applies reading-session changes pulled from Parrot Cloud to the local
 * statistics ledger.
 *
 * Sessions are immutable rows, so applying is insert-if-absent keyed by the
 * session's natural identity (the same fields its cloud identity is derived
 * from). Applied rows keep the origin book uuid verbatim so a later sweep of
 * the same row re-derives the same session id instead of duplicating it.
 */
@Single
class ParrotCloudReadingSessionChangeApplier(
    @Provided private val readingSessionDatabase: ReadingSessionDatabase,
) {
    private val json = Json {
        encodeDefaults = true
        ignoreUnknownKeys = true
        coerceInputValues = true
    }

    suspend fun apply(payload: JsonElement) {
        // Let decoding failures abort the pull page. Treating a malformed
        // change as applied would advance the checkpoint and permanently lose
        // it, including when a later client version learns to decode it.
        val session = json.decodeFromJsonElement<ParrotCloudReadingSessionPayload>(payload)
        val existing = readingSessionDatabase.getSessionByNaturalKey(
            bookUuid = session.bookUuid,
            bookType = session.bookType,
            startTime = session.startTime,
            endTime = session.endTime,
            durationMs = session.durationMs,
        )
        if (existing != null) return
        readingSessionDatabase.insertSession(session.toReadingSessionEntity())
    }
}
