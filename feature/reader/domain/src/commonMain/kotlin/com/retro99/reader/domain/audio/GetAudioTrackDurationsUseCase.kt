package com.retro99.reader.domain.audio

import com.retro99.database.api.books.BooksDatabase
import org.koin.core.annotation.Factory
import org.koin.core.annotation.Provided

/** Each audio file's length cached from the server (Audiobookshelf), or null when unknown. */
@Factory
class GetAudioTrackDurationsUseCase(
    @Provided private val booksDatabase: BooksDatabase,
) {
    suspend operator fun invoke(serverId: String, bookUuid: String): List<Long>? =
        booksDatabase.getBookByServerAndUuid(serverId, bookUuid)?.audioTrackDurationsMs
}
