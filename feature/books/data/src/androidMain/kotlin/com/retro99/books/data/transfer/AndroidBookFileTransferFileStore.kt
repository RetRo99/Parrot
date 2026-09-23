package com.retro99.books.data.transfer

import android.content.Context
import java.io.File
import java.io.FileOutputStream
import java.io.RandomAccessFile
import org.koin.core.annotation.Provided
import org.koin.core.annotation.Single

@Single(binds = [BookFileTransferFileStore::class])
class AndroidBookFileTransferFileStore(
    @Provided private val context: Context,
) : BookFileTransferFileStore {
    override fun stagingPath(transferId: String): String =
        File(stagingDirectory, "${transferId.safeName()}.part").absolutePath

    override fun importedFilePath(localUuid: String, mediaType: String): String =
        File(ebooksDirectory, "${localUuid.safeName()}_${mediaType.safeName()}.epub").absolutePath

    override suspend fun exists(path: String): Boolean = File(path).exists()

    override suspend fun size(path: String): Long = File(path).length()

    override suspend fun truncate(path: String) {
        File(path).parentFile?.mkdirs()
        FileOutputStream(path, false).use { }
    }

    override suspend fun write(path: String, offset: Long, bytes: ByteArray) {
        val file = File(path)
        file.parentFile?.mkdirs()
        RandomAccessFile(file, "rw").use { output ->
            output.seek(offset)
            output.write(bytes)
        }
    }

    override suspend fun moveToImportedStore(stagingPath: String, destinationPath: String) {
        val source = File(stagingPath)
        val destination = File(destinationPath)
        destination.parentFile?.mkdirs()
        if (destination.exists() && !destination.delete()) error("Could not replace restored book file")
        if (source.renameTo(destination)) return

        val temporaryDestination = File(destination.parentFile, "${destination.name}.part")
        if (temporaryDestination.exists()) temporaryDestination.delete()
        source.copyTo(temporaryDestination, overwrite = true)
        if (!temporaryDestination.renameTo(destination)) {
            temporaryDestination.delete()
            error("Could not atomically move restored book into the import store")
        }
        if (!source.delete()) error("Restored book was saved but its staging file could not be removed")
    }

    override suspend fun writeCover(localUuid: String, bytes: ByteArray): String {
        val cover = File(coversDirectory, "${localUuid.safeName()}.png")
        cover.writeBytes(bytes)
        return cover.absolutePath
    }

    override suspend fun delete(path: String): Boolean {
        val file = File(path)
        return !file.exists() || file.delete()
    }

    private val stagingDirectory: File
        get() = File(context.filesDir, "book_file_transfers").apply { mkdirs() }

    private val ebooksDirectory: File
        get() = File(context.filesDir, "ebooks").apply { mkdirs() }

    private val coversDirectory: File
        get() = File(context.filesDir, "imported_covers").apply { mkdirs() }
}

private fun String.safeName(): String = map { character ->
    if (character.isLetterOrDigit() || character == '-' || character == '_') character else '_'
}.joinToString("")
