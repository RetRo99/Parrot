package com.retro99.books.data

import kotlinx.cinterop.ByteVar
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.UByteVar
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.alloc
import kotlinx.cinterop.convert
import kotlinx.cinterop.nativeHeap
import kotlinx.cinterop.ptr
import kotlinx.cinterop.readBytes
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.usePinned
import platform.CoreCrypto.CC_SHA256_CTX
import platform.CoreCrypto.CC_SHA256_DIGEST_LENGTH
import platform.CoreCrypto.CC_SHA256_Final
import platform.CoreCrypto.CC_SHA256_Init
import platform.CoreCrypto.CC_SHA256_Update
import platform.Foundation.NSFileHandle
import platform.Foundation.closeFile
import platform.Foundation.fileHandleForReadingAtPath
import platform.Foundation.readDataOfLength

@OptIn(ExperimentalForeignApi::class)
internal actual fun calculateFileContentHash(filePath: String): String {
    val fileHandle = NSFileHandle.fileHandleForReadingAtPath(filePath)
        ?: error("Could not open file for content hashing: $filePath")
    val digest = Sha256Digest()
    try {
        while (true) {
            val data = fileHandle.readDataOfLength(HASH_BUFFER_SIZE.convert())
            if (data.length == 0UL) {
                break
            }
            val bytes = data.bytes?.reinterpret<ByteVar>()?.readBytes(data.length.toInt())
                ?: error("Could not read file bytes for content hashing")
            digest.update(bytes, 0, bytes.size)
        }
    } finally {
        fileHandle.closeFile()
    }
    return digest.digest().toHexString()
}

@OptIn(ExperimentalForeignApi::class)
internal actual class Sha256Digest actual constructor() {
    private val context = nativeHeap.alloc<CC_SHA256_CTX>()
    private var finished = false

    init {
        CC_SHA256_Init(context.ptr)
    }

    actual fun update(bytes: ByteArray, offset: Int, length: Int) {
        require(offset >= 0 && length >= 0 && offset + length <= bytes.size)
        if (length == 0) {
            return
        }
        bytes.usePinned { pinned ->
            CC_SHA256_Update(context.ptr, pinned.addressOf(offset), length.convert())
        }
    }

    actual fun digest(): ByteArray {
        check(!finished) { "SHA-256 digest was already finalized" }
        finished = true
        val hash = ByteArray(CC_SHA256_DIGEST_LENGTH)
        hash.usePinned { pinned ->
            CC_SHA256_Final(pinned.addressOf(0).reinterpret<UByteVar>(), context.ptr)
        }
        nativeHeap.free(context.rawPtr)
        return hash
    }
}
