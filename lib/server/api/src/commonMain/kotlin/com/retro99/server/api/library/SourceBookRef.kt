package com.retro99.server.api.library

import kotlinx.serialization.Serializable

/** Installation-local profile scope; resolve it before applying portable changes. */
@Serializable
data class LibraryProfileId(val value: String) {
    init { require(value.isNotBlank()) }
}

/** Registration key owned by an adapter, independent of the legacy ServerType enum. */
@Serializable
data class LibraryAdapterId(val value: String) {
    init { require(value.isNotBlank()) }
}

@Serializable
data class SourceConnectionId(val value: String) {
    init { require(value.isNotBlank()) }
}

@Serializable
data class NativeBookId(val value: String) {
    init { require(value.isNotBlank()) }
}

/** An existing content/Cloud alias, never a displayed group ID. */
@Serializable
data class LegacyLibraryBookId(val value: String) {
    init { require(value.isNotBlank()) }
}

/**
 * Portable identity must be certified by the adapter's backend/account contract.
 * Neither a URL nor an installation's connection UUID establishes this identity.
 */
@Serializable
sealed interface SourceAccountIdentity {
    @Serializable
    data class Portable(
        val backendId: String,
        val accountId: String,
    ) : SourceAccountIdentity {
        init {
            require(backendId.isNotBlank())
            require(accountId.isNotBlank())
        }
    }

    /** Local-only fallback. Never serialize this as a portable source membership. */
    @Serializable
    data class Unresolved(val connectionId: SourceConnectionId) : SourceAccountIdentity
}

@Serializable
data class SourceBookKey(
    val profileId: LibraryProfileId,
    val adapterId: LibraryAdapterId,
    val accountIdentity: SourceAccountIdentity,
    val nativeBookId: NativeBookId,
)

/**
 * Identity and execution location are separate: another installation can resolve
 * the same portable member to a different connection, or have no connection yet.
 * Callers index memberships by [key], not by the whole reference or legacy alias.
 */
@Serializable
data class SourceBookRef(
    val key: SourceBookKey,
    val connectionId: SourceConnectionId?,
    val legacyLibraryBookId: LegacyLibraryBookId? = null,
) {
    init {
        val unresolved = key.accountIdentity as? SourceAccountIdentity.Unresolved
        require(unresolved == null || unresolved.connectionId == connectionId) {
            "An unresolved source must retain its installation-local connection"
        }
    }
}
