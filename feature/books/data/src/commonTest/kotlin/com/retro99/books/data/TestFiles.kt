package com.retro99.books.data

/** Real files in a temporary directory, for code that hashes and sizes files on disk. */
internal expect object TestFiles {
    /** Writes [bytes] to a new file named [name] and returns its absolute path. */
    fun write(name: String, bytes: ByteArray): String

    fun exists(path: String): Boolean

    fun delete(path: String)
}
