package com.retro99.cloud.implementation.transfer

/** Small random-access file API used to keep TUS requests bounded in memory. */
interface TusLocalFileSource {
    suspend fun size(path: String): Long

    suspend fun read(path: String, offset: Long, length: Int): ByteArray
}
