package com.retro99.books.data.transfer

import com.retro99.books.data.calculateFileContentHash

interface BookFileTransferFileStore {
    fun stagingPath(transferId: String): String
    fun importedFilePath(localUuid: String, mediaType: String): String
    suspend fun exists(path: String): Boolean
    suspend fun size(path: String): Long
    fun contentHash(path: String): String = calculateFileContentHash(path)
    suspend fun truncate(path: String)
    suspend fun write(path: String, offset: Long, bytes: ByteArray)
    suspend fun moveToImportedStore(stagingPath: String, destinationPath: String)
    suspend fun writeCover(localUuid: String, bytes: ByteArray): String
    suspend fun delete(path: String): Boolean
}
