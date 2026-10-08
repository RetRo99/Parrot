package com.retro99.opds.implementation.opds2

import com.retro99.opds.api.*
import com.retro99.opds.api.model.*
import com.retro99.opds.implementation.mediatype.SeparatedMediaTypeParser
import kotlinx.serialization.json.*

/** Shared JSON reader; URL expansion and acquisition policy are deliberately separate. */
internal class Opds2Parser(private val resolver: OpdsUrlResolver) : OpdsParser {
    override fun parse(payload: OpdsPayload, effectiveResponseUrl: String): OpdsParseResult {
        return try {
            val root = Json.parseToJsonElement(payload.asText()) as? JsonObject
                ?: return OpdsParseResult.Rejected(OpdsRejection.Malformed())
            OpdsParseResult.Document(Reader(effectiveResponseUrl).document(root))
        } catch (_: IllegalArgumentException) {
            OpdsParseResult.Rejected(OpdsRejection.Malformed("invalid OPDS JSON structure"))
        }
    }

    private inner class Reader(val base: String) {
        val warnings = mutableListOf<ParseWarning>()
        val types = SeparatedMediaTypeParser()
        fun text(value: JsonElement?): String? = when (value) {
            is JsonPrimitive -> value.takeIf { it.isString }?.content
            is JsonObject -> text(value["und"]) ?: value.values.firstNotNullOfOrNull { text(it) }
            else -> null
        }
        fun values(value: JsonElement?): List<JsonElement> = when (value) {
            null, JsonNull -> emptyList()
            is JsonArray -> value
            else -> listOf(value)
        }
        fun contributors(value: JsonElement?, role: String? = null): List<OpdsContributor> = values(value).mapNotNull {
            val obj = it as? JsonObject
            val name = text(obj?.get("name") ?: it) ?: return@mapNotNull null
            OpdsContributor(name, text(obj?.get("href")), text(obj?.get("role")) ?: role)
        }
        fun link(obj: JsonObject): OpdsLink {
            val href = text(obj["href"]) ?: throw IllegalArgumentException()
            val template = (obj["templated"] as? JsonPrimitive)?.booleanOrNull == true || '{' in href
            val relations = values(obj["rel"]).mapNotNull { text(it) }
            val resolved = if (template) null else try { resolver.resolve(base, href) } catch (_: IllegalArgumentException) {
                warnings += ParseWarning(ParseWarning.Code.UNRESOLVABLE_LINK)
                null
            }
            return OpdsLink(href, resolved, template, relations, types.parse(text(obj["type"])), text(obj["title"]),
                (obj["length"] as? JsonPrimitive)?.longOrNull)
        }
        fun links(value: JsonElement?): List<OpdsLink> = values(value).mapNotNull {
            try { link(it as? JsonObject ?: throw IllegalArgumentException()) } catch (_: IllegalArgumentException) {
                warnings += ParseWarning(ParseWarning.Code.MALFORMED_ITEM_SKIPPED)
                null
            }
        }
        fun publication(obj: JsonObject): OpdsEntry {
            val metadata = obj["metadata"] as? JsonObject ?: throw IllegalArgumentException()
            val title = text(metadata["title"]) ?: throw IllegalArgumentException()
            val links = links(obj["links"])
            val self = links.firstOrNull { "self" in it.relations }?.resolvedHref
            val identifier = text(metadata["identifier"])
            val identity = when {
                self != null -> OpdsIdentity(self, OpdsIdentity.Kind.NOMINAL)
                identifier != null -> OpdsIdentity("$base#$identifier", OpdsIdentity.Kind.PROVIDER_SCOPED_FALLBACK)
                else -> {
                    warnings += ParseWarning(ParseWarning.Code.MISSING_IDENTITY)
                    OpdsIdentity.documentScoped("$base:${obj.toString().encodeToByteArray().fold(1) { h, b -> 31 * h + b }}")
                }
            }
            val published = text(metadata["published"])
            return OpdsEntry(identity, title, updated = text(metadata["modified"]),
                authors = contributors(metadata["author"]),
                languages = values(metadata["language"]).mapNotNull { text(it) },
                content = text(metadata["description"])?.let { OpdsContent(OpdsContent.Format.HTML, it) },
                rights = text(metadata["rights"]), publisher = contributors(metadata["publisher"]).firstOrNull()?.name,
                published = published, year = published?.take(4),
                identifiers = identifier?.let { listOf(OpdsIdentifier(it)) }.orEmpty(),
                images = values(obj["images"]).mapNotNull {
                    val image = it as? JsonObject ?: return@mapNotNull null
                    val href = text(image["href"]) ?: return@mapNotNull null
                    OpdsImage(resolver.resolve(base, href), types.parse(text(image["type"])),
                        (image["width"] as? JsonPrimitive)?.intOrNull, (image["height"] as? JsonPrimitive)?.intOrNull)
                }, links = links, editionLabel = text(metadata["edition"]))
        }
        fun publications(value: JsonElement?): List<OpdsEntry> = values(value).mapNotNull {
            try { publication(it as? JsonObject ?: throw IllegalArgumentException()) } catch (_: IllegalArgumentException) {
                warnings += ParseWarning(ParseWarning.Code.MALFORMED_ITEM_SKIPPED)
                null
            }
        }
        fun navigation(value: JsonElement?): List<OpdsEntry> = links(value).map {
            OpdsEntry(OpdsIdentity(it.resolvedHref ?: it.rawHref, OpdsIdentity.Kind.NOMINAL), it.title.orEmpty(), links = listOf(it))
        }
        fun document(root: JsonObject): OpdsDocument {
            val metadata = root["metadata"] as? JsonObject ?: throw IllegalArgumentException()
            val title = text(metadata["title"]) ?: throw IllegalArgumentException()
            val links = links(root["links"])
            val self = links.firstOrNull { "self" in it.relations }
            val groups = values(root["groups"]).map { raw ->
                val group = raw as? JsonObject ?: throw IllegalArgumentException()
                OpdsGroup(text((group["metadata"] as? JsonObject)?.get("title")).orEmpty(), links(group["links"]),
                    publications(group["publications"]), navigation(group["navigation"]))
            }
            val facets = values(root["facets"]).map { raw ->
                val facet = raw as? JsonObject ?: throw IllegalArgumentException()
                OpdsFacetGroup(text((facet["metadata"] as? JsonObject)?.get("name")), values(facet["links"]).map {
                    val option = it as JsonObject
                    val link = link(option)
                    OpdsFacetOption(link.title, link, (option["active"] as? JsonPrimitive)?.booleanOrNull == true)
                })
            }
            return OpdsFeedDocument(OpdsFeedMetadata(title, text(metadata["identifier"])?.let { OpdsIdentity(it, OpdsIdentity.Kind.NOMINAL) },
                authors = contributors(metadata["attribution"] ?: metadata["author"]), language = text(metadata["language"]),
                rights = text(metadata["rights"]), modified = text(metadata["modified"])),
                navigation(root["navigation"]), publications(root["publications"]), groups, facets,
                OpdsPagination(links.firstOrNull { "first" in it.relations }, links.firstOrNull { "next" in it.relations },
                    links.firstOrNull { "previous" in it.relations || "prev" in it.relations }, links.firstOrNull { "last" in it.relations }),
                search = links.firstOrNull { "search" in it.relations }?.let { OpdsSearchOffer(it, OpdsSearchOffer.Kind.URI_TEMPLATE) },
                self = self, up = links.filter { "up" in it.relations || "start" in it.relations },
                effectiveResponseUrl = base, warnings = warnings.toList())
        }
    }
}
