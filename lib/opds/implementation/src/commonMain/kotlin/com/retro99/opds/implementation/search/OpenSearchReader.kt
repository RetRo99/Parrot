package com.retro99.opds.implementation.search

import com.retro99.opds.api.*
import com.retro99.opds.api.model.OpdsBudgets
import com.retro99.opds.api.model.OpdsMediaType
import com.retro99.opds.api.model.OpdsRejection
import com.retro99.opds.implementation.mediatype.SeparatedMediaTypeParser
import nl.adaptivity.xmlutil.xmlStreaming

/** Namespace-aware OpenSearch 1.1 description reader and scalar parameter expansion. */
class OpenSearchReader(private val resolver: OpdsUrlResolver) : OpdsOpenSearchReader {
    override fun readDescriptor(payload: OpdsPayload, effectiveResponseUrl: String): OpdsOpenSearchReader.OpdsDescriptorResult {
        fun rejected(rejection: OpdsRejection) = OpdsOpenSearchReader.OpdsDescriptorResult.NotADescriptor(rejection)
        if (payload.bytes.size > OpdsBudgets.MAX_RESPONSE_BYTES) return rejected(OpdsRejection.TooLarge())
        var reader: nl.adaptivity.xmlutil.XmlReader? = null
        return try {
            val xml = xmlStreaming.newReader(payload.asText(), false)
            reader = xml
            val bases = mutableListOf(effectiveResponseUrl)
            val templates = mutableListOf<OpdsSearchTemplate>()
            val inputs = mutableListOf<String>()
            val outputs = mutableListOf<String>()
            val encodingText = StringBuilder()
            var encodingElement: String? = null
            var depth = 0
            var sawRoot = false
            var closedRoot = false
            var urlCount = 0
            val mediaTypes = SeparatedMediaTypeParser()
            while (xml.hasNext()) {
                when (xml.next().name) {
                    "DOCDECL", "DTD" -> return rejected(OpdsRejection.DocumentTypeDeclarationRejected())
                    "START_ELEMENT" -> {
                        if (++depth > OpdsBudgets.MAX_NESTING_DEPTH) return rejected(OpdsRejection.TooDeep())
                        if (depth == 1) {
                            if (sawRoot || xml.localName != "OpenSearchDescription" || xml.namespaceURI != NAMESPACE) {
                                return rejected(OpdsRejection.NotACatalogue("not an OpenSearch 1.1 description"))
                            }
                            sawRoot = true
                        }
                        val attrs = mutableMapOf<String, String>()
                        for (i in 0 until xml.attributeCount) {
                            val name = if (xml.getAttributeNamespace(i) == XML_NAMESPACE) "xml:${xml.getAttributeLocalName(i)}" else xml.getAttributeLocalName(i)
                            attrs[name] = xml.getAttributeValue(i).orEmpty()
                        }
                        bases.add(attrs["xml:base"]?.let { resolver.resolve(bases.last(), it) } ?: bases.last())
                        if (depth == 2 && xml.namespaceURI == NAMESPACE) {
                            when (xml.localName) {
                                "Url" -> {
                                    if (++urlCount > OpdsBudgets.MAX_ITEMS_PER_RESPONSE) return rejected(OpdsRejection.TooManyItems())
                                    val type = mediaTypes.parse(attrs["type"])
                                    val template = attrs["template"]
                                    if (type != null && template != null && rank(type) != null && attrs["rel"].let { it == null || "results" in it.split(' ') }) {
                                        templates += OpdsSearchTemplate(type, template,
                                            parameters(template, attrs["pageOffset"] ?: "1", attrs["indexOffset"] ?: "1"), baseUrl = bases.last())
                                    }
                                }
                                "InputEncoding", "OutputEncoding" -> {
                                    encodingElement = xml.localName
                                    encodingText.clear()
                                }
                            }
                        }
                    }
                    "TEXT", "CDSECT", "ENTITY_REF" -> if (encodingElement != null) encodingText.append(xml.text)
                    "END_ELEMENT" -> {
                        if (depth == 2 && encodingElement != null) {
                            val value = encodingText.toString().trim()
                            if (value.isNotEmpty()) (if (encodingElement == "InputEncoding") inputs else outputs).add(value)
                            encodingElement = null
                        }
                        bases.removeAt(bases.lastIndex)
                        if (--depth == 0) closedRoot = true
                    }
                }
            }
            if (!sawRoot || !closedRoot || depth != 0) return rejected(OpdsRejection.Malformed("incomplete description"))
            val input = inputs.firstOrNull { it.equals("UTF-8", true) } ?: inputs.firstOrNull() ?: "UTF-8"
            val output = outputs.firstOrNull { it.equals("UTF-8", true) } ?: outputs.firstOrNull() ?: "UTF-8"
            val usable = templates.map { template -> template.copy(inputEncoding = input, parameters = template.parameters.map {
                when (it.name) {
                    "inputEncoding" -> it.copy(defaultValue = input)
                    "outputEncoding" -> it.copy(defaultValue = output)
                    else -> it
                }
            }) }
            val preferred = usable.minByOrNull { rank(it.responseMediaType!!)!! }
            OpdsOpenSearchReader.OpdsDescriptorResult.Descriptor(OpdsSearchDescriptor(preferred, usable.filter { it !== preferred }))
        } catch (_: UnsupportedOpdsEncodingException) {
            rejected(OpdsRejection.UnsupportedEncoding())
        } catch (error: Exception) {
            // xmlutil can refuse external DTDs before delivering DOCDECL.
            val dtd = error.message.orEmpty().contains("document type declaration", true) || error.message.orEmpty().contains("doctype", true)
            rejected(if (dtd) OpdsRejection.DocumentTypeDeclarationRejected() else OpdsRejection.Malformed("invalid OpenSearch description"))
        } finally {
            reader?.close()
        }
    }

    override fun expand(template: OpdsSearchTemplate, query: String, parameters: Map<String, String>): String {
        if (!template.inputEncoding.orEmpty().ifEmpty { "UTF-8" }.equals("UTF-8", true) ||
            parameters["inputEncoding"]?.equals("UTF-8", true) == false) {
            throw OpdsSearchError.Vanilla("unsupported search input encoding")
        }
        val defaults = template.parameters.associate { it.name to it.defaultValue }
        val expanded = replaceParameters(template.template) { name, required ->
            if (name !in SUPPORTED_PARAMETERS) {
                if (required) throw OpdsSearchError.UnsupportedRequiredParameter(name)
                ""
            } else {
                val value = if (name == "searchTerms") query else parameters[name] ?: defaults[name]
                if (value == null && required) throw OpdsSearchError.Vanilla("required search parameter has no value")
                PercentEncoding.encode(value.orEmpty())
            }
        }
        return resolver.resolve(template.baseUrl, expanded)
    }

    private fun parameters(template: String, page: String, index: String): List<OpdsSearchTemplate.OpenSearchParameter> {
        val result = mutableListOf<OpdsSearchTemplate.OpenSearchParameter>()
        replaceParameters(template) { name, required ->
            result += OpdsSearchTemplate.OpenSearchParameter(name, required, when (name) {
                "startPage" -> page
                "startIndex" -> index
                "language" -> "*"
                else -> null
            })
            ""
        }
        return result
    }

    private fun replaceParameters(template: String, replace: (String, Boolean) -> String): String = buildString {
        var i = 0
        while (i < template.length) {
            when (template[i]) {
                '{' -> {
                    val end = template.indexOf('}', i + 1)
                    if (end < 0) throw OpdsSearchError.Vanilla("invalid OpenSearch parameter syntax")
                    val expression = template.substring(i + 1, end)
                    val optional = expression.endsWith('?')
                    val name = expression.removeSuffix("?")
                    if (!PARAMETER_NAME.matches(name)) throw OpdsSearchError.Vanilla("invalid OpenSearch parameter syntax")
                    append(replace(name, !optional))
                    i = end + 1
                }
                '}' -> throw OpdsSearchError.Vanilla("invalid OpenSearch parameter syntax")
                else -> append(template[i++])
            }
        }
    }

    private fun rank(type: OpdsMediaType): Int? = when {
        type.mainType == "application" && type.subType in setOf("opds+json", "opds-publication+json") -> 0
        type.mainType == "application" && type.subType == "atom+xml" -> if (type.parameter("profile")?.contains("opds-catalog", true) == true) 0 else 1
        else -> null
    }

    private companion object {
        const val NAMESPACE = "http://a9.com/-/spec/opensearch/1.1/"
        const val XML_NAMESPACE = "http://www.w3.org/XML/1998/namespace"
        val SUPPORTED_PARAMETERS = setOf("searchTerms", "startPage", "startIndex", "count", "language", "inputEncoding", "outputEncoding")
        val PARAMETER_NAME = Regex("[A-Za-z_][A-Za-z0-9_.:-]*")
    }
}
