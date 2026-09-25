package com.retro99.server.api.library

import kotlinx.coroutines.flow.Flow

enum class LibrarySourceIdentityPairingStatus {
    Unpaired,
    Paired,
    CloudAccountRequired,
    CloudAccountMismatch,
    SourceAccountMismatch,
    NotAuthenticated,
    ServerUnavailable,
}

enum class LibrarySourceIdentityPairingFailure {
    CloudAccountRequired,
    CloudAccountMismatch,
    SourceAccountMismatch,
    SourceAccountIdUnavailable,
    AlreadyPaired,
    InvalidCode,
    UnsupportedCodeVersion,
    NotAuthenticated,
    ServerUnavailable,
}

class LibrarySourceIdentityPairingException(
    val reason: LibrarySourceIdentityPairingFailure,
) : IllegalStateException(reason.name)

/** Integration capability for explicitly sharing source identity across installations. */
interface LibrarySourceIdentityPairing {
    fun observePairingStatus(): Flow<LibrarySourceIdentityPairingStatus>

    /** Generate or re-export this connection's pairing code. */
    suspend fun createPairingCode(): String

    /** Bind this connection to a pairing code from another installation. */
    suspend fun importPairingCode(code: String)
}
