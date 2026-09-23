package com.retro99.sync.domain

import kotlinx.coroutines.flow.Flow

/** Reports aggregate progress from a durable book-file transfer engine. */
interface FileTransferStatusSource {
    fun observe(): Flow<FileTransferStatus?>
}

data class FileTransferStatus(
    val phase: SyncPhase,
    val activeItems: Int,
    val totalItems: Int?,
    val bytesTransferred: Long,
    val totalBytes: Long?,
    val error: String? = null,
    val canRetry: Boolean = true,
)
