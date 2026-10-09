package com.retro99.server.opds

import com.retro99.opds.api.model.*
import com.retro99.server.api.*
import kotlin.test.*

class OpdsCatalogueMapperTest {
    @Test fun generic_models_retain_protocol_metadata_topology_and_all_acquisition_choices() {
        val base = "https://books.example/declaring/"
        val type = OpdsMediaType("application", "epub+zip", mapOf("profile" to "custom"))
        val link = OpdsLink("book", "$base/book", relations = listOf("download"), mediaType = type,
            title = OpdsText("Variant"), lengthBytes = 123, price = OpdsPrice(2.0, "EUR"),
            indirectAcquisition = OpdsIndirectAcquisition(type, listOf(OpdsIndirectAcquisition(type, emptyList()))),
            extras = mapOf("extension" to "kept"), effectiveBaseUri = base)
        val text = OpdsText(mapOf("en" to "Title", "sl" to "Naslov"))
        val contributor = OpdsContributor(text, "$base/author", "translator")
        val entry = OpdsEntry(OpdsIdentity("urn:book", OpdsIdentity.Kind.PROVIDER_SCOPED_FALLBACK, "fallback"), text,
            updated = "updated", authors = listOf(contributor), otherContributors = listOf(contributor), languages = listOf("en", "sl"),
            summary = text, content = OpdsContent(OpdsContent.Format.XHTML, text), rights = text, publisher = text,
            published = "published", year = "2026", identifiers = listOf(OpdsIdentifier("isbn", "ISBN")),
            images = listOf(OpdsImage("$base/image", type, 10, 20)), links = listOf(link, link.copy(rawHref = "second")),
            editionLabel = text, seller = text, lender = text)
        val feed = OpdsFeedDocument(OpdsFeedMetadata(text, entry.identity, "updated", listOf(contributor), "sl", text, "modified"),
            navigation = listOf(entry), publications = listOf(entry),
            groups = listOf(OpdsGroup(text, listOf(link), listOf(entry), listOf(entry))),
            facets = listOf(OpdsFacetGroup(text, listOf(OpdsFacetOption(text, link, true, 3)), OpdsFacetOption(text, link))),
            pagination = OpdsPagination(link, link, link, link), self = link, up = listOf(link),
            effectiveResponseUrl = "https://books.example/response/", referencedBy = link,
            warnings = listOf(ParseWarning(ParseWarning.Code.MISSING_IDENTITY, "publication")))
        val mapped = assertIs<CatalogueFeedDocument>(OpdsCatalogueMapper { object : CatalogueTarget {} }.map(feed, CatalogueFetchStatus(10, false, false)))
        assertEquals(text.translations, mapped.metadata.title.translations)
        assertEquals("modified", mapped.metadata.modified)
        assertEquals("updated", mapped.metadata.updated)
        assertEquals("sl", mapped.metadata.language)
        assertEquals(entry.identity.raw, mapped.metadata.identifier?.value)
        assertEquals(1, mapped.metadata.authors.size)
        assertEquals(text.translations, mapped.metadata.rights?.translations)
        val book = mapped.publications.single()
        assertEquals(CatalogueIdentity.Scope.Provider, book.identity.scope)
        assertEquals("fallback", book.identity.note)
        assertEquals(text.translations, book.title.translations)
        assertEquals("updated", book.updated)
        assertEquals("translator", book.authors.single().role)
        assertEquals(contributor.href, book.otherContributors.single().href)
        assertEquals(entry.languages, book.languages)
        assertEquals(text.translations, book.summary?.translations)
        assertEquals(CatalogueDescription.Format.Xhtml, book.content?.format)
        assertEquals(text.translations, book.content?.body?.translations)
        assertEquals(text.translations, book.rights?.translations)
        assertEquals(text.translations, book.publisher?.translations)
        assertEquals("published", book.published)
        assertEquals("2026", book.year)
        assertEquals("ISBN", book.identifiers.single().scheme)
        assertEquals("isbn", book.identifiers.single().value)
        assertEquals(CatalogueImage("$base/image", CatalogueMediaType("application", "epub+zip", mapOf("profile" to "custom")), 10, 20), book.images.single())
        assertEquals(text.translations, book.editionLabel?.translations)
        assertEquals(text.translations, book.seller?.translations)
        assertEquals(text.translations, book.lender?.translations)
        val choice = book.acquisitionChoices.first()
        assertEquals(2, book.acquisitionChoices.size)
        assertFalse(choice.isOpenable, "Indirect EPUB trees are not direct downloads")
        assertEquals(base, choice.link.effectiveBaseUri)
        assertEquals(link.rawHref, choice.link.rawHref)
        assertEquals(link.resolvedHref, choice.link.resolvedHref)
        assertEquals(link.relations, choice.link.relations)
        assertEquals(link.extras, choice.link.extras)
        assertEquals(123L, choice.link.lengthBytes)
        assertEquals(CataloguePrice(2.0, "EUR"), choice.link.price)
        assertEquals(1, choice.link.indirectAcquisition?.children?.size)
        assertEquals("custom", choice.link.mediaType?.parameters?.get("profile"))
        assertEquals(link.title?.translations, choice.link.title?.translations)
        assertEquals(1, mapped.groups.single().publications.size)
        assertEquals(1, mapped.groups.single().navigation.size)
        assertEquals(1, mapped.groups.single().links.size)
        assertEquals(text.translations, mapped.groups.single().title.translations)
        assertEquals(text.translations, mapped.facets.single().name?.translations)
        assertTrue(mapped.facets.single().options.single().active)
        assertEquals(3L, mapped.facets.single().options.single().count)
        assertNotNull(mapped.facets.single().allOption)
        assertNotNull(mapped.pagination.first)
        assertNotNull(mapped.pagination.next)
        assertNotNull(mapped.pagination.previous)
        assertNotNull(mapped.pagination.last)
        assertNotNull(mapped.self)
        assertNotNull(mapped.referencedBy)
        assertEquals(1, mapped.up.size)
        assertEquals(feed.effectiveResponseUrl, mapped.responseUrl)
        assertEquals(CatalogueWarning.Kind.MissingIdentity, mapped.warnings.single().kind)
        assertEquals("publication", mapped.warnings.single().elementPath)
    }
}
