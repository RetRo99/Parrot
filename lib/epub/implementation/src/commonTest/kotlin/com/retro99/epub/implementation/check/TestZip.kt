package com.retro99.epub.implementation.check

import com.retro99.epub.implementation.zip.RandomAccessSource

/** A ZIP entry for a test archive. The overrides exist to write archives that lie. */
internal class TestEntry(
    val name: String,
    val data: ByteArray,
    /** Stored as DEFLATE (one uncompressed block), so reading it goes through the inflater. */
    val deflated: Boolean = false,
    val flags: Int = 0,
    /** What the directory claims the entry unpacks to, when not the truth. */
    val claimedUncompressedSize: Long? = null,
    /** Point the directory record at another entry's data instead of writing this one. */
    val sharesDataWith: String? = null,
) {
    constructor(name: String, text: String, deflated: Boolean = false) : this(name, text.encodeToByteArray(), deflated)
}

/** Writes small ZIP archives byte by byte, so tests need no platform ZIP library. */
internal object TestZip {
    fun build(
        entries: List<TestEntry>,
        prefix: ByteArray = ByteArray(0),
        suffix: ByteArray = ByteArray(0),
        claimedEntryCount: Int? = null,
    ): ByteArray {
        val out = Bytes()
        out.raw(prefix)
        val offsets = LinkedHashMap<String, Int>()
        val stored = LinkedHashMap<String, ByteArray>()
        entries.filter { entry -> entry.sharesDataWith == null }.forEach { entry ->
            val name = entry.name.encodeToByteArray()
            val payload = if (entry.deflated) deflateStored(entry.data) else entry.data
            offsets[entry.name] = out.size - prefix.size
            stored[entry.name] = payload
            out.int(0x04034b50).short(20).short(entry.flags).short(if (entry.deflated) 8 else 0)
            out.short(0).short(0).int(0).int(payload.size).int(entry.data.size)
            out.short(name.size).short(0).raw(name).raw(payload)
        }
        val directoryStart = out.size - prefix.size
        entries.forEach { entry ->
            val target = entry.sharesDataWith ?: entry.name
            val source = entries.first { other -> other.name == target }
            val name = entry.name.encodeToByteArray()
            val uncompressed = entry.claimedUncompressedSize ?: source.data.size.toLong()
            out.int(0x02014b50).short(20).short(20).short(entry.flags).short(if (source.deflated) 8 else 0)
            out.short(0).short(0).int(0).int(stored.getValue(target).size).int(uncompressed.toInt())
            out.short(name.size).short(0).short(0).short(0).short(0).int(0)
            out.int(offsets.getValue(target)).raw(name)
        }
        val directorySize = out.size - prefix.size - directoryStart
        val count = claimedEntryCount ?: entries.size
        out.int(0x06054b50).short(0).short(0).short(count).short(count)
        out.int(directorySize).int(directoryStart).short(0)
        out.raw(suffix)
        return out.toByteArray()
    }

    /** Raw DEFLATE made of uncompressed blocks: valid input for any inflater. */
    private fun deflateStored(data: ByteArray): ByteArray {
        val out = Bytes()
        var offset = 0
        do {
            val length = minOf(65_535, data.size - offset)
            val last = offset + length >= data.size
            out.byte(if (last) 1 else 0).short(length).short(length.inv() and 0xFFFF)
            out.raw(data.copyOfRange(offset, offset + length))
            offset += length
        } while (offset < data.size)
        return out.toByteArray()
    }

    private class Bytes {
        private var buffer = ByteArray(1024)
        var size = 0
            private set

        fun byte(value: Int) = apply {
            if (size == buffer.size) buffer = buffer.copyOf(buffer.size * 2)
            buffer[size++] = value.toByte()
        }
        fun short(value: Int) = byte(value and 0xFF).byte((value shr 8) and 0xFF)
        fun int(value: Int) = short(value and 0xFFFF).short((value ushr 16) and 0xFFFF)
        fun raw(bytes: ByteArray) = apply {
            while (size + bytes.size > buffer.size) buffer = buffer.copyOf(buffer.size * 2)
            bytes.copyInto(buffer, size)
            size += bytes.size
        }
        fun toByteArray(): ByteArray = buffer.copyOf(size)
    }
}

internal class ByteArraySource(private val bytes: ByteArray) : RandomAccessSource {
    var closed = false
        private set
    override val size: Long = bytes.size.toLong()

    override fun readFully(position: Long, length: Int): ByteArray {
        if (position < 0 || length < 0 || position + length > bytes.size) error("Read past the end")
        return bytes.copyOfRange(position.toInt(), position.toInt() + length)
    }

    override fun close() {
        closed = true
    }
}

/** The files of a minimal EPUB, in the order an EPUB writer puts them. */
internal object TestEpub {
    const val CONTAINER = """<?xml version="1.0"?>
<container version="1.0" xmlns="urn:oasis:names:tc:opendocument:xmlns:container">
  <rootfiles>
    <rootfile full-path="OEBPS/content.opf" media-type="application/oebps-package+xml"/>
  </rootfiles>
</container>"""

    const val PACKAGE = """<?xml version="1.0" encoding="UTF-8"?>
<package xmlns="http://www.idpf.org/2007/opf" version="3.0" unique-identifier="id">
  <metadata xmlns:dc="http://purl.org/dc/elements/1.1/"><dc:title>The Lantern Ferry</dc:title></metadata>
  <manifest><item id="c1" href="chapter1.xhtml" media-type="application/xhtml+xml"/></manifest>
  <spine><itemref idref="c1"/></spine>
</package>"""

    const val CHAPTER = "<html><body><p>The ferry left at dusk.</p></body></html>"

    fun entries(deflated: Boolean = false): List<TestEntry> = listOf(
        TestEntry("mimetype", "application/epub+zip"),
        TestEntry("META-INF/container.xml", CONTAINER, deflated),
        TestEntry("OEBPS/content.opf", PACKAGE, deflated),
        TestEntry("OEBPS/chapter1.xhtml", CHAPTER, deflated),
    )

    fun valid(deflated: Boolean = false): ByteArray = TestZip.build(entries(deflated))

    fun encryption(algorithm: String, uri: String): String = """<?xml version="1.0"?>
<encryption xmlns="urn:oasis:names:tc:opendocument:xmlns:container" xmlns:enc="http://www.w3.org/2001/04/xmlenc#">
  <enc:EncryptedData>
    <enc:EncryptionMethod Algorithm="$algorithm"/>
    <enc:CipherData><enc:CipherReference URI="$uri"/></enc:CipherData>
  </enc:EncryptedData>
</encryption>"""
}
