package com.retro99.books.data.transfer

import com.retro99.books.data.calculateFileContentHash

interface BookFileTransferFileStore {
    fun stagingPath(transferId: String): String
    /** Where a device copy of [libraryBookId] in [mediaType] is stored. Read paths from
     *  `device_files`, never rebuild them from this (I5). */
    fun libraryFilePath(libraryBookId: String, mediaType: String): String
    suspend fun exists(path: String): Boolean
    suspend fun size(path: String): Long
    fun contentHash(path: String): String = calculateFileContentHash(path)
    suspend fun truncate(path: String)
    suspend fun write(path: String, offset: Long, bytes: ByteArray)
    suspend fun moveToImportedStore(stagingPath: String, destinationPath: String)
    /** Where [writeCover] puts the cover of [libraryBookId]. */
    fun coverPath(libraryBookId: String): String
    suspend fun writeCover(libraryBookId: String, bytes: ByteArray): String
    suspend fun delete(path: String): Boolean
}
