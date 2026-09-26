package com.retro99.analytics.api

/**
 * Bounded, non-user-authored context for an unexpected user-impacting failure.
 * Values are validated again at the analytics provider boundary; do not pass messages,
 * identifiers, URLs, filenames, or user-authored content here.
 */
data class DiagnosticContext(
    val screen: String? = null,
    val sourceScreen: String? = null,
    val entryPoint: String? = null,
    val action: String? = null,
    val operation: String? = null,
    val stage: String? = null,
    val outcome: String? = null,
    val reasonCode: String? = null,
    val serverType: String? = null,
    val mediaType: String? = null,
    /** Diagnostic-only correlation ID. Never forward this as a Firebase Analytics dimension. */
    val correlationId: String? = null,
)
