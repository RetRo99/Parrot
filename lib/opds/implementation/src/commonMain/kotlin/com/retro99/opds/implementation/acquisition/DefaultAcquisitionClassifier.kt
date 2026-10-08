package com.retro99.opds.implementation.acquisition

import com.retro99.opds.api.OpdsAcquisitionAction
import com.retro99.opds.api.OpdsAcquisitionClassifier
import com.retro99.opds.api.OpdsClientCapabilities
import com.retro99.opds.api.OpdsUnavailableReason
import com.retro99.opds.api.OpdsEntryAcquisition
import com.retro99.opds.api.model.OpdsEntry
import com.retro99.opds.api.model.OpdsIndirectAcquisition
import com.retro99.opds.api.model.OpdsLink
import com.retro99.opds.api.model.OpdsMediaType

/** Pure first-release policy. No URL suffix, title, or indirect EPUB inference. */
class DefaultAcquisitionClassifier : OpdsAcquisitionClassifier {
    override fun classify(link: OpdsLink, capabilities: OpdsClientCapabilities): OpdsAcquisitionAction {
        val relations = link.relations.map { it.removePrefix("http://opds-spec.org/acquisition/") }.toSet()
        val recognized = relations.any { it in ACQUISITION_RELATIONS }
        val reason = when {
            "preview" in relations || "sample" in relations -> OpdsUnavailableReason.SAMPLE_ONLY
            "buy" in relations -> OpdsUnavailableReason.SOLD
            "subscribe" in relations -> OpdsUnavailableReason.SUBSCRIPTION
            "borrow" in relations -> OpdsUnavailableReason.BORROW
            link.mediaType.isProtected() || link.indirectAcquisition.isProtected() -> OpdsUnavailableReason.PROTECTED
            else -> null
        }
        if (link.isTemplate || !recognized) return OpdsAcquisitionAction.NotAvailable(reason ?: OpdsUnavailableReason.UNSUPPORTED_FORMAT)
        if (reason == null && link.indirectAcquisition == null && link.isEpub() &&
            "application/epub+zip" in capabilities.openableMediaTypes) {
            return OpdsAcquisitionAction.Download(link)
        }
        val unavailable = reason ?: OpdsUnavailableReason.UNSUPPORTED_FORMAT
        if (unavailable in PROVIDER_PAGE_REASONS && link.isWebLink()) {
            return OpdsAcquisitionAction.OpenProviderPage(link, unavailable)
        }
        return OpdsAcquisitionAction.NotAvailable(unavailable)
    }

    override fun classify(entry: OpdsEntry, capabilities: OpdsClientCapabilities): OpdsEntryAcquisition {
        val choices = entry.acquisitionLinks.map { it to classify(it, capabilities) }
        choices.firstOrNull { it.second is OpdsAcquisitionAction.Download }?.let { return OpdsEntryAcquisition(it.second) }
        fun reason(action: OpdsAcquisitionAction) = when (action) {
            is OpdsAcquisitionAction.NotAvailable -> action.reason
            is OpdsAcquisitionAction.OpenProviderPage -> action.reason
            is OpdsAcquisitionAction.Download -> error("download handled above")
        }
        val selected = choices.minByOrNull { REASON_ORDER.indexOf(reason(it.second)) }
        val why = selected?.second?.let { reason(it) } ?: OpdsUnavailableReason.UNSUPPORTED_FORMAT
        val provider = if (why == OpdsUnavailableReason.BORROW) entry.lender else if (why in PROVIDER_PAGE_REASONS) entry.seller else null
        val web = if (why in PROVIDER_PAGE_REASONS) {
            selected?.first?.takeIf { it.isWebLink() } ?: entry.links.firstOrNull { it.isWebLink() }
        } else null
        return OpdsEntryAcquisition(
            if (web != null) OpdsAcquisitionAction.OpenProviderPage(web, why) else OpdsAcquisitionAction.NotAvailable(why),
            provider, if (why == OpdsUnavailableReason.UNSUPPORTED_FORMAT) selected?.first?.mediaType else null,
        )
    }

    private fun OpdsLink.isWebLink() = !isTemplate && mediaType?.mediaName() in setOf("text/html", "application/xhtml+xml")

    private fun OpdsIndirectAcquisition?.isProtected(): Boolean =
        this != null && (mediaType.isProtected() || children.any { it.isProtected() })

    private fun OpdsMediaType?.isProtected(): Boolean = this != null &&
        (mediaName() in PROTECTED_TYPES || parameter("profile")?.lowercase() in setOf("lcp", "drm") || parameter("drm") != null)

    private fun OpdsMediaType.mediaName() = "$mainType/$subType"

    private companion object {
        val ACQUISITION_RELATIONS = setOf("download", "acquisition", "http://opds-spec.org/acquisition", "open-access", "buy", "borrow", "subscribe", "sample", "preview")
        val PROVIDER_PAGE_REASONS = setOf(OpdsUnavailableReason.SOLD, OpdsUnavailableReason.SUBSCRIPTION, OpdsUnavailableReason.BORROW, OpdsUnavailableReason.SAMPLE_ONLY)
        val REASON_ORDER = listOf(OpdsUnavailableReason.SAMPLE_ONLY, OpdsUnavailableReason.SOLD, OpdsUnavailableReason.SUBSCRIPTION, OpdsUnavailableReason.BORROW, OpdsUnavailableReason.PROTECTED, OpdsUnavailableReason.UNSUPPORTED_FORMAT)
        val PROTECTED_TYPES = setOf("application/vnd.readium.lcp.license+json", "application/vnd.adobe.adept+xml", "application/vnd.adobe.adept+json")
    }
}
