package com.retro99.server.opds

import com.retro99.opds.api.OPDS_ACCEPT_MEDIA_TYPES
import com.retro99.opds.api.OpdsCacheEntry
import com.retro99.opds.api.OpdsCacheKey
import com.retro99.opds.api.OpdsPayload
import com.retro99.opds.api.model.OpdsBudgets
import io.ktor.http.HttpHeaders
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class SavedPagesFeedCacheTest {
    private var activeProfile: String? = "a"
    private val documents = MemoryDocuments { activeProfile }
    private val cache = SavedPagesFeedCache(documents.session, documents, maxBytes = 10, maxPageBytes = 6)

    private fun key(url: String = ROOT, source: String = "source", generation: Long = 0, profile: String = "a") =
        OpdsCacheKey(url, profile, source, generation, representation = OPDS_ACCEPT_MEDIA_TYPES.joinToString(", "))

    private fun page(bytes: Int, at: Long, cacheControl: List<String> = emptyList(), servedFrom: String = ROOT) = OpdsCacheEntry(
        body = OpdsPayload("application/opds+json", ByteArray(bytes) { 7 }),
        validators = mapOf(HttpHeaders.IfNoneMatch to "\"v1\"", HttpHeaders.IfModifiedSince to "Wed, 21 Oct 2015 07:28:00 GMT"),
        storedAtMillis = at,
        effectiveUrl = servedFrom,
        cacheControl = cacheControl,
    )

    @Test fun `a saved page comes back with its bytes - validators - time and the address it was served from`() = runTest {
        // Given
        cache.store(key("http://books.example/opds"), page(3, at = 42, servedFrom = ROOT))

        // When: a new cache over the same database, as after a restart
        val loaded = assertNotNull(SavedPagesFeedCache(documents.session, documents).load(key("http://books.example/opds")))

        // Then
        assertContentEquals(ByteArray(3) { 7 }, loaded.body.bytes)
        assertEquals("application/opds+json", loaded.body.mediaTypeHeader)
        assertEquals("\"v1\"", loaded.validators[HttpHeaders.IfNoneMatch])
        assertEquals("Wed, 21 Oct 2015 07:28:00 GMT", loaded.validators[HttpHeaders.IfModifiedSince])
        assertEquals(42L, loaded.storedAtMillis)
        assertEquals(ROOT, loaded.effectiveUrl)
    }

    @Test fun `a page is found only under the catalogue and access generation it was saved under`() = runTest {
        // Given a page fetched with account details, generation 4
        cache.store(key(generation = 4), page(1, at = 1))

        // Then
        assertNotNull(cache.load(key(generation = 4)))
        assertNull(cache.load(key(generation = 5)), "other account details")
        assertNull(cache.load(key(generation = 0)), "no account details")
        assertNull(cache.load(key(source = "other", generation = 4)), "another catalogue")
        assertNull(cache.load(key("$ROOT?page=2", generation = 4)), "another page")
        assertNull(cache.load(key(generation = 4).copy(representation = "application/opensearchdescription+xml")))
    }

    @Test fun `saving a page under new account details drops the catalogue's pages from the old ones`() = runTest {
        // Given
        cache.store(key(generation = 4), page(1, at = 1))
        cache.store(key("$ROOT/b", generation = 4), page(1, at = 2))
        cache.store(key(source = "other", generation = 4), page(1, at = 3))

        // When
        cache.store(key("$ROOT/c", generation = 5), page(1, at = 4))

        // Then
        assertEquals(listOf("other" to 4L, "source" to 5L), documents.peek("a").map { it.sourceId to it.accessGeneration })
    }

    @Test fun `the pages of a profile stay within the budget and the oldest go first`() = runTest {
        // Given: 10 bytes in all
        cache.store(key("$ROOT/1"), page(4, at = 1))
        cache.store(key("$ROOT/2"), page(4, at = 2))
        cache.store(key("$ROOT/1"), page(4, at = 3)) // opened again: now the newer of the two

        // When
        cache.store(key("$ROOT/3", source = "other"), page(4, at = 4))

        // Then
        assertEquals(listOf("$ROOT/1", "$ROOT/3"), documents.peek("a").map { it.requestUrl })
        assertEquals(8L, documents.peek("a").sumOf { it.sizeBytes })

        // And one page that needs the room of two takes it
        cache.store(key("$ROOT/4"), page(6, at = 5))
        assertEquals(listOf("$ROOT/3", "$ROOT/4"), documents.peek("a").map { it.requestUrl })
    }

    @Test fun `a page over the feed budget is never saved and takes its older copy with it`() = runTest {
        // Given
        cache.store(key(), page(2, at = 1))

        // When
        cache.store(key(), page(7, at = 2))

        // Then
        assertNull(cache.load(key()))
        assertEquals(emptyList(), documents.peek("a"))
        assertEquals(5L * 1024 * 1024, OpdsBudgets.MAX_RESPONSE_BYTES)
        assertEquals(25L * 1024 * 1024, OpdsBudgets.MAX_SAVED_PAGES_BYTES)
    }

    @Test fun `a no-store page is never saved and takes its older copy with it`() = runTest {
        // Given
        cache.store(key(), page(2, at = 1))

        // When
        cache.store(key(), page(2, at = 2, cacheControl = listOf("private", "NO-STORE")))

        // Then
        assertEquals(emptyList(), documents.peek("a"))
    }

    @Test fun `only the open profile is read or written`() = runTest {
        // Given a page saved by profile a
        cache.store(key(), page(2, at = 1))

        // When profile b is open
        activeProfile = "b"

        // Then a's page is not found, cannot be replaced or removed, and b saves its own
        assertNull(cache.load(key()))
        cache.store(key(), page(3, at = 2))
        cache.invalidate(key())
        cache.clearSource("a", "source")
        assertNull(cache.load(key(profile = "b")))
        cache.store(key(profile = "b"), page(1, at = 3))
        assertEquals(listOf(2L), documents.peek("a").map { it.sizeBytes })
        assertEquals(listOf(1L), documents.peek("b").map { it.sizeBytes })
    }

    @Test fun `clearing a catalogue removes its pages under every account and no other catalogue's`() = runTest {
        // Given
        cache.store(key(generation = 1), page(1, at = 1))
        cache.store(key(source = "other"), page(1, at = 2))

        // When
        cache.clearAll()
        cache.clearSource("a", "source")

        // Then: ending a session clears nothing; clearing the catalogue clears only it
        assertEquals(listOf("other"), documents.peek("a").map { it.sourceId })
    }

    private companion object {
        const val ROOT = "https://books.example/opds/"
    }
}
