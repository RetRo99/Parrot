package com.retro99.parrot.di

import java.io.ByteArrayOutputStream
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

internal object CatalogueTestEpub {
    /** The smallest file the EPUB check accepts: mimetype first and stored, a container, a package, one chapter. */
    val BYTES: ByteArray = ByteArrayOutputStream().also { bytes ->
        ZipOutputStream(bytes).use { zip ->
            val mimetype = "application/epub+zip".toByteArray()
            zip.putNextEntry(
                ZipEntry("mimetype").apply {
                    method = ZipEntry.STORED
                    size = mimetype.size.toLong()
                    compressedSize = mimetype.size.toLong()
                    crc = CRC32().apply { update(mimetype) }.value
                },
            )
            zip.write(mimetype)
            zip.closeEntry()
            mapOf(
                "META-INF/container.xml" to """<?xml version="1.0"?><container version="1.0" xmlns="urn:oasis:names:tc:opendocument:xmlns:container"><rootfiles><rootfile full-path="OEBPS/content.opf" media-type="application/oebps-package+xml"/></rootfiles></container>""",
                "OEBPS/content.opf" to """<?xml version="1.0"?><package xmlns="http://www.idpf.org/2007/opf" version="3.0" unique-identifier="id"><metadata xmlns:dc="http://purl.org/dc/elements/1.1/"><dc:identifier id="id">urn:book:1</dc:identifier><dc:title>A Catalogue Book</dc:title><dc:language>en</dc:language></metadata><manifest><item id="c1" href="chapter1.xhtml" media-type="application/xhtml+xml"/></manifest><spine><itemref idref="c1"/></spine></package>""",
                "OEBPS/chapter1.xhtml" to """<?xml version="1.0"?><html xmlns="http://www.w3.org/1999/xhtml"><head><title>One</title></head><body><p>Text.</p></body></html>""",
            ).forEach { (path, content) ->
                zip.putNextEntry(ZipEntry(path))
                zip.write(content.toByteArray())
                zip.closeEntry()
            }
        }
    }.toByteArray()
}
