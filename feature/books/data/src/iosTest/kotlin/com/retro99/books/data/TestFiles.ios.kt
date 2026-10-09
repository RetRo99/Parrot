package com.retro99.books.data

import kotlinx.cinterop.BetaInteropApi
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.usePinned
import platform.Foundation.NSData
import platform.Foundation.NSFileManager
import platform.Foundation.NSTemporaryDirectory
import platform.Foundation.NSUUID
import platform.Foundation.create

@OptIn(ExperimentalForeignApi::class, BetaInteropApi::class)
internal actual object TestFiles {
    private val directory: String by lazy {
        "${NSTemporaryDirectory()}books-data-test-${NSUUID().UUIDString}".also { path ->
            NSFileManager.defaultManager.createDirectoryAtPath(
                path,
                withIntermediateDirectories = true,
                attributes = null,
                error = null,
            )
        }
    }

    actual fun write(name: String, bytes: ByteArray): String {
        val path = "$directory/$name"
        val data = if (bytes.isEmpty()) {
            NSData()
        } else {
            bytes.usePinned { pinned -> NSData.create(bytes = pinned.addressOf(0), length = bytes.size.toULong()) }
        }
        check(NSFileManager.defaultManager.createFileAtPath(path, contents = data, attributes = null))
        return path
    }

    actual fun exists(path: String): Boolean = NSFileManager.defaultManager.fileExistsAtPath(path)

    actual fun delete(path: String) {
        NSFileManager.defaultManager.removeItemAtPath(path, error = null)
    }
}
