package com.retro99.catalogue.ui.browse

import com.retro99.opds.api.*
import com.retro99.opds.api.model.*
import com.retro99.opds.implementation.ParserFactory
import com.retro99.server.api.*
import kotlinx.coroutines.test.*
import kotlin.test.*

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class CapturedLinkFeedsTest {
    /** Only the fields the row rule reads; the production parser reads the untouched wire capture. */
    private fun captured(name: String, url: String): CatalogueFeedDocument {
        val parsed = assertIs<OpdsParseResult.Document>(ParserFactory.opdsParser().parse(OpdsPayload("application/atom+xml;profile=opds-catalog", CapturedLinkFeeds.xml.getValue(name).encodeToByteArray()), url))
        val doc = assertIs<OpdsFeedDocument>(parsed.document)
        fun entry(e: OpdsEntry): CataloguePublication = folder(e.title.translations.values.first(), e.identity.raw).copy(
            identity = CatalogueIdentity(e.identity.raw, CatalogueIdentity.Scope.Nominal),
            content = e.content?.let { CatalogueDescription(CatalogueDescription.Format.Text, CatalogueText(it.body.translations)) },
            summary = e.summary?.let { CatalogueText(it.translations) },
            links = e.links.filter { it.resolvedHref != null }.map { link("target", it.resolvedHref!!) },
            images = e.images.map { CatalogueImage(it.href, null, null, null) },
        )
        return feed(name, folders = doc.navigation.map(::entry), next = doc.pagination.next?.resolvedHref, first = doc.pagination.first?.resolvedHref, search = doc.search != null,
            responseUrl = url).copy(pagination = CataloguePagination(doc.pagination.first?.let { link("first", it.resolvedHref!!) }, doc.pagination.next?.let { link("next", it.resolvedHref!!) }, doc.pagination.previous?.let { link("prev", it.resolvedHref!!) }, doc.pagination.last?.let { link("last", it.resolvedHref!!) }))
    }

    @Test fun realFirstPageListAndSearchRenderBookRowsOnlyWithTheFlag() = runTest {
        val cases = listOf(
            Triple("first-page", "https://www.gutenberg.org/ebooks.opds/", false),
            Triple("list", "https://www.gutenberg.org/ebooks/search.opds/", false),
            Triple("search", "https://www.gutenberg.org/ebooks/search.opds/?query=whale", true),
        )
        for ((name, url, search) in cases) {
            val feed = captured(name, url)
            val linked = linkedBookEntries(true, feed, search)
            assertEquals(if (name == "first-page") 0 else 25, linked.size, name)
            assertTrue(linkedBookEntries(false, feed, search).isEmpty())
            if (name == "first-page") assertEquals(listOf("Popular", "Latest", "Random"), feed.navigation.map { it.displayTitle() })
            else {
                assertTrue(CapturedLinkFeeds.xml.getValue(name).contains("data:image/png;base64,"), "wire capture includes generic thumbnails")
                val gateway = FakeGateway().also { it.source.value = it.source.value!!.copy(listEntriesAreBooks = true) }
                val library = FakeLibrary(setOf(linked.first().publicationKey))
                val browser = CatalogueBrowser(SOURCE, com.retro99.catalogue.ui.navigation.CataloguePlace(FakeTarget(name)), gateway, library, backgroundScope)
                runCurrent()
                // Search fixtures include paging, so they are also recognized without a route hint.
                gateway.repository.answer(name, feed); runCurrent()
                val rows = assertIs<CatalogueBrowseContent.Loaded>(browser.state.value.content).books
                assertEquals(25, rows.size)
                assertNull(rows.first().cover, "generic image must be ignored")
                assertEquals(linked.first().content!!.body.display(), rows.first().author)
                assertTrue(rows.first().inLibrary)
            }
        }
    }
}
