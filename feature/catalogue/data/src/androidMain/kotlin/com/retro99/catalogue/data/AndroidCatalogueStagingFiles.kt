package com.retro99.catalogue.data

import android.content.Context
import com.retro99.base.file.safeFileName
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.koin.core.annotation.Provided
import org.koin.core.annotation.Single
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

/**
 * Staging under `noBackupFilesDir`: the system does not clear it during a download the way it
 * can clear the cache, and it is left out of device backup.
 */
@Single(binds = [CatalogueStagingFiles::class])
class AndroidCatalogueStagingFiles(
    @Provided context: Context,
) : CatalogueStagingFiles by JvmCatalogueStagingFiles({ File(context.noBackupFilesDir, CatalogueStagingFiles.DIRECTORY) })

internal class JvmCatalogueStagingFiles(
    private val root: () -> File,
) : CatalogueStagingFiles {

    @OptIn(ExperimentalUuidApi::class)
    override fun newPartPath(profileId: String): String =
        File(File(root(), profileId.safeFileName()), "${Uuid.random()}${CatalogueStagingFiles.PART_SUFFIX}").absolutePath

    override suspend fun openForWriting(path: String): StagingWriter = withContext(Dispatchers.IO) {
        val file = File(path)
        file.parentFile?.mkdirs()
        val output = FileOutputStream(file, false)
        object : StagingWriter {
            override fun write(buffer: ByteArray, length: Int) = output.write(buffer, 0, length)
            override fun close() = output.close()
        }
    }

    override suspend fun freeSpaceBytes(): Long? = withContext(Dispatchers.IO) {
        val directory = root().also { it.mkdirs() }
        // 0 is also what the JDK answers when it cannot tell.
        directory.usableSpace.takeIf { it > 0 || directory.exists() }
    }

    override suspend fun size(path: String): Long = withContext(Dispatchers.IO) { File(path).length() }

    override suspend fun sha256(path: String): String = withContext(Dispatchers.IO) {
        val digest = MessageDigest.getInstance("SHA-256")
        File(path).inputStream().use { input ->
            val buffer = ByteArray(HASH_BUFFER_BYTES)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                digest.update(buffer, 0, read)
            }
        }
        digest.digest().toHex()
    }

    override suspend fun rename(from: String, to: String): Boolean = withContext(Dispatchers.IO) {
        val target = File(to)
        if (target.exists() && !target.delete()) return@withContext false
        File(from).renameTo(target)
    }

    override suspend fun delete(path: String) {
        withContext(Dispatchers.IO) { runCatching { File(path).delete() } }
    }

    private companion object {
        const val HASH_BUFFER_BYTES = 64 * 1024
    }
}
