package com.retro99.opds.implementation.acquisition

import com.retro99.opds.api.OpdsAcquisitionAction
import com.retro99.opds.api.OpdsAcquisitionClassifier
import com.retro99.opds.api.OpdsClientCapabilities
import com.retro99.opds.api.OpdsUnavailableReason
import com.retro99.opds.api.model.OpdsIndirectAcquisition
import com.retro99.opds.api.model.OpdsLink
import com.retro99.opds.api.model.OpdsMediaType

/** Pure first-release policy. No URL suffix, title, or indirect EPUB inference. */
class DefaultAcquisitionClassifier : OpdsAcquisitionClassifier {
    override fun classify(link: OpdsLink, capabilities: OpdsClientCapabilities): OpdsAcquisitionAction {
        val relations = link.relations.map { it.removePrefix("http://opds-spec.org/acquisition/") }.toSet()
        val recognized = relations.any { it in ACQUISITION_RELATIONS }
        val reason = when {
            link.mediaType.isProtected() || link.indirectAcquisition.isProtected() -> OpdsUnavailableReason.PROTECTED
            "preview" in relations || "sample" in relations -> OpdsUnavailableReason.SAMPLE_ONLY
            "buy" in relations || "subscribe" in relations -> OpdsUnavailableReason.SOLD
            "borrow" in relations -> OpdsUnavailableReason.BORROW
            else -> null
        }
        if (link.isTemplate || !recognized) return OpdsAcquisitionAction.NotAvailable(reason ?: OpdsUnavailableReason.UNSUPPORTED_FORMAT)
        if (reason == null && link.indirectAcquisition == null && link.isEpub() &&
            "application/epub+zip" in capabilities.openableMediaTypes) {
            return OpdsAcquisitionAction.Download(link)
        }
        val unavailable = reason ?: OpdsUnavailableReason.UNSUPPORTED_FORMAT
        if (unavailable != OpdsUnavailableReason.PROTECTED &&
            link.mediaType?.mediaName() in setOf("text/html", "application/xhtml+xml")) {
            return OpdsAcquisitionAction.OpenProviderPage(link, unavailable)
        }
        return OpdsAcquisitionAction.NotAvailable(unavailable)
    }

    private fun OpdsIndirectAcquisition?.isProtected(): Boolean =
        this != null && (mediaType.isProtected() || children.any { it.isProtected() })

    private fun OpdsMediaType?.isProtected(): Boolean = this != null &&
        (mediaName() in PROTECTED_TYPES || parameter("profile")?.lowercase() in setOf("lcp", "drm") || parameter("drm") != null)

    private fun OpdsMediaType.mediaName() = "$mainType/$subType"

    private companion object {
        val ACQUISITION_RELATIONS = setOf("download", "acquisition", "http://opds-spec.org/acquisition", "open-access", "buy", "borrow", "subscribe", "sample", "preview")
        val PROTECTED_TYPES = setOf("application/vnd.readium.lcp.license+json", "application/vnd.adobe.adept+xml", "application/vnd.adobe.adept+json")
    }
}
