package com.retro99.opds.api

import com.retro99.opds.api.model.OpdsLink

/**
 * Extensible acquisition policy (plan §5.1) selected by relation, outer
 * content type, and indirect tree. The first handler: direct, complete,
 * DRM-free EPUB for generic acquisition/open-access/download relations.
 * Buy/borrow/subscribe/preview and indirect resources represent honestly
 * rather than as downloads.
 */
interface OpdsAcquisitionClassifier {
    fun classify(link: OpdsLink, capabilities: OpdsClientCapabilities = OpdsClientCapabilities()): OpdsAcquisitionAction

    /** Keep every advertised choice; only a full downloadable file is selectable. */
    fun files(links: List<OpdsLink>, capabilities: OpdsClientCapabilities = OpdsClientCapabilities()): List<OpdsFileChoice> {
        var selected = false
        return links.map { link ->
            val action = classify(link, capabilities)
            val openable = action is OpdsAcquisitionAction.Download
            val default = openable && !selected
            if (default) selected = true
            OpdsFileChoice(link, action, openable, default)
        }
    }
}

data class OpdsClientCapabilities(val openableMediaTypes: Set<String> = setOf("application/epub+zip"))
data class OpdsFileChoice(val link: OpdsLink, val action: OpdsAcquisitionAction, val isOpenable: Boolean, val isDefault: Boolean)

/** Protocol reasons matching the design brief's not-downloadable categories; UI owns copy. */
enum class OpdsUnavailableReason { SOLD, BORROW, SAMPLE_ONLY, UNSUPPORTED_FORMAT, PROTECTED }

sealed interface OpdsAcquisitionAction {
    /** A direct, complete EPUB download this client may offer ( Basic-auth optional). */
    data class Download(val link: OpdsLink) : OpdsAcquisitionAction

    /** An advertised HTML provider flow, opened only on explicit user action. */
    data class OpenProviderPage(val link: OpdsLink, val reason: OpdsUnavailableReason) : OpdsAcquisitionAction
    data class NotAvailable(val reason: OpdsUnavailableReason) : OpdsAcquisitionAction
}
