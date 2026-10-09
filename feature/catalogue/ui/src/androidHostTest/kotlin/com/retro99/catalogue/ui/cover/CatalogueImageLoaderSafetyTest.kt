package com.retro99.catalogue.ui.cover

import java.io.File
import kotlin.test.Test
import kotlin.test.assertFalse

class CatalogueImageLoaderSafetyTest {
    @Test
    fun image_loaders_never_receive_a_raw_catalogue_address() {
        val sourceRoot = File("src")
        val files = sourceRoot.walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .toList()
        val unsafeCalls = files.flatMap { file ->
            val text = file.readText()
            val patterns = listOf(
                Regex("""(?:AsyncImage|rememberAsyncImagePainter)\s*\([^)]*\bmodel\s*=\s*(?:address|url|imageUrl)\b""", RegexOption.DOT_MATCHES_ALL),
                Regex("""(?:AsyncImage|rememberAsyncImagePainter)\s*\(\s*(?:"https?://|address\b|url\b|imageUrl\b)""", RegexOption.DOT_MATCHES_ALL),
                Regex("""\bloadImage\s*\(\s*(?:"https?://|address\b|url\b|imageUrl\b)""", RegexOption.DOT_MATCHES_ALL),
            )
            patterns.flatMap { pattern -> pattern.findAll(text).map { "${file.path}: ${it.value}" }.toList() }
        }
        assertFalse(unsafeCalls.isNotEmpty(), "Raw catalogue addresses passed to image loaders:\n${unsafeCalls.joinToString("\n")}")
    }
}
