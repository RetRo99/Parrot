package com.retro99.epub.implementation.zip

/** Random reads from a file, so a ZIP can be read entry by entry without loading it whole. */
interface RandomAccessSource : AutoCloseable {
    val size: Long

    /** Exactly [length] bytes starting at [position]. */
    fun readFully(position: Long, length: Int): ByteArray
}

/** Opens [path] for random reads, or null when it doesn't exist or can't be opened. */
internal expect fun openRandomAccessSource(path: String): RandomAccessSource?

/** Inflates raw DEFLATE data (no zlib header), as ZIP entries store it. */
internal expect fun inflateRaw(data: ByteArray, uncompressedSize: Int): ByteArray

class ZipFormatException(message: String) : Exception(message)

data class ZipEntry(
    val name: String,
    val method: Int,
    val compressedSize: Long,
    val uncompressedSize: Long,
    val localHeaderOffset: Long,
    /** General purpose bit flags; bit 0 set means the entry's data is encrypted. */
    val flags: Int = 0,
)

/**
 * What the end of the archive says about its layout. A plain ZIP has its directory right
 * before the end record, and nothing after that record's comment.
 */
data class ZipLayout(
    val declaredEntryCount: Long,
    val directoryOffset: Long,
    val directorySize: Long,
    /** Where the end record (or the ZIP64 records before it) begins. */
    val directoryLimit: Long,
    /** Bytes in the file after the end record and its comment. */
    val trailingBytes: Long,
)

/**
 * A ZIP archive read through its central directory. Only the directory and the entries asked
 * for are read, one at a time, so multi-GB read-aloud EPUBs are never loaded whole. ZIP64
 * archives (over 4 GB, or over 65535 entries) are supported.
 */
class ZipArchive private constructor(
    private val source: RandomAccessSource,
    val entries: Map<String, ZipEntry>,
    /** Every directory record in directory order, repeated names included. */
    val directoryEntries: List<ZipEntry>,
    val layout: ZipLayout,
) : AutoCloseable {

    /**
     * The entry's bytes, or null when it's missing, larger than [maxBytes] uncompressed, or
     * stored with a method other than STORED or DEFLATE.
     */
    fun read(name: String, maxBytes: Long): ByteArray? {
        val entry = entries[name] ?: return null
        if (entry.uncompressedSize > maxBytes || entry.compressedSize > maxBytes) return null
        val header = source.readFully(entry.localHeaderOffset, LOCAL_HEADER_SIZE)
        if (header.int32(0) != LOCAL_HEADER_SIGNATURE) {
            throw ZipFormatException("Bad local header for $name")
        }
        val dataOffset = entry.localHeaderOffset + LOCAL_HEADER_SIZE +
            header.uint16(26) + header.uint16(28)
        val data = source.readFully(dataOffset, entry.compressedSize.toInt())
        return when (entry.method) {
            METHOD_STORED -> data
            METHOD_DEFLATED -> inflateRaw(data, entry.uncompressedSize.toInt())
            else -> null
        }
    }

    override fun close() = source.close()

    companion object {
        private const val LOCAL_HEADER_SIGNATURE = 0x04034b50
        private const val CENTRAL_HEADER_SIGNATURE = 0x02014b50
        private const val END_SIGNATURE = 0x06054b50
        private const val ZIP64_END_SIGNATURE = 0x06064b50
        private const val ZIP64_LOCATOR_SIGNATURE = 0x07064b50
        private const val LOCAL_HEADER_SIZE = 30
        private const val CENTRAL_HEADER_SIZE = 46
        private const val END_SIZE = 22
        private const val ZIP64_LOCATOR_SIZE = 20
        private const val ZIP64_END_SIZE = 56
        private const val MAX_COMMENT = 65_535
        private val TAIL_WINDOWS = listOf(END_SIZE, END_SIZE + 1024, END_SIZE + MAX_COMMENT)
        private const val MAX_DIRECTORY_BYTES = 64L * 1024 * 1024
        private const val ZIP64_EXTRA_ID = 0x0001
        const val METHOD_STORED = 0
        const val METHOD_DEFLATED = 8

        /** Opens [source]; it's closed again when it isn't a readable ZIP. */
        fun open(source: RandomAccessSource): ZipArchive = try {
            readDirectory(source)
        } catch (exception: Exception) {
            source.close()
            throw exception
        }

        private fun readDirectory(source: RandomAccessSource): ZipArchive {
            if (source.size < END_SIZE) throw ZipFormatException("Too small to be a ZIP")
            // The end record is usually the last 22 bytes. Only an archive comment pushes it
            // further back, so the tail is widened only then: reading a big tail would touch
            // the data of the last entry, which in a read-aloud is audio.
            var tailStart = 0L
            var tail = ByteArray(0)
            var endIndex = -1
            for (window in TAIL_WINDOWS) {
                val tailLength = minOf(source.size, window.toLong()).toInt()
                tailStart = source.size - tailLength
                tail = source.readFully(tailStart, tailLength)
                endIndex = (tailLength - END_SIZE downTo 0)
                    .firstOrNull { index -> tail.int32(index) == END_SIGNATURE }
                    ?: -1
                if (endIndex >= 0 || tailLength.toLong() == source.size) break
            }
            if (endIndex < 0) throw ZipFormatException("No end of central directory")

            val commentLength = tail.uint16(endIndex + 20)
            var directoryLimit = tailStart + endIndex
            var entryCount = tail.uint16(endIndex + 10).toLong()
            var directorySize = tail.uint32(endIndex + 12)
            var directoryOffset = tail.uint32(endIndex + 16)
            val endPosition = tailStart + endIndex
            if (endPosition >= ZIP64_LOCATOR_SIZE) {
                val locator = source.readFully(endPosition - ZIP64_LOCATOR_SIZE, ZIP64_LOCATOR_SIZE)
                if (locator.int32(0) == ZIP64_LOCATOR_SIGNATURE) {
                    val zip64End = source.readFully(locator.int64(8), ZIP64_END_SIZE)
                    if (zip64End.int32(0) != ZIP64_END_SIGNATURE) {
                        throw ZipFormatException("Bad ZIP64 end of central directory")
                    }
                    entryCount = zip64End.int64(32)
                    directorySize = zip64End.int64(40)
                    directoryOffset = zip64End.int64(48)
                    directoryLimit = locator.int64(8)
                }
            }
            if (directorySize > MAX_DIRECTORY_BYTES) {
                throw ZipFormatException("Central directory too large")
            }
            val directory = source.readFully(directoryOffset, directorySize.toInt())
            val all = parseDirectory(directory, entryCount)
            val byName = LinkedHashMap<String, ZipEntry>()
            all.forEach { entry -> byName[entry.name] = entry }
            val layout = ZipLayout(
                declaredEntryCount = entryCount,
                directoryOffset = directoryOffset,
                directorySize = directorySize,
                directoryLimit = directoryLimit,
                trailingBytes = source.size - (endPosition + END_SIZE + commentLength),
            )
            return ZipArchive(source, byName, all, layout)
        }

        private fun parseDirectory(directory: ByteArray, entryCount: Long): List<ZipEntry> {
            val entries = ArrayList<ZipEntry>()
            var offset = 0
            var index = 0L
            while (index < entryCount && offset + CENTRAL_HEADER_SIZE <= directory.size) {
                if (directory.int32(offset) != CENTRAL_HEADER_SIGNATURE) {
                    throw ZipFormatException("Bad central directory entry")
                }
                val flags = directory.uint16(offset + 8)
                val method = directory.uint16(offset + 10)
                var compressedSize = directory.uint32(offset + 20)
                var uncompressedSize = directory.uint32(offset + 24)
                val nameLength = directory.uint16(offset + 28)
                val extraLength = directory.uint16(offset + 30)
                val commentLength = directory.uint16(offset + 32)
                var localHeaderOffset = directory.uint32(offset + 42)
                val nameStart = offset + CENTRAL_HEADER_SIZE
                val name = directory.decodeToString(nameStart, nameStart + nameLength)

                // ZIP64: the real values follow in the extra field, in this order, for each
                // value that is saturated in the header.
                var extra = nameStart + nameLength
                val extraEnd = extra + extraLength
                while (extra + 4 <= extraEnd) {
                    val id = directory.uint16(extra)
                    val size = directory.uint16(extra + 2)
                    if (id == ZIP64_EXTRA_ID) {
                        var field = extra + 4
                        if (uncompressedSize == UINT32_MAX) {
                            uncompressedSize = directory.int64(field)
                            field += 8
                        }
                        if (compressedSize == UINT32_MAX) {
                            compressedSize = directory.int64(field)
                            field += 8
                        }
                        if (localHeaderOffset == UINT32_MAX) {
                            localHeaderOffset = directory.int64(field)
                        }
                    }
                    extra += 4 + size
                }

                entries += ZipEntry(
                    name = name,
                    method = method,
                    compressedSize = compressedSize,
                    uncompressedSize = uncompressedSize,
                    localHeaderOffset = localHeaderOffset,
                    flags = flags,
                )
                offset = extraEnd + commentLength
                index++
            }
            return entries
        }

        private const val UINT32_MAX = 0xFFFFFFFFL
    }
}

private fun ByteArray.uint16(index: Int): Int =
    (this[index].toInt() and 0xFF) or ((this[index + 1].toInt() and 0xFF) shl 8)

private fun ByteArray.int32(index: Int): Int =
    uint16(index) or (uint16(index + 2) shl 16)

private fun ByteArray.uint32(index: Int): Long = int32(index).toLong() and 0xFFFFFFFFL

private fun ByteArray.int64(index: Int): Long = uint32(index) or (uint32(index + 4) shl 32)
