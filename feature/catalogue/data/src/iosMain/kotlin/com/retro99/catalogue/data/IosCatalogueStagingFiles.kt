package com.retro99.catalogue.data

import com.retro99.base.file.safeFileName
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.ObjCObjectVar
import kotlinx.cinterop.value
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
import platform.Foundation.NSApplicationSupportDirectory
import platform.Foundation.NSSearchPathForDirectoriesInDomains
import platform.Foundation.NSURL
import platform.Foundation.NSURLIsExcludedFromBackupKey
import platform.Foundation.NSUserDomainMask
import platform.posix.fclose
import platform.posix.fopen
import platform.posix.fread
import platform.posix.fwrite
import platform.posix.remove
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

/**
 * Staging under Application Support: unlike the temporary directory, the system does not clear
 * it while Parrot is not running. The folder is marked as excluded from iCloud and device
 * backup, and the queue deletes what it no longer needs.
 */
@Single(binds = [CatalogueStagingFiles::class])
class IosCatalogueStagingFiles : CatalogueStagingFiles by PosixCatalogueStagingFiles(
    root = "${applicationSupportDirectory()}/${CatalogueStagingFiles.DIRECTORY}",
    excludeFromBackup = true,
)

private fun applicationSupportDirectory(): String =
    NSSearchPathForDirectoriesInDomains(NSApplicationSupportDirectory, NSUserDomainMask, true)
        .firstOrNull() as? String ?: error("Could not find the Application Support directory")

@OptIn(ExperimentalForeignApi::class)
internal class PosixCatalogueStagingFiles(
    private val root: String,
    private val excludeFromBackup: Boolean = false,
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

    override suspend fun list(profileId: String): List<String> = withContext(Dispatchers.IO) {
        val directory = "$root/${profileId.safeFileName()}"
        NSFileManager.defaultManager.contentsOfDirectoryAtPath(directory, error = null).orEmpty()
            .filterIsInstance<String>()
            .map { name -> "$directory/$name" }
    }

    override suspend fun deleteFoldersExcept(profileIds: Set<String>) {
        withContext(Dispatchers.IO) {
            val kept = profileIds.map { profileId -> profileId.safeFileName() }.toSet()
            val manager = NSFileManager.defaultManager
            manager.contentsOfDirectoryAtPath(root, error = null).orEmpty()
                .filterIsInstance<String>()
                .filterNot { name -> name in kept }
                .forEach { name -> manager.removeItemAtPath("$root/$name", error = null) }
        }
    }

    /** Whether the staging folder carries the "do not back up" mark. Null when it does not exist. */
    internal fun isExcludedFromBackup(): Boolean? = memScoped {
        val value = alloc<ObjCObjectVar<Any?>>()
        if (!NSURL.fileURLWithPath(root).getResourceValue(value.ptr, forKey = NSURLIsExcludedFromBackupKey, error = null)) {
            return null
        }
        (value.value as? NSNumber)?.boolValue
    }

    private fun createDirectory(path: String) {
        NSFileManager.defaultManager.createDirectoryAtPath(
            path,
            withIntermediateDirectories = true,
            attributes = null,
            error = null,
        )
        // The mark is on the folder, so it covers every file in it. Set each time: a restore
        // or a system update can drop it.
        if (excludeFromBackup) {
            NSURL.fileURLWithPath(root).setResourceValue(NSNumber(bool = true), forKey = NSURLIsExcludedFromBackupKey, error = null)
        }
    }

    private companion object {
        const val HASH_BUFFER_BYTES = 64 * 1024
    }
}
