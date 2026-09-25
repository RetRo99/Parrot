package com.retro99.server.api.library

import kotlinx.serialization.Serializable

/** Explicit, device-local pairing of one source account with a linked Cloud account. */
@Serializable
data class LibrarySourceIdentityBinding(
    val adapterId: LibraryAdapterId,
    val cloudAccountId: String,
    val sourceAccountId: String,
    val backendId: String,
) {
    init {
        require(cloudAccountId.isNotBlank())
        require(sourceAccountId.isNotBlank())
        require(backendId.isNotBlank())
    }
}
