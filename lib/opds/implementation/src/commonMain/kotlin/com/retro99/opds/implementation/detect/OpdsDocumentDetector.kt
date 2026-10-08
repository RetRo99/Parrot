package com.retro99.opds.implementation.detect

import com.retro99.opds.api.OpdsContentType
import com.retro99.opds.api.OpdsPayload
import com.retro99.opds.api.UnsupportedOpdsEncodingException
import com.retro99.opds.api.model.OpdsBudgets
import com.retro99.opds.api.model.OpdsMediaType
import com.retro99.opds.api.model.OpdsRejection
import com.retro99.opds.implementation.mediatype.SeparatedMediaTypeParser
import com.retro99.opds.implementation.opds2.JsonDepthGuard
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import nl.adaptivity.xmlutil.XmlReader
import nl.adaptivity.xmlutil.xmlStreaming

/**
 * Document detection (plan §4): declared media types classify first; generic
 * XML/JSON types are classified by bounded structure; HTML/RSS error pages
 * are rejected; a DOCTYPE is rejected before any element content is read
 * (Phase 0 verified that xmlutil accepts internal-DTD documents and resolves
 * internal entities, so detection must gate this).
 */
class OpdsDocumentDetector(
    private val mediaTypeParser: SeparatedMediaTypeParser = SeparatedMediaTypeParser(),
) {

    fun detect(mediaTypeHeader: String?, body: ByteArray): OpdsContentType =
        detectBody(OpdsPayload(mediaTypeHeader, body))

    fun detectBody(payload: OpdsPayload): OpdsContentType {
        if (payload.bytes.size > OpdsBudgets.MAX_RESPONSE_BYTES) {
            return OpdsContentType.Rejected(OpdsRejection.TooLarge())
        }
        try {
            payload.asText()
        } catch (_: UnsupportedOpdsEncodingException) {
            return OpdsContentType.Rejected(OpdsRejection.UnsupportedEncoding())
        } catch (_: IllegalArgumentException) {
            return OpdsContentType.Rejected(OpdsRejection.Malformed("invalid encoded text"))
        }
        val mediaType = mediaTypeParser.parse(payload.mediaTypeHeader)
        if (mediaType != null) {
            when {
                mediaType.mainType == "text" && mediaType.subType == "html" ->
                    return OpdsContentType.NotACatalogue

                mediaType.isXmlFamily() &&
                    mediaType.parameter("profile")?.contains("opds-catalog", ignoreCase = true) == true ->
                    return byDeclaredKind(mediaType)

                mediaType.mainType == "application" && mediaType.subType == "opds+json" ->
                    return OpdsContentType.FEED

                mediaType.mainType == "application" && mediaType.subType == "opds-publication+json" ->
                    return OpdsContentType.PUBLICATION

                mediaType.mainType == "application" && mediaType.subType == "opensearchdescription+xml" ->
                    return OpdsContentType.OPEN_SEARCH_DESCRIPTION

                // Generic JSON/XML and plain Atom XML: structure decides.
                else -> {}
            }
        }
        return structuralProbe(payload, mediaType)
    }

    private fun structuralProbe(payload: OpdsPayload, mediaType: OpdsMediaType?): OpdsContentType {
        val text = payload.asText()
        return when {
            text.startsLikeJson() || (mediaType?.isJsonFamily() == true) -> jsonProbe(text)
            text.startsLikeXml() || mediaType?.isXmlFamily() == true -> xmlProbe(text)
            else -> OpdsContentType.NotACatalogue
        }
    }

    private fun xmlProbe(text: String): OpdsContentType {
        val reader: XmlReader = try {
            xmlStreaming.newReader(text, false)
        } catch (readerError: Exception) {
            return mapReaderError(readerError)
        }
        while (reader.hasNext()) {
            val typeName = try {
                reader.next().name
            } catch (readerError: Exception) {
                return mapReaderError(readerError)
            }
            when (typeName) {
                // xmlutil delivers the declaration event before the document
                // element; any DOCTYPE at all is rejected here before any
                // entity content is produced (Phase 0 record).
                "DOCDECL", "DTD" -> return OpdsContentType.Rejected(OpdsRejection.DocumentTypeDeclarationRejected())
                "START_ELEMENT" -> {
                    return when (reader.localName.lowercaseAscii()) {
                        "feed" -> OpdsContentType.FEED
                        "entry" -> OpdsContentType.PUBLICATION
                        "opensearchdescription" -> OpdsContentType.OPEN_SEARCH_DESCRIPTION
                        else -> OpdsContentType.NotACatalogue // html, rss, everything else
                    }
                }
            }
        }
        return OpdsContentType.Rejected(OpdsRejection.Malformed())
    }

    /**
     * xmlutil's own DOCTYPE parser refuses external `SYSTEM` entities before
     * any DOCDECL event or content (Phase 0 record); those failures classify
     * as the same DTD rejection. Everything else is malformed input.
     */
    private fun mapReaderError(error: Exception): OpdsContentType {
        val why = error.message?.lowercaseAscii().orEmpty()
        return if (why.contains("document type declaration") || why.contains("doctype")) {
            OpdsContentType.Rejected(OpdsRejection.DocumentTypeDeclarationRejected())
        } else {
            OpdsContentType.Rejected(OpdsRejection.Malformed(why.take(120)))
        }
    }

    private fun jsonProbe(text: String): OpdsContentType {
        if (!text.startsWithCharacter('{')) return OpdsContentType.Rejected(OpdsRejection.NotACatalogue())
        JsonDepthGuard.rejection(text)?.let { return OpdsContentType.Rejected(it) }
        val keys = try {
            (Json.parseToJsonElement(text) as? JsonObject)?.keys ?: return OpdsContentType.NotACatalogue
        } catch (_: IllegalArgumentException) {
            return OpdsContentType.Rejected(OpdsRejection.Malformed("invalid JSON structure"))
        }
        return when {
            "metadata" in keys && ("navigation" in keys || "publications" in keys || "groups" in keys) ->
                OpdsContentType.FEED

            "metadata" in keys && ("links" in keys || "images" in keys || "manifestUrl" in keys) ->
                OpdsContentType.PUBLICATION

            else -> OpdsContentType.NotACatalogue
        }
    }

}

private fun byDeclaredKind(mediaType: OpdsMediaType): OpdsContentType =
    when (mediaType.kindParameter?.lowercaseAscii()) {
        "publication", "acquisition" -> OpdsContentType.PUBLICATION
        else -> OpdsContentType.FEED
    }

private fun String.startsLikeJson(): Boolean {
    val trimmed = trim()
    return trimmed.startsWith("{")
}

private fun String.startsLikeXml(): Boolean = trim().startsWith("<")

private fun String.startsWithCharacter(c: Char): Boolean = trim().startsWith(c)

private fun String.lowercaseAscii(): String = lowercase()
