package com.retro99.books.domain.model

import com.retro99.server.api.ServerBook

fun List<ServerBook>.aggregateBookReplicas(): List<ServerBook> {
    return groupBy { book -> book.canonicalIdentity() }
        .values
        .map { replicas ->
            replicas.firstOrNull { book -> book.isLocal } ?: replicas.first()
        }
}

private fun ServerBook.canonicalIdentity(): String {
    return libraryBookId
        ?: contentHash?.let { hash -> "${contentHashAlgorithm ?: "sha-256-v1"}:$hash" }
        ?: "$serverId:$uuid"
}
