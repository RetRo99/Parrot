package com.retro99.reader.ui.tts

import java.io.File
import java.io.IOException
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.attribute.BasicFileAttributes

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

/**
 * Returns [anchor]/[segments] when no component below [anchor] is a symlink,
 * else null, so a planted link cannot become the trusted base directory.
 */
internal fun trustedDirectory(anchor: File, vararg segments: String): File? {
    val expected = segments.fold(anchor.canonicalFile) { parent, name -> File(parent, name) }
    return expected.takeIf { directory -> directory.canonicalFile.path == directory.path }
}

/** Like [File.deleteRecursively] but removes symlinks instead of following them. */
internal fun File.deleteRecursivelyNoFollow(): Boolean {
    val root = toPath()
    if (!Files.exists(root, LinkOption.NOFOLLOW_LINKS)) return true
    return try {
        Files.walkFileTree(
            root,
            object : SimpleFileVisitor<Path>() {
                override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
                    Files.delete(file)
                    return FileVisitResult.CONTINUE
                }

                override fun postVisitDirectory(dir: Path, exc: IOException?): FileVisitResult {
                    if (exc != null) throw exc
                    Files.delete(dir)
                    return FileVisitResult.CONTINUE
                }
            },
        )
        true
    } catch (error: IOException) {
        false
    }
}
