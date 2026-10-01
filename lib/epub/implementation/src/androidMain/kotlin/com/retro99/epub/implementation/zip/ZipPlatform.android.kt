package com.retro99.epub.implementation.zip

import java.io.File
import java.io.RandomAccessFile
import java.util.zip.Inflater

internal actual fun openRandomAccessSource(path: String): RandomAccessSource? {
    val file = File(path)
    if (!file.isFile) return null
    return FileRandomAccessSource(RandomAccessFile(file, "r"))
}

private class FileRandomAccessSource(
    private val file: RandomAccessFile,
) : RandomAccessSource {
    override val size: Long = file.length()

    override fun readFully(position: Long, length: Int): ByteArray {
        val bytes = ByteArray(length)
        file.seek(position)
        file.readFully(bytes)
        return bytes
    }

    override fun close() = file.close()
}

internal actual fun inflateRaw(data: ByteArray, uncompressedSize: Int): ByteArray {
    val inflater = Inflater(true)
    try {
        inflater.setInput(data)
        val output = ByteArray(uncompressedSize)
        var written = 0
        while (written < uncompressedSize && !inflater.finished()) {
            val count = inflater.inflate(output, written, uncompressedSize - written)
            if (count == 0 && (inflater.needsInput() || inflater.needsDictionary())) break
            written += count
        }
        if (written != uncompressedSize) throw ZipFormatException("Truncated DEFLATE data")
        return output
    } finally {
        inflater.end()
    }
}
