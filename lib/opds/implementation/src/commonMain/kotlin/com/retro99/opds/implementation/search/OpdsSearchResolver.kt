package com.retro99.opds.implementation.search

import com.retro99.opds.api.OpdsTemplateExpander
import com.retro99.opds.api.OpdsUrlResolver
import com.retro99.opds.api.model.OpdsSearchOffer

/** Templates are expanded first; `self` identity never replaces the response base. */
class OpdsSearchResolver(private val resolver: OpdsUrlResolver, private val expander: OpdsTemplateExpander) {
    fun expand(offer: OpdsSearchOffer, effectiveBaseUrl: String, query: String, optionalFields: Map<String, String> = emptyMap()): String {
        require(offer.kind == OpdsSearchOffer.Kind.URI_TEMPLATE) { "OpenSearch descriptors require discovery first" }
        return resolver.resolve(effectiveBaseUrl, expander.expand(offer.link.rawHref, optionalFields + ("query" to query)))
    }
}
