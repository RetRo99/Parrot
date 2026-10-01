package com.retro99.epub.implementation

import com.retro99.epub.implementation.text.MarkupToken
import com.retro99.epub.implementation.text.scanMarkup
import com.retro99.epub.implementation.zip.ZipArchive
import com.retro99.epub.implementation.zip.ZipFormatException

internal data class ManifestItem(
    val id: String,
    /** The resource's path inside the archive. */
    val path: String,
    val mediaType: String,
    val mediaOverlayId: String?,
)

/** The parts of an EPUB package document (OPF) that text and timing reading need. */
internal data class EpubPackage(
    val manifest: Map<String, ManifestItem>,
    /** Linear spine items in reading order. */
    val readingOrder: List<ManifestItem>,
    /** The book's `media:duration`, when the package declares one. */
    val mediaDurationText: String?,
) {
    companion object {
        const val MAX_PACKAGE_BYTES = 10L * 1024 * 1024

        fun read(archive: ZipArchive): EpubPackage {
            val container = archive.read("META-INF/container.xml", MAX_PACKAGE_BYTES)
                ?.decodeToString()
                ?: throw ZipFormatException("No META-INF/container.xml")
            var opfPath: String? = null
            scanMarkup(container) { token ->
                if (opfPath == null && token is MarkupToken.StartTag && token.name == "rootfile") {
                    opfPath = token.attributes["full-path"]
                }
            }
            val packagePath = opfPath ?: throw ZipFormatException("No rootfile")
            val opf = archive.read(packagePath, MAX_PACKAGE_BYTES)?.decodeToString()
                ?: throw ZipFormatException("Missing package document $packagePath")
            return parse(opf, packagePath)
        }

        fun parse(opf: String, packagePath: String): EpubPackage {
            val manifest = LinkedHashMap<String, ManifestItem>()
            val spine = mutableListOf<String>()
            var mediaDuration: String? = null
            var readingDuration = false
            scanMarkup(opf) { token ->
                when (token) {
                    is MarkupToken.StartTag -> when (token.name) {
                        "item" -> {
                            val id = token.attributes["id"]
                            val href = token.attributes["href"]
                            if (id != null && href != null) {
                                manifest[id] = ManifestItem(
                                    id = id,
                                    path = resolvePath(packagePath, href),
                                    mediaType = token.attributes["media-type"].orEmpty(),
                                    mediaOverlayId = token.attributes["media-overlay"],
                                )
                            }
                        }
                        "itemref" -> {
                            val idref = token.attributes["idref"]
                            if (idref != null && token.attributes["linear"] != "no") {
                                spine += idref
                            }
                        }
                        "meta" -> {
                            readingDuration = token.attributes["property"] == "media:duration" &&
                                token.attributes["refines"] == null && !token.selfClosing
                        }
                    }
                    is MarkupToken.Text -> if (readingDuration && mediaDuration == null) {
                        mediaDuration = token.text.trim()
                    }
                    is MarkupToken.EndTag -> if (token.name == "meta") readingDuration = false
                }
            }
            return EpubPackage(
                manifest = manifest,
                readingOrder = spine.mapNotNull { idref -> manifest[idref] },
                mediaDurationText = mediaDuration,
            )
        }
    }
}

/**
 * Resolves [href] (URL-encoded, relative to [basePath]'s folder, maybe with a fragment) to a
 * path inside the archive. The fragment is dropped.
 */
internal fun resolvePath(basePath: String, href: String): String {
    val withoutFragment = percentDecode(href.substringBefore('#'))
    if (withoutFragment.startsWith("/")) return normalizePath(withoutFragment.removePrefix("/"))
    val folder = basePath.substringBeforeLast('/', missingDelimiterValue = "")
    val joined = if (folder.isEmpty()) withoutFragment else "$folder/$withoutFragment"
    return normalizePath(joined)
}

private fun normalizePath(path: String): String {
    val parts = ArrayDeque<String>()
    path.split('/').forEach { part ->
        when (part) {
            "", "." -> Unit
            ".." -> parts.removeLastOrNull()
            else -> parts.addLast(part)
        }
    }
    return parts.joinToString("/")
}

internal fun percentDecode(input: String): String {
    if ('%' !in input) return input
    val bytes = ArrayList<Byte>(input.length)
    var index = 0
    while (index < input.length) {
        val char = input[index]
        if (char == '%' && index + 2 < input.length) {
            val value = input.substring(index + 1, index + 3).toIntOrNull(16)
            if (value != null) {
                bytes.add(value.toByte())
                index += 3
                continue
            }
        }
        char.toString().encodeToByteArray().forEach { byte -> bytes.add(byte) }
        index++
    }
    return bytes.toByteArray().decodeToString()
}
