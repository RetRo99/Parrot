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
    fun classify(link: OpdsLink): OpdsAcquisitionAction
}

sealed interface OpdsAcquisitionAction {
    /** A direct, complete EPUB download this client may offer ( Basic-auth optional). */
    data class Download(val link: OpdsLink) : OpdsAcquisitionAction

    /**
     * Not downloadable here; the reason code is bounded (Phase 2 UI maps to
     * the exact failure-reason copy) and, when the catalogue provides a
     * provider-flow link, the UI may offer "Open provider page" on explicit
     * user action (plan §6).
     */
    data class Unsupported(val reason: Reason, val providerPage: OpdsLink? = null) : OpdsAcquisitionAction {
        enum class Reason {
            UNSUPPORTED_FORMAT,
            INDIRECT_ACQUISITION,
            PURCHASE_REQUIRED,
            BORROW_LENDING,
            SUBSCRIPTION,
            SAMPLE_ONLY,
            PROTECTED_FILE, // DRM/LCP indications
            NO_MEDIA_TYPE, // cannot be verified/opened; honest refusal
        }
    }
}
