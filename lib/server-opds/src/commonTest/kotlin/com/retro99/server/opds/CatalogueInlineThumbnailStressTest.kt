package com.retro99.server.opds

import com.github.michaelbull.result.get
import com.github.michaelbull.result.getError
import com.retro99.base.result.AppError
import com.retro99.opds.api.model.OpdsBudgets
import com.retro99.opds.implementation.transport.KtorOpdsTransport
import com.retro99.server.api.*
import com.retro99.server.implementation.CatalogueAccessStoreImpl
import com.retro99.server.implementation.OpdsCredentialStoreImpl
import io.ktor.client.engine.mock.*
import io.ktor.http.*
import kotlinx.coroutines.test.runTest
import kotlin.io.encoding.Base64
import kotlin.test.*

/** Pages that carry their pictures inside them, at the sizes where the limits meet. */
class CatalogueInlineThumbnailStressTest {
    private val pngStart = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A)
    private var requests = 0

    private fun png(size: Int) = pngStart + ByteArray(size - pngStart.size) { (it % 200).toByte() }
    private fun dataUri(bytes: ByteArray) = "data:image/png;base64," + Base64.Default.encode(bytes)

    private fun page(thumbnails: List<String>) = buildString {
        append("<feed xmlns=\"http://www.w3.org/2005/Atom\"><id>urn:feed</id><title>Pictures</title>")
        thumbnails.forEachIndexed { index, uri ->
            append("<entry><id>urn:e:$index</id><title>t$index</title><link rel=\"http://opds-spec.org/acquisition\" type=\"application/epub+zip\" href=\"/b/$index.epub\"/>")
            append("<link rel=\"http://opds-spec.org/image/thumbnail\" type=\"image/png\" href=\"$uri\"/></entry>")
        }
        append("</feed>")
    }

    private fun repository(body: String): OpdsCatalogueRepository {
        val preferences = TestPreferences()
        val engine = MockEngine { requests++; respond(body, headers = headersOf(HttpHeaders.ContentType, "application/atom+xml")) }
        return OpdsCatalogueRepository("a", ServerConfig("source", "Books", ServerType.Opds, ROOT, 0), KtorOpdsTransport(engine, ROOT),
            OpdsCredentialStoreImpl(preferences), CatalogueAccessStoreImpl(preferences), { true }, { 10L })
    }

    @Test fun two_thousand_small_inline_thumbnails_are_each_decoded_without_a_request() = runTest {
        val uri = dataUri(png(1_500))
        val body = page(List(OpdsBudgets.MAX_ITEMS_PER_RESPONSE) { uri })
        assertTrue(body.length < OpdsBudgets.MAX_RESPONSE_BYTES)

        val feed = assertIs<CatalogueFeedDocument>(repository(body).getRoot().get())

        val models = feed.publications.map { CatalogueImageModel("source", it.images.single().href) }
        assertEquals(OpdsBudgets.MAX_ITEMS_PER_RESPONSE, models.size)
        assertTrue(models.all { it.isInline })
        assertTrue(models.all { decodeCatalogueDataImage(it.url)?.size == 1_500 })
        assertEquals(1, requests, "the page, and nothing for its pictures")
        // What the page model keeps for pictures is what the page carried.
        assertTrue(models.sumOf { it.url.length.toLong() } <= body.length)
    }

    @Test fun as_many_thumbnails_at_the_inline_ceiling_as_fit_in_one_page_are_all_decoded() = runTest {
        val atCeiling = dataUri(png(CatalogueImageLimits.MAX_INLINE_IMAGE_BYTES))
        val fitting = ((OpdsBudgets.MAX_RESPONSE_BYTES - 1_000) / (atCeiling.length + 300)).toInt()
        assertTrue(fitting >= 10, "fitting: $fitting")
        val body = page(List(fitting) { atCeiling })
        assertTrue(body.length <= OpdsBudgets.MAX_RESPONSE_BYTES)

        val feed = assertIs<CatalogueFeedDocument>(repository(body).getRoot().get())

        val decoded = feed.publications.map { decodeCatalogueDataImage(it.images.single().href) }
        assertEquals(fitting, decoded.size)
        assertTrue(decoded.all { it?.size == CatalogueImageLimits.MAX_INLINE_IMAGE_BYTES })
    }

    @Test fun a_thumbnail_one_byte_over_the_inline_ceiling_is_not_decoded_and_the_rest_of_the_page_is_fine() = runTest {
        val over = dataUri(png(CatalogueImageLimits.MAX_INLINE_IMAGE_BYTES + 1))
        val fine = dataUri(png(2_000))

        val feed = assertIs<CatalogueFeedDocument>(repository(page(listOf(over, fine))).getRoot().get())

        assertNull(decodeCatalogueDataImage(feed.publications[0].images.single().href))
        assertEquals(2_000, decodeCatalogueDataImage(feed.publications[1].images.single().href)?.size)
    }

    @Test fun a_page_whose_thumbnails_push_it_over_the_page_budget_is_refused_as_too_large() = runTest {
        val atCeiling = dataUri(png(CatalogueImageLimits.MAX_INLINE_IMAGE_BYTES))
        val tooMany = (OpdsBudgets.MAX_RESPONSE_BYTES / atCeiling.length).toInt() + 1
        val body = page(List(tooMany) { atCeiling })
        assertTrue(body.length > OpdsBudgets.MAX_RESPONSE_BYTES)

        val error = repository(body).getRoot().getError()

        assertEquals(CatalogueErrorKind.TooLarge.name, assertIs<AppError.ApiError>(error).message)
    }

    @Test fun inline_pictures_that_are_not_pictures_are_refused_whatever_they_claim() = runTest {
        val svg = "data:image/svg+xml;base64," + Base64.Default.encode("<svg xmlns=\"http://www.w3.org/2000/svg\"><script>alert(1)</script></svg>".encodeToByteArray())
        val html = "data:image/png;base64," + Base64.Default.encode("<html><script>alert(1)</script></html>".encodeToByteArray())
        val unencoded = "data:image/png,%89PNG"
        val broken = "data:image/png;base64,!!!!not-base64!!!!"
        val wrongLabel = "data:image/jpeg;base64," + Base64.Default.encode(png(100))

        val feed = assertIs<CatalogueFeedDocument>(repository(page(listOf(svg, html, unencoded, broken, wrongLabel))).getRoot().get())

        feed.publications.forEach { publication -> assertNull(decodeCatalogueDataImage(publication.images.single().href)) }
    }

    private companion object {
        const val ROOT = "https://books.example/opds/"
    }
}
