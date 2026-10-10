package com.retro99.database.api.cloudfiles

data class CloudFileTransferEntity(
    val transferId: String,
    val serverId: String,
    val direction: String,
    val libraryBookId: String,
    val cloudBookFileId: String?,
    val mediaType: String,
    val stagingPath: String?,
    val sizeBytes: Long,
    val bytesTransferred: Long,
    val contentHash: String?,
    val contentHashAlgorithm: String?,
    val uploadId: String?,
    val storagePath: String?,
    val tusUploadUrl: String?,
    val tusExpiresAt: String?,
    val rightsAttestation: String?,
    /** "" for the book's own file; a prepared chapter is told apart by this. */
    val relativePath: String = "",
    /** Where the bytes are. Null means the device-files row for (book, media type). */
    val sourcePath: String? = null,
    val state: String,
    val attemptCount: Int,
    val nextAttemptAt: String?,
    val lastError: String?,
    val createdAt: String,
    val updatedAt: String,
)
