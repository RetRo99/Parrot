package com.retro99.server.api.library

enum class LibraryBookOperation {
    RemoveRemoteBook,
}

data class LibraryBookOperationAvailability(
    val operation: LibraryBookOperation,
    val isAvailable: Boolean,
    val reason: String? = null,
) {
    init {
        require(isAvailable || !reason.isNullOrBlank()) {
            "An unavailable book operation must explain why"
        }
    }
}

data class LibraryBookOperationRequest(
    val operationId: String,
    val operation: LibraryBookOperation,
    val source: SourceBookRef,
    val expectedSourceRevision: String,
    val userConfirmed: Boolean = false,
) {
    init {
        require(operationId.isNotBlank())
        require(expectedSourceRevision.isNotBlank())
        require(source.connectionId != null) {
            "An executable book operation requires a local source connection"
        }
    }
}
