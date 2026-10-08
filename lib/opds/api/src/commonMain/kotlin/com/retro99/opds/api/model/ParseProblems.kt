package com.retro99.opds.api.model

/**
 * Recoverable, bounded problems observed while parsing (plan §4: "Recoverable
 * item errors produce warnings; malformed roots fail visibly").
 *
 * Hygiene rule (plan §4): warnings never include publication titles, URLs,
 * or any private data — only structure paths and machine-readable codes.
 */
data class ParseWarning(val code: Code, val elementPath: String? = null) {
    enum class Code {
        MISSING_IDENTITY,
        UNRESOLVABLE_LINK,
        UNKNOWN_EXTENSION_IGNORED,
        MALFORMED_ITEM_SKIPPED,
        MEDIA_TYPE_NOT_PARSED,
    }
}

/**
 * Hard parse failures: the user-facing message "this is not a usable
 * catalogue / publication" maps from these codes (Phase 2 mapping owns copy).
 */
sealed interface OpdsRejection {
    val note: String? // bounded, hygiene-safe

    data class Malformed(override val note: String? = null) : OpdsRejection
    data class NotACatalogue(override val note: String? = null) : OpdsRejection // HTML/RSS/garbage
    data class DocumentTypeDeclarationRejected(override val note: String? = null) : OpdsRejection // DOCTYPE/DD
    data class TooLarge(override val note: String? = null) : OpdsRejection
    data class TooDeep(override val note: String? = null) : OpdsRejection
    data class TooManyItems(override val note: String? = null) : OpdsRejection
    data class UnresolvedBase(override val note: String? = null) : OpdsRejection
}
