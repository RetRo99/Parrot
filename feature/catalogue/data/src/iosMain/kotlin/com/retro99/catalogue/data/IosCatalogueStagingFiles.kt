package com.retro99.catalogue.data

import com.retro99.base.file.safeFileName
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.UByteVar
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.alloc
import kotlinx.cinterop.convert
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.usePinned
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.withContext
import org.koin.core.annotation.Single
import platform.CoreCrypto.CC_SHA256_CTX
import platform.CoreCrypto.CC_SHA256_DIGEST_LENGTH
import platform.CoreCrypto.CC_SHA256_Final
import platform.CoreCrypto.CC_SHA256_Init
import platform.CoreCrypto.CC_SHA256_Update
import platform.Foundation.NSFileManager
import platform.Foundation.NSFileSize
import platform.Foundation.NSFileSystemFreeSize
import platform.Foundation.NSNumber
import platform.Foundation.NSTemporaryDirectory
import platform.posix.fclose
import platform.posix.fopen
import platform.posix.fread
import platform.posix.fwrite
import platform.posix.remove
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

/**
 * Staging in the temporary directory, like other partial transfers: the system may clear it,
 * and a file that vanished simply means the download starts again.
 */
@Single(binds = [CatalogueStagingFiles::class])
class IosCatalogueStagingFiles : CatalogueStagingFiles by PosixCatalogueStagingFiles(
    "${NSTemporaryDirectory()}${CatalogueStagingFiles.DIRECTORY}",
)

@OptIn(ExperimentalForeignApi::class)
internal class PosixCatalogueStagingFiles(
    private val root: String,
) : CatalogueStagingFiles {

    @OptIn(ExperimentalUuidApi::class)
    override fun newPartPath(profileId: String): String =
        "$root/${profileId.safeFileName()}/${Uuid.random()}${CatalogueStagingFiles.PART_SUFFIX}"

    override suspend fun openForWriting(path: String): StagingWriter = withContext(Dispatchers.IO) {
        createDirectory(path.substringBeforeLast('/'))
        val file = fopen(path, "wb") ?: error("Could not create the staging file")
        object : StagingWriter {
            private var open = true

            override fun write(buffer: ByteArray, length: Int) {
                if (length == 0) return
                val written = buffer.usePinned { pinned ->
                    fwrite(pinned.addressOf(0), 1.convert(), length.convert(), file).toLong()
                }
                if (written != length.toLong()) error("Could not write to the staging file")
            }

            override fun close() {
                if (!open) return
                open = false
                if (fclose(file) != 0) error("Could not finish the staging file")
            }
        }
    }

    override suspend fun freeSpaceBytes(): Long? = withContext(Dispatchers.IO) {
        createDirectory(root)
        val attributes = NSFileManager.defaultManager.attributesOfFileSystemForPath(root, error = null)
        (attributes?.get(NSFileSystemFreeSize) as? NSNumber)?.longLongValue
    }

    override suspend fun size(path: String): Long = withContext(Dispatchers.IO) {
        val attributes = NSFileManager.defaultManager.attributesOfItemAtPath(path, error = null)
        (attributes?.get(NSFileSize) as? NSNumber)?.longLongValue ?: 0L
    }

    override suspend fun sha256(path: String): String = withContext(Dispatchers.IO) {
        val file = fopen(path, "rb") ?: error("Could not open the staging file")
        try {
            memScoped {
                val context = alloc<CC_SHA256_CTX>()
                CC_SHA256_Init(context.ptr)
                val buffer = ByteArray(HASH_BUFFER_BYTES)
                buffer.usePinned { pinned ->
                    while (true) {
                        val read = fread(pinned.addressOf(0), 1.convert(), buffer.size.convert(), file).toInt()
                        if (read <= 0) break
                        CC_SHA256_Update(context.ptr, pinned.addressOf(0), read.convert())
                    }
                }
                val hash = ByteArray(CC_SHA256_DIGEST_LENGTH)
                hash.usePinned { pinned -> CC_SHA256_Final(pinned.addressOf(0).reinterpret<UByteVar>(), context.ptr) }
                hash.toHex()
            }
        } finally {
            fclose(file)
        }
    }

    override suspend fun rename(from: String, to: String): Boolean = withContext(Dispatchers.IO) {
        val manager = NSFileManager.defaultManager
        if (manager.fileExistsAtPath(to) && !manager.removeItemAtPath(to, error = null)) return@withContext false
        manager.moveItemAtPath(from, toPath = to, error = null)
    }

    override suspend fun delete(path: String) {
        withContext(Dispatchers.IO) { remove(path) }
    }

    private fun createDirectory(path: String) {
        NSFileManager.defaultManager.createDirectoryAtPath(
            path,
            withIntermediateDirectories = true,
            attributes = null,
            error = null,
        )
    }

    private companion object {
        const val HASH_BUFFER_BYTES = 64 * 1024
    }
}
