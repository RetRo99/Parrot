package com.retro99.database.api.cloudfiles

data class CloudBookFileEntity(
    val libraryBookId: String,
    val cloudBookFileId: String,
    val mediaType: String,
    val relativePath: String,
    val fileName: String,
    val status: String,
    val sizeBytes: Long,
    val contentHash: String,
    val contentHashAlgorithm: String,
    val remoteRevision: Long,
    val updatedAt: String,
)
