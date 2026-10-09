package com.retro99.books.data

import java.io.File
import java.security.MessageDigest

internal actual fun calculateFileContentHash(filePath: String): String {
    val digest = Sha256Digest()
    File(filePath).inputStream().use { inputStream ->
        val buffer = ByteArray(HASH_BUFFER_SIZE)
        var bytesRead = inputStream.read(buffer)
        while (bytesRead >= 0) {
            if (bytesRead > 0) {
                digest.update(buffer, 0, bytesRead)
            }
            bytesRead = inputStream.read(buffer)
        }
    }
    return digest.digest().toHexString()
}

internal actual fun fileSizeBytes(filePath: String): Long = File(filePath).length()

internal actual class Sha256Digest actual constructor() {
    private val messageDigest = MessageDigest.getInstance("SHA-256")

    actual fun update(bytes: ByteArray, offset: Int, length: Int) {
        messageDigest.update(bytes, offset, length)
    }

    actual fun digest(): ByteArray {
        return messageDigest.digest()
    }
}
