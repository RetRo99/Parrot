package com.retro99.epub.implementation.check

import com.retro99.epub.api.EpubFileCheck
import com.retro99.epub.api.EpubFileChecker
import com.retro99.epub.api.EpubFileLimits
import com.retro99.epub.api.EpubFileProblem
import com.retro99.epub.implementation.text.MarkupToken
import com.retro99.epub.implementation.text.scanMarkup
import com.retro99.epub.implementation.zip.RandomAccessSource
import com.retro99.epub.implementation.zip.ZipArchive
import com.retro99.epub.implementation.zip.ZipEntry
import com.retro99.epub.implementation.zip.openRandomAccessSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.withContext
import org.koin.core.annotation.Single

@Single(binds = [EpubFileChecker::class])
class EpubFileCheckerImpl : EpubFileChecker {
    // Tests swap this to check archives built in memory.
    internal var openSource: (String) -> RandomAccessSource? = ::openRandomAccessSource

    override suspend fun check(filePath: String): EpubFileCheck = withContext(Dispatchers.IO) {
        val source = try {
            openSource(filePath)
        } catch (_: Exception) {
            null
        } ?: return@withContext EpubFileCheck.NotAnEpub(EpubFileProblem.Unreadable)
        checkEpub(source)
    }
}

private const val LOCAL_HEADER_SIGNATURE = 0x04034b50
private const val LOCAL_HEADER_SIZE = 30L
private const val FLAG_ENCRYPTED = 0x1
private const val MIMETYPE_ENTRY = "mimetype"
private const val EPUB_MIMETYPE = "application/epub+zip"
private const val CONTAINER_ENTRY = "META-INF/container.xml"
private const val ENCRYPTION_ENTRY = "META-INF/encryption.xml"
private const val MAX_MIMETYPE_BYTES = 64L
private const val MAX_META_INF_BYTES = 1L * 1024 * 1024
private const val MAX_PACKAGE_BYTES = 10L * 1024 * 1024

/** Font obfuscation (IDPF and Adobe). It scrambles embedded fonts and is not DRM. */
private val FONT_OBFUSCATION_ALGORITHMS = setOf(
    "http://www.idpf.org/2008/embedding",
    "http://ns.adobe.com/pdf/enc#rc",
)

/** Checks [source] and closes it. */
internal fun checkEpub(source: RandomAccessSource): EpubFileCheck {
    fun invalid(problem: EpubFileProblem) = EpubFileCheck.NotAnEpub(problem)
    try {
        if (source.size == 0L) {
            source.close()
            return invalid(EpubFileProblem.Empty)
        }
        if (source.size < 4 || source.readFully(0, 4).littleEndianInt() != LOCAL_HEADER_SIGNATURE) {
            source.close()
            return invalid(EpubFileProblem.NotAZip)
        }
    } catch (_: Exception) {
        runCatching { source.close() }
        return invalid(EpubFileProblem.NotAZip)
    }
    val archive = try {
        ZipArchive.open(source)
    } catch (_: Exception) {
        // No end record or a broken directory: a truncated download lands here.
        return invalid(EpubFileProblem.NotAZip)
    }
    return archive.use { zip ->
        try {
            checkArchive(zip, source.size)
        } catch (_: Exception) {
            invalid(EpubFileProblem.MalformedArchive)
        }
    }
}

private fun checkArchive(archive: ZipArchive, fileSize: Long): EpubFileCheck {
    fun invalid(problem: EpubFileProblem) = EpubFileCheck.NotAnEpub(problem)
    val entries = archive.directoryEntries
    val layout = archive.layout

    if (layout.declaredEntryCount > EpubFileLimits.MAX_ENTRIES || entries.size > EpubFileLimits.MAX_ENTRIES) {
        return invalid(EpubFileProblem.TooManyEntries)
    }
    if (entries.isEmpty() || entries.size.toLong() != layout.declaredEntryCount) {
        return invalid(EpubFileProblem.MalformedArchive)
    }
    var uncompressed = 0L
    for (entry in entries) {
        if (entry.uncompressedSize < 0 || entry.compressedSize < 0) return invalid(EpubFileProblem.MalformedArchive)
        uncompressed += entry.uncompressedSize
        if (uncompressed > EpubFileLimits.MAX_UNCOMPRESSED_BYTES) return invalid(EpubFileProblem.TooLargeUncompressed)
    }
    if (uncompressed > EpubFileLimits.RATIO_CHECK_FLOOR_BYTES &&
        uncompressed / EpubFileLimits.MAX_EXPANSION_RATIO > fileSize
    ) {
        return invalid(EpubFileProblem.TooLargeUncompressed)
    }
    if (entries.any { entry -> !isSafeEntryPath(entry.name) }) return invalid(EpubFileProblem.UnsafeEntryPath)
    if (!hasPlainLayout(archive, entries)) return invalid(EpubFileProblem.MalformedArchive)

    val first = entries.first()
    if (first.name != MIMETYPE_ENTRY || first.localHeaderOffset != 0L) return invalid(EpubFileProblem.MimetypeNotFirst)
    val mimetype = archive.read(MIMETYPE_ENTRY, MAX_MIMETYPE_BYTES)?.decodeToString()?.trim()
    if (mimetype != EPUB_MIMETYPE) return invalid(EpubFileProblem.WrongMimetype)

    val container = archive.read(CONTAINER_ENTRY, MAX_META_INF_BYTES)?.decodeToString()
        ?: return invalid(EpubFileProblem.NoContainer)
    var packagePath: String? = null
    scanMarkup(container) { token ->
        if (packagePath == null && token is MarkupToken.StartTag && token.name == "rootfile") {
            packagePath = token.attributes["full-path"]
        }
    }
    val path = packagePath?.takeIf { it.isNotBlank() } ?: return invalid(EpubFileProblem.NoPackageDocument)
    val packageEntry = archive.entries[path] ?: return invalid(EpubFileProblem.NoPackageDocument)
    val packageDocument = archive.read(packageEntry.name, MAX_PACKAGE_BYTES)
        ?: return invalid(EpubFileProblem.NoPackageDocument)
    // The package document must be XML with a <package> root, not another archive or a stub.
    if (!looksLikePackageDocument(packageDocument)) return invalid(EpubFileProblem.NoPackageDocument)

    if (entries.any { entry -> entry.flags and FLAG_ENCRYPTED != 0 }) return EpubFileCheck.Protected
    if (archive.entries.containsKey(ENCRYPTION_ENTRY)) {
        val encryption = archive.read(ENCRYPTION_ENTRY, MAX_META_INF_BYTES)?.decodeToString()
            // Too large to inspect or stored in a way we cannot read: do not guess it is harmless.
            ?: return EpubFileCheck.Protected
        if (encryptsContent(encryption)) return EpubFileCheck.Protected
    }
    return EpubFileCheck.Valid
}

/**
 * True when the archive is laid out the way a ZIP writer lays it out: the first entry at byte 0,
 * no entry reaching into the next one or into the directory, no name used twice, the directory
 * directly before the end record and nothing after it. This is what rules out a ZIP glued onto
 * another file, an archive hidden inside or after the real one, and entries that share data.
 */
private fun hasPlainLayout(archive: ZipArchive, entries: List<ZipEntry>): Boolean {
    val layout = archive.layout
    if (layout.trailingBytes != 0L) return false
    if (layout.directoryOffset < 0 || layout.directoryOffset + layout.directorySize != layout.directoryLimit) return false
    if (entries.map { entry -> entry.name }.toSet().size != entries.size) return false
    val byOffset = entries.sortedBy { entry -> entry.localHeaderOffset }
    if (byOffset.first().localHeaderOffset != 0L) return false
    for (index in byOffset.indices) {
        val entry = byOffset[index]
        // The local header's extra field can only make the real end later than this.
        val minimumEnd = entry.localHeaderOffset + LOCAL_HEADER_SIZE +
            entry.name.encodeToByteArray().size + entry.compressedSize
        val limit = byOffset.getOrNull(index + 1)?.localHeaderOffset ?: layout.directoryOffset
        if (minimumEnd > limit) return false
    }
    return true
}

internal fun isSafeEntryPath(name: String): Boolean {
    if (name.isEmpty() || name.startsWith('/') || '\\' in name) return false
    if (name.any { character -> character.code < 0x20 || character.code == 0x7f }) return false
    // A drive letter is an absolute path on some systems.
    if (name.length >= 2 && name[1] == ':' && name[0].isLetter()) return false
    return name.split('/').none { segment -> segment == ".." }
}

private fun looksLikePackageDocument(bytes: ByteArray): Boolean {
    val text = bytes.decodeToString(0, minOf(bytes.size, 64 * 1024)).trimStart('﻿', ' ', '\t', '\r', '\n')
    if (!text.startsWith("<")) return false
    var isPackage = false
    var seenRoot = false
    scanMarkup(text) { token ->
        if (!seenRoot && token is MarkupToken.StartTag) {
            seenRoot = true
            isPackage = token.name == "package"
        }
    }
    return isPackage
}

/** True when encryption.xml encrypts anything with a method other than font obfuscation. */
private fun encryptsContent(encryptionXml: String): Boolean {
    var inEncryptedData = 0
    var algorithmSeen = false
    var encrypted = false
    scanMarkup(encryptionXml) { token ->
        when (token) {
            is MarkupToken.StartTag -> when (token.name) {
                "encrypteddata" -> {
                    if (!token.selfClosing) inEncryptedData++
                    algorithmSeen = false
                }
                // Only the method directly describing the data; a KeyInfo method wraps a key.
                "encryptionmethod" -> if (inEncryptedData > 0 && !algorithmSeen) {
                    algorithmSeen = true
                    val algorithm = token.attributes.entries
                        .firstOrNull { attribute -> attribute.key.equals("algorithm", ignoreCase = true) }
                        ?.value?.trim()?.lowercase()
                    if (algorithm !in FONT_OBFUSCATION_ALGORITHMS) encrypted = true
                }
            }
            is MarkupToken.EndTag -> if (token.name == "encrypteddata") {
                // Data with no declared method is encrypted with something we do not know.
                if (!algorithmSeen) encrypted = true
                if (inEncryptedData > 0) inEncryptedData--
            }
            is MarkupToken.Text -> Unit
        }
    }
    return encrypted
}

private fun ByteArray.littleEndianInt(): Int =
    (this[0].toInt() and 0xFF) or ((this[1].toInt() and 0xFF) shl 8) or
        ((this[2].toInt() and 0xFF) shl 16) or ((this[3].toInt() and 0xFF) shl 24)
