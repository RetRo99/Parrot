package com.retro99.books.data

import java.io.File
import java.nio.file.Files

internal actual object TestFiles {
    private val directory: File by lazy {
        Files.createTempDirectory("books-data-test").toFile().apply { deleteOnExit() }
    }

    actual fun write(name: String, bytes: ByteArray): String =
        File(directory, name).apply { writeBytes(bytes) }.absolutePath

    actual fun exists(path: String): Boolean = File(path).exists()

    actual fun delete(path: String) {
        File(path).delete()
    }
}
