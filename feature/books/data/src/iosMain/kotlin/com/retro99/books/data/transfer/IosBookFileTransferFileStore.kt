package com.retro99.books.data.transfer

import com.retro99.books.data.calculateFileContentHash
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.usePinned
import platform.Foundation.NSData
import platform.Foundation.NSDocumentDirectory
import platform.Foundation.NSFileHandle
import platform.Foundation.NSFileManager
import platform.Foundation.NSUserDomainMask
import platform.Foundation.closeFile
import platform.Foundation.create
import platform.Foundation.fileHandleForWritingAtPath
import platform.Foundation.seekToFileOffset
import platform.Foundation.truncateFileAtOffset
import platform.Foundation.writeData
import platform.Foundation.NSSearchPathForDirectoriesInDomains
import org.koin.core.annotation.Single

@OptIn(ExperimentalForeignApi::class)
@Single(binds = [BookFileTransferFileStore::class])
class IosBookFileTransferFileStore : BookFileTransferFileStore {
    override fun stagingPath(transferId: String): String =
        "${documentsDirectory()}/book_file_transfers/${transferId.safeName()}.part"

    override fun importedFilePath(localUuid: String, mediaType: String): String =
        "${documentsDirectory()}/ebooks/${localUuid.safeName()}_${mediaType.safeName()}.epub"

    override suspend fun exists(path: String): Boolean = NSFileManager.defaultManager.fileExistsAtPath(path)

    override suspend fun size(path: String): Long =
        (NSFileManager.defaultManager.attributesOfItemAtPath(path, error = null)?.get("NSFileSize") as? Long)
            ?: 0L

    override fun contentHash(path: String): String = calculateFileContentHash(path)

    override suspend fun truncate(path: String) {
        ensureParent(path)
        if (!NSFileManager.defaultManager.createFileAtPath(path, contents = null, attributes = null) &&
            !NSFileManager.defaultManager.fileExistsAtPath(path)
        ) error("Could not create download staging file")
        val handle = NSFileHandle.fileHandleForWritingAtPath(path) ?: error("Could not open staging file")
        try {
            handle.truncateFileAtOffset(0uL)
        } finally {
            handle.closeFile()
        }
    }

    override suspend fun write(path: String, offset: Long, bytes: ByteArray) {
        ensureParent(path)
        if (!NSFileManager.defaultManager.fileExistsAtPath(path)) {
            NSFileManager.defaultManager.createFileAtPath(path, contents = null, attributes = null)
        }
        val handle = NSFileHandle.fileHandleForWritingAtPath(path) ?: error("Could not open download staging file")
        try {
            handle.seekToFileOffset(offset.toULong())
            bytes.usePinned { pinned ->
                handle.writeData(
                    NSData.create(bytes = pinned.addressOf(0), length = bytes.size.toULong()),
                )
            }
        } finally {
            handle.closeFile()
        }
    }

    override suspend fun moveToImportedStore(stagingPath: String, destinationPath: String) {
        ensureParent(destinationPath)
        val fileManager = NSFileManager.defaultManager
        if (fileManager.fileExistsAtPath(destinationPath)) {
            if (!fileManager.removeItemAtPath(destinationPath, error = null)) error("Could not replace restored book file")
        }
        if (fileManager.moveItemAtPath(stagingPath, destinationPath, error = null)) return

        val temporaryDestination = "$destinationPath.part"
        if (fileManager.fileExistsAtPath(temporaryDestination)) {
            fileManager.removeItemAtPath(temporaryDestination, error = null)
        }
        if (!fileManager.copyItemAtPath(stagingPath, temporaryDestination, error = null) ||
            !fileManager.moveItemAtPath(temporaryDestination, destinationPath, error = null)
        ) {
            fileManager.removeItemAtPath(temporaryDestination, error = null)
            error("Could not atomically move restored book into the import store")
        }
        if (!fileManager.removeItemAtPath(stagingPath, error = null)) {
            error("Restored book was saved but its staging file could not be removed")
        }
    }

    override suspend fun writeCover(localUuid: String, bytes: ByteArray): String {
        val path = "${documentsDirectory()}/imported_covers/${localUuid.safeName()}.png"
        ensureParent(path)
        val fileManager = NSFileManager.defaultManager
        if (!fileManager.createFileAtPath(path, contents = null, attributes = null) &&
            !fileManager.fileExistsAtPath(path)
        ) error("Could not create restored cover")
        val handle = NSFileHandle.fileHandleForWritingAtPath(path) ?: error("Could not open restored cover")
        try {
            handle.truncateFileAtOffset(0uL)
            if (bytes.isNotEmpty()) {
                bytes.usePinned { pinned ->
                    handle.writeData(NSData.create(bytes = pinned.addressOf(0), length = bytes.size.toULong()))
                }
            }
        } finally {
            handle.closeFile()
        }
        return path
    }

    override suspend fun delete(path: String): Boolean {
        val fileManager = NSFileManager.defaultManager
        return !fileManager.fileExistsAtPath(path) || fileManager.removeItemAtPath(path, error = null)
    }

    private fun documentsDirectory(): String =
        NSSearchPathForDirectoriesInDomains(NSDocumentDirectory, NSUserDomainMask, true)
            .firstOrNull() as? String ?: error("Could not find documents directory")

    private fun ensureParent(path: String) {
        val parent = path.substringBeforeLast('/', "")
        if (parent.isNotEmpty()) {
            NSFileManager.defaultManager.createDirectoryAtPath(
                parent,
                withIntermediateDirectories = true,
                attributes = null,
                error = null,
            )
        }
    }
}

private fun String.safeName(): String = map { character ->
    if (character.isLetterOrDigit() || character == '-' || character == '_') character else '_'
}.joinToString("")
