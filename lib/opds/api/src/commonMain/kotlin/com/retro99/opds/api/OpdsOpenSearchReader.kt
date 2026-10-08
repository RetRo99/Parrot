package com.retro99.opds.api

/**
 * OpenSearch description documents (OPDS1 search discovery; plan §4).
 * Reads a fetched descriptor, chooses the best template for OPDS use, and
 * expands it. The implementation must prefer OPDS/XML response types over
 * HTML/suggestions (§4) and fail explicitly on required-but-unsupported
 * parameters.
 */
interface OpdsOpenSearchReader {
    fun readDescriptor(payload: OpdsPayload, effectiveResponseUrl: String): OpdsDescriptorResult
    fun expand(template: OpdsSearchTemplate, query: String, parameters: Map<String, String> = emptyMap()): String

    sealed interface OpdsDescriptorResult {
        data class Descriptor(val descriptor: OpdsSearchDescriptor) : OpdsDescriptorResult
        data class NotADescriptor(val rejection: com.retro99.opds.api.model.OpdsRejection) : OpdsDescriptorResult
    }
}

data class OpdsSearchDescriptor(
    /** Best pick for catalogue search; null when only HTML/suggestion forms exist. */
    val preferred: OpdsSearchTemplate?,
    /** Alternate usable templates, in descriptor order (bounded). */
    val alternatives: List<OpdsSearchTemplate> = emptyList(),
)

data class OpdsSearchTemplate(
    val responseMediaType: com.retro99.opds.api.model.OpdsMediaType?,
    /** RFC 3986-reference template, with the descriptor's parameter syntax inside. */
    val template: String,
    /** OpenSearch parameter set observed in the template. */
    val parameters: List<OpenSearchParameter> = emptyList(),
    val inputEncoding: String? = null,
    /** Effective descriptor URL plus inherited xml:base; the template is not yet resolved. */
    val baseUrl: String,
) {
    data class OpenSearchParameter(
        val name: String,
        val required: Boolean,
        val defaultValue: String? = null,
    )
}

/** OpenSearch expansion errors (§4: "explicit unsupported-required-parameter errors"). */
sealed class OpdsSearchError(why: String) : IllegalStateException(why) {
    class UnsupportedRequiredParameter(val parameterName: String) :
        OpdsSearchError("unsupported required OpenSearch parameter")

    class Vanilla(massage: String) : OpdsSearchError(massage)
}
