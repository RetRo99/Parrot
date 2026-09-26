package com.retro99.server.storyteller

import com.retro99.server.storyteller.model.StorytellerBookApiModel
import com.retro99.server.storyteller.model.StorytellerMediaFileApiModel
import com.retro99.server.storyteller.model.StorytellerPersonApiModel
import com.retro99.server.storyteller.model.StorytellerReadaloudApiModel
import com.retro99.server.storyteller.model.StorytellerSeriesApiModel
import com.retro99.server.storyteller.model.StorytellerTagApiModel
import com.retro99.server.storyteller.model.toDomain
import com.retro99.server.storyteller.source.toEntity
import com.retro99.server.storyteller.source.toServerBook
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Guards the cache round trip: `cachedRemoteFlow` skips its cache write when the remote
 * payload equals what it just read back, so a book must survive
 * `toDomain -> toEntity -> toServerBook` unchanged or that optimisation is a no-op.
 */
class ServerBookEntityRoundTripTest {

    @Test
    fun remoteBookRoundTripsThroughTheCacheUnchanged() {
        val apiModel = StorytellerBookApiModel(
            uuid = "book-1",
            title = "Oathbringer",
            language = "en",
            createdAt = "2017-11-14",
            updatedAt = "2020-01-01",
            publicationDate = "2017-11-14",
            description = "Humanity faces a new Desolation.",
            rating = 4.5f,
            suffix = "The Stormlight Archive",
            subtitle = "Book Three",
            authors = listOf(StorytellerPersonApiModel(id = 1, name = "Brandon Sanderson")),
            narrators = listOf(StorytellerPersonApiModel(id = 2, name = "Kate Reading")),
            series = listOf(
                StorytellerSeriesApiModel(
                    id = 7,
                    uuid = "series-1",
                    name = "The Stormlight Archive",
                    position = 3.0f,
                ),
            ),
            tags = listOf(StorytellerTagApiModel(id = 3, name = "fantasy")),
            ebook = StorytellerMediaFileApiModel(filepath = "oathbringer.epub", size = 1_234L),
            audiobook = StorytellerMediaFileApiModel(filepath = "oathbringer.m4b", size = 5_678L),
            readaloud = StorytellerReadaloudApiModel(filepath = "oathbringer_readaloud.epub"),
        )

        val fromRemote = apiModel.toDomain(serverId = "server-1", baseUrl = "http://example.com")
        // The cache source reads entities back with a null base URL and leaves the stored
        // cover URL in place, so mirror that exactly.
        val roundTripped = fromRemote.toEntity().toServerBook(baseUrl = null)

        assertEquals(fromRemote, roundTripped)
    }

    @Test
    fun publicationDateSurvivesTheCacheRoundTrip() {
        val apiModel = StorytellerBookApiModel(
            uuid = "book-2",
            title = "Edgedancer",
            publicationDate = "2016-11-22",
        )

        val fromRemote = apiModel.toDomain(serverId = "server-1", baseUrl = "http://example.com")
        val roundTripped = fromRemote.toEntity().toServerBook(baseUrl = null)

        assertEquals("2016-11-22", roundTripped.publicationDate)
    }

    /**
     * The cache queries order relations by name while the API returns its own order, so
     * relation order must not participate in equality or cachedRemoteFlow rewrites the
     * cache on every poll.
     */
    @Test
    fun relationOrderDoesNotAffectEquality() {
        val apiOrder = StorytellerBookApiModel(
            uuid = "book-3",
            title = "Memoirs of a Geisha",
            authors = listOf(
                StorytellerPersonApiModel(id = 1, name = "Arthur Golden"),
                StorytellerPersonApiModel(id = 2, name = "Aaron Smith"),
            ),
            series = listOf(
                StorytellerSeriesApiModel(id = 2, name = "Zeta Series", position = 2.0f),
                StorytellerSeriesApiModel(id = 1, name = "Alpha Series", position = 1.0f),
            ),
            tags = listOf(
                StorytellerTagApiModel(id = 1, name = "Romance"),
                StorytellerTagApiModel(id = 2, name = "Classics"),
                StorytellerTagApiModel(id = 3, name = "historical"),
            ),
        )
        val nameOrder = apiOrder.copy(
            authors = apiOrder.authors.sortedBy { it.name },
            series = apiOrder.series.sortedBy { it.name },
            tags = apiOrder.tags.sortedBy { it.name },
        )

        assertEquals(
            apiOrder.toDomain(serverId = "server-1", baseUrl = "http://example.com"),
            nameOrder.toDomain(serverId = "server-1", baseUrl = "http://example.com"),
        )
    }

    /**
     * Regression: an audiobook with a filepath but no size is reported as absent, yet used
     * to still carry an audiobook path. The cache drops the record entirely, so the two
     * representations never matched and cachedRemoteFlow rewrote the cache on every poll.
     */
    @Test
    fun audiobookWithoutSizeDoesNotCarryAnAudiobookPath() {
        val apiModel = StorytellerBookApiModel(
            uuid = "book-4",
            title = "Oathbringer",
            audiobook = StorytellerMediaFileApiModel(filepath = "oathbringer.m4b", size = null),
        )

        val fromRemote = apiModel.toDomain(serverId = "server-1", baseUrl = "http://example.com")
        val roundTripped = fromRemote.toEntity().toServerBook(baseUrl = null)

        assertEquals(false, fromRemote.hasAudiobook)
        assertEquals(null, fromRemote.audiobookFilepath)
        assertEquals(fromRemote, roundTripped)
    }
}
