package com.retro99.books.data

internal const val CONTENT_HASH_ALGORITHM = "sha-256-v1"

internal const val HASH_BUFFER_SIZE = 8192

/** Hashes the file in [HASH_BUFFER_SIZE] chunks; the file is never held in memory whole. */
internal expect fun calculateFileContentHash(filePath: String): String

/** The file's size in bytes, or 0 when there is no file. */
internal expect fun fileSizeBytes(filePath: String): Long

internal expect class Sha256Digest() {
    fun update(bytes: ByteArray, offset: Int, length: Int)
    fun digest(): ByteArray
}

internal fun sha256(bytes: ByteArray): ByteArray {
    return Sha256Digest().apply { update(bytes, 0, bytes.size) }.digest()
}

internal fun ByteArray.toHexString(): String {
    return joinToString(separator = "") { byte ->
        (byte.toInt() and 0xff).toString(16).padStart(2, '0')
    }
}
