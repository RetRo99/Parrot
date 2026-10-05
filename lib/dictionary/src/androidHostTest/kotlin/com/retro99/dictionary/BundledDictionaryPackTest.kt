package com.retro99.dictionary

import com.retro99.packs.PackFile
import kotlinx.coroutines.test.runTest
import okio.ByteString.Companion.encodeUtf8
import okio.FileSystem
import okio.Path.Companion.toPath
import java.nio.file.Files
import kotlin.test.*

class BundledDictionaryPackTest {
    private val body = "bundled dictionary"
    private val file = PackFile("english.sqlite", ENGLISH_PACK.files.single().url,
        body.length.toLong(), body.encodeUtf8().sha256().hex())

    @Test fun bundleIsCopiedOnceWithoutADownloadAndSurvivesUpdateRemoval() = runTest {
        val root = Files.createTempDirectory("bundled-dictionary").toString().toPath()
        val fs = FileSystem.SYSTEM
        var reads = 0
        try {
            val pack = BundledDictionaryPack(root, { 100_000_000L }, file, { reads++; body.encodeToByteArray() })
            val installed = pack.file()
            assertEquals(body, fs.read(installed) { readUtf8() })
            assertEquals(installed, pack.file())
            assertEquals(1, reads)
            // Downloaded updates occupy a different directory.
            fs.createDirectories(root / ENGLISH_PACK.id)
            fs.write(root / ENGLISH_PACK.id / ".active") { writeUtf8("update") }
            fs.deleteRecursively(root / ENGLISH_PACK.id)
            assertEquals(installed, pack.file())
            assertEquals(1, reads)
        } finally { fs.deleteRecursively(root) }
    }

    @Test fun corruptResourceNeverBecomesAnInstalledFile() = runTest {
        val root = Files.createTempDirectory("bundled-dictionary").toString().toPath()
        try {
            val pack = BundledDictionaryPack(root, { 100_000_000L }, file, { body.reversed().encodeToByteArray() })
            assertFails { pack.file() }
            assertFalse(FileSystem.SYSTEM.exists(root / "bundled-dictionary" / ENGLISH_PACK.version / file.path))
        } finally { FileSystem.SYSTEM.deleteRecursively(root) }
    }

    @Test fun insufficientSpaceDoesNotReadTheResource() = runTest {
        val root = Files.createTempDirectory("bundled-dictionary").toString().toPath()
        try {
            val pack = BundledDictionaryPack(root, { 0L }, file, { error("Resource must not be read") })
            assertContains(assertFails { pack.file() }.message.orEmpty(), "Not enough storage")
        } finally { FileSystem.SYSTEM.deleteRecursively(root) }
    }
}
