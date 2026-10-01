package com.retro99.epub.implementation.zip

import kotlinx.cinterop.CPointer
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.UByteVar
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.alloc
import kotlinx.cinterop.convert
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.sizeOf
import kotlinx.cinterop.usePinned
import platform.posix.FILE
import platform.posix.SEEK_END
import platform.posix.SEEK_SET
import platform.posix.fclose
import platform.posix.fopen
import platform.posix.fread
import platform.posix.fseeko
import platform.posix.ftello
import platform.zlib.ZLIB_VERSION
import platform.zlib.Z_FINISH
import platform.zlib.Z_OK
import platform.zlib.Z_STREAM_END
import platform.zlib.inflate
import platform.zlib.inflateEnd
import platform.zlib.inflateInit2_
import platform.zlib.z_stream

@OptIn(ExperimentalForeignApi::class)
internal actual fun openRandomAccessSource(path: String): RandomAccessSource? {
    val file = fopen(path, "rb") ?: return null
    if (fseeko(file, 0, SEEK_END) != 0) {
        fclose(file)
        return null
    }
    val size = ftello(file)
    return PosixRandomAccessSource(file, size)
}

@OptIn(ExperimentalForeignApi::class)
private class PosixRandomAccessSource(
    private val file: CPointer<FILE>,
    override val size: Long,
) : RandomAccessSource {

    override fun readFully(position: Long, length: Int): ByteArray {
        val bytes = ByteArray(length)
        if (length == 0) return bytes
        if (fseeko(file, position, SEEK_SET) != 0) {
            throw ZipFormatException("Seek failed at $position")
        }
        val read = bytes.usePinned { pinned ->
            fread(pinned.addressOf(0), 1.convert(), length.convert(), file).toLong()
        }
        if (read != length.toLong()) throw ZipFormatException("Short read at $position")
        return bytes
    }

    override fun close() {
        fclose(file)
    }
}

@OptIn(ExperimentalForeignApi::class)
internal actual fun inflateRaw(data: ByteArray, uncompressedSize: Int): ByteArray {
    val output = ByteArray(uncompressedSize)
    if (uncompressedSize == 0) return output
    memScoped {
        val stream = alloc<z_stream>()
        // Negative window bits: raw DEFLATE with no zlib header, as ZIP stores it.
        // inflateInit2 is a C macro, so call the function it expands to.
        val initResult = inflateInit2_(
            stream.ptr,
            -15,
            ZLIB_VERSION,
            sizeOf<z_stream>().convert(),
        )
        if (initResult != Z_OK) {
            throw ZipFormatException("inflateInit2 failed")
        }
        try {
            data.usePinned { input ->
                output.usePinned { out ->
                    stream.next_in = input.addressOf(0).reinterpret<UByteVar>()
                    stream.avail_in = data.size.convert()
                    stream.next_out = out.addressOf(0).reinterpret<UByteVar>()
                    stream.avail_out = uncompressedSize.convert()
                    val result = inflate(stream.ptr, Z_FINISH)
                    if (result != Z_STREAM_END && result != Z_OK) {
                        throw ZipFormatException("inflate failed: $result")
                    }
                    if (stream.total_out.toLong() != uncompressedSize.toLong()) {
                        throw ZipFormatException("Truncated DEFLATE data")
                    }
                }
            }
        } finally {
            inflateEnd(stream.ptr)
        }
    }
    return output
}
