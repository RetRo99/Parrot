package com.retro99.reader.ui.tts

import java.io.File
import java.io.IOException

/**
 * Resolves [relativePath] under this directory and fails if the canonical result
 * escapes it, so no manifest value can redirect a write, link or delete.
 */
internal fun File.resolveInside(relativePath: String): File {
    val base = canonicalFile
    val resolved = File(base, relativePath).canonicalFile
    if (!resolved.path.startsWith(base.path + File.separator)) {
        throw IOException("Path escapes ${base.path}: $relativePath")
    }
    return resolved
}
