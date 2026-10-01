package com.retro99.reader.domain.translate

import com.retro99.books.domain.model.links.LinkedCopy
import com.retro99.database.api.books.BooksDatabase
import org.koin.core.annotation.Factory
import org.koin.core.annotation.Provided

/** The total length of a server audiobook, from its cached item (P6b). */
fun interface AudiobookDurations {
    suspend fun durationMs(copy: LinkedCopy): Long?

    /** Each audio file's length in playlist order, when the server reported them. */
    suspend fun trackDurationsMs(copy: LinkedCopy): List<Long>? = null
}

/** Audiobookshelf reports `duration` (or its audio files' durations) with each item. */
@Factory(binds = [AudiobookDurations::class])
class CachedAudiobookDurations(
    @Provided private val booksDatabase: BooksDatabase,
) : AudiobookDurations {
    override suspend fun durationMs(copy: LinkedCopy): Long? =
        booksDatabase.getBookByServerAndUuid(copy.serverId, copy.uuid)?.audioDurationMs

    override suspend fun trackDurationsMs(copy: LinkedCopy): List<Long>? =
        booksDatabase.getBookByServerAndUuid(copy.serverId, copy.uuid)?.audioTrackDurationsMs
}
