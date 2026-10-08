package com.retro99.opds.implementation.opds2

import com.retro99.opds.api.*
import com.retro99.opds.api.model.*
import com.retro99.opds.implementation.mediatype.SeparatedMediaTypeParser
import kotlinx.serialization.json.*

/** Shared JSON reader; URL expansion and acquisition policy are deliberately separate. */
internal class Opds2Parser(private val resolver: OpdsUrlResolver) : OpdsParser {
    override fun parse(payload: OpdsPayload, effectiveResponseUrl: String): OpdsParseResult {
        if (payload.bytes.size > OpdsBudgets.MAX_RESPONSE_BYTES) return OpdsParseResult.Rejected(OpdsRejection.TooLarge())
        return try {
            val text = payload.asText()
            JsonDepthGuard.rejection(text)?.let { return OpdsParseResult.Rejected(it) }
            val root = Json.parseToJsonElement(text) as? JsonObject
                ?: return OpdsParseResult.Rejected(OpdsRejection.Malformed())
            for (key in listOf("publications", "navigation", "groups", "facets", "links", "images")) {
                if (key in root && root[key] !is JsonArray) throw IllegalArgumentException()
            }
            fun countItems(obj: JsonObject): Int {
                var count = 0
                for (key in listOf("publications", "navigation")) {
                    val value = obj[key] ?: continue
                    count += (value as? JsonArray)?.size ?: 0
                }
                for (group in (obj["groups"] as? JsonArray).orEmpty()) {
                    if (group is JsonObject) count += countItems(group)
                }
                return count
            }
            if (countItems(root) > OpdsBudgets.MAX_ITEMS_PER_RESPONSE) return OpdsParseResult.Rejected(OpdsRejection.TooManyItems())
            OpdsParseResult.Document(Reader(effectiveResponseUrl).document(root))
        } catch (_: UnsupportedOpdsEncodingException) {
            OpdsParseResult.Rejected(OpdsRejection.UnsupportedEncoding())
        } catch (_: IllegalArgumentException) {
            OpdsParseResult.Rejected(OpdsRejection.Malformed("invalid OPDS JSON structure"))
        }
    }

    private inner class Reader(val base: String) {
        val warnings = mutableListOf<ParseWarning>()
        val types = SeparatedMediaTypeParser()
        val fallbackOccurrences = mutableMapOf<String, Int>()
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
        fun <T> items(value: JsonElement?, read: (JsonObject) -> T): List<T> {
            if (value == null) return emptyList()
            val array = value as? JsonArray ?: throw IllegalArgumentException()
            return array.mapNotNull {
                try { read(it as? JsonObject ?: throw IllegalArgumentException()) } catch (_: IllegalArgumentException) {
                    warnings += ParseWarning(ParseWarning.Code.MALFORMED_ITEM_SKIPPED)
                    null
                }
            }
        }
        fun contributors(value: JsonElement?, role: String? = null): List<OpdsContributor> = values(value).mapNotNull {
            val obj = it as? JsonObject
            val name = text(obj?.get("name") ?: it) ?: run {
                warnings += ParseWarning(ParseWarning.Code.MALFORMED_ITEM_SKIPPED)
                return@mapNotNull null
            }
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
            val properties = obj["properties"] as? JsonObject
            val price = properties?.get("price") as? JsonObject
            val priceValue = (price?.get("value") as? JsonPrimitive)?.doubleOrNull
                ?: (properties?.get("priceValue") as? JsonPrimitive)?.doubleOrNull
            val currency = text(price?.get("currency") ?: properties?.get("currency"))
            val trees = values(properties?.get("indirectAcquisition")).map { indirect(it as? JsonObject ?: throw IllegalArgumentException()) }
            return OpdsLink(href, resolved, template, relations, types.parse(text(obj["type"])), text(obj["title"]),
                (obj["length"] as? JsonPrimitive)?.longOrNull,
                price = if (priceValue != null && currency != null) OpdsPrice(priceValue, currency) else null,
                indirectAcquisition = when (trees.size) { 0 -> null; 1 -> trees.single(); else -> OpdsIndirectAcquisition(null, trees) })
        }
        fun indirect(obj: JsonObject): OpdsIndirectAcquisition = OpdsIndirectAcquisition(types.parse(text(obj["type"])),
            values(obj["child"] ?: obj["children"]).map { indirect(it as? JsonObject ?: throw IllegalArgumentException()) })
        fun links(value: JsonElement?): List<OpdsLink> = items(value, ::link)
        fun publication(obj: JsonObject): OpdsEntry {
            val metadata = obj["metadata"] as? JsonObject ?: throw IllegalArgumentException()
            val title = text(metadata["title"]) ?: throw IllegalArgumentException()
            val links = links(obj["links"])
            val self = links.firstOrNull { "self" in it.relations }?.resolvedHref
            val identifier = text(metadata["identifier"])
            val identity = when {
                self != null -> OpdsIdentity(self, OpdsIdentity.Kind.NOMINAL)
                identifier != null -> OpdsIdentity("${resolver.resolve(base, "/")}#$identifier", OpdsIdentity.Kind.PROVIDER_SCOPED_FALLBACK,
                    "identifier scoped to response origin")
                else -> {
                    warnings += ParseWarning(ParseWarning.Code.MISSING_IDENTITY)
                    // Content fingerprint plus occurrence disambiguates identical anonymous
                    // records. This is only a document-local key, never a stable book ID.
                    val fingerprint = obj.toString().encodeToByteArray().fold(-3750763034362895579L) { h, b ->
                        (h xor (b.toLong() and 255)) * 1099511628211L
                    }.toULong().toString(16)
                    val occurrence = fallbackOccurrences[fingerprint] ?: 0
                    fallbackOccurrences[fingerprint] = occurrence + 1
                    OpdsIdentity.documentScoped("$base:$fingerprint:$occurrence", "no declared identity; document-local only")
                }
            }
            val published = text(metadata["published"])
            return OpdsEntry(identity, title, updated = text(metadata["modified"]),
                authors = contributors(metadata["author"]),
                otherContributors = listOf("translator", "editor", "illustrator", "contributor", "narrator").flatMap { contributors(metadata[it], it) },
                languages = values(metadata["language"]).mapNotNull { text(it) },
                content = text(metadata["description"])?.let { OpdsContent(OpdsContent.Format.HTML, it) },
                rights = text(metadata["rights"]), publisher = contributors(metadata["publisher"]).firstOrNull()?.name,
                published = published, year = published?.take(4),
                identifiers = identifier?.let { listOf(OpdsIdentifier(it)) }.orEmpty(),
                images = items(obj["images"]) { image ->
                    val href = text(image["href"]) ?: throw IllegalArgumentException()
                    OpdsImage(resolver.resolve(base, href), types.parse(text(image["type"])),
                        (image["width"] as? JsonPrimitive)?.intOrNull, (image["height"] as? JsonPrimitive)?.intOrNull)
                }, links = links, editionLabel = text(metadata["edition"]))
        }
        fun publications(value: JsonElement?): List<OpdsEntry> = items(value, ::publication)
        fun navigation(value: JsonElement?): List<OpdsEntry> = links(value).map {
            OpdsEntry(OpdsIdentity(it.resolvedHref ?: it.rawHref, OpdsIdentity.Kind.NOMINAL), it.title.orEmpty(), links = listOf(it))
        }
        fun document(root: JsonObject): OpdsDocument {
            val metadata = root["metadata"] as? JsonObject ?: throw IllegalArgumentException()
            val title = text(metadata["title"]) ?: throw IllegalArgumentException()
            val links = links(root["links"])
            val self = links.firstOrNull { "self" in it.relations }
            if (listOf("navigation", "publications", "groups", "facets").none { it in root }) {
                return OpdsPublicationDocument(publication(root), self, base, warnings = warnings.toList())
            }
            val groups = items(root["groups"]) { group ->
                OpdsGroup(text((group["metadata"] as? JsonObject)?.get("title")) ?: throw IllegalArgumentException(), links(group["links"]),
                    publications(group["publications"]), navigation(group["navigation"]))
            }
            val facets = items(root["facets"]) { facet ->
                val facetMetadata = facet["metadata"] as? JsonObject ?: throw IllegalArgumentException()
                val options = items(facet["links"]) { option ->
                    val link = link(option)
                    val properties = option["properties"] as? JsonObject
                    OpdsFacetOption(link.title, link, (properties?.get("active") as? JsonPrimitive)?.booleanOrNull
                        ?: ((option["active"] as? JsonPrimitive)?.booleanOrNull == true),
                        (properties?.get("numberOfItems") as? JsonPrimitive)?.longOrNull)
                }
                OpdsFacetGroup(text(facetMetadata["title"] ?: facetMetadata["name"]), options,
                    options.firstOrNull { "all" in it.link.relations || "http://opds-spec.org/facet/all" in it.link.relations })
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

/** Check structural depth before building a JSON tree, including unknown extensions. */
internal object JsonDepthGuard {
    fun rejection(text: String): OpdsRejection? {
        var depth = 0
        var quoted = false
        var escaped = false
        for (c in text) {
            if (quoted) {
                if (escaped) escaped = false
                else if (c == '\\') escaped = true
                else if (c == '"') quoted = false
            } else when (c) {
                '"' -> quoted = true
                '{', '[' -> if (++depth > OpdsBudgets.MAX_NESTING_DEPTH) return OpdsRejection.TooDeep()
                '}', ']' -> depth--
            }
        }
        return null
    }
}
