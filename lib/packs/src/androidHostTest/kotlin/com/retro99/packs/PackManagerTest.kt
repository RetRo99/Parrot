package com.retro99.packs

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import okio.ByteString.Companion.encodeUtf8
import okio.FileSystem
import okio.Path.Companion.toPath
import java.nio.file.Files
import kotlin.test.*

class PackManagerTest {
    @Test fun ignoredRangeRestartsInsteadOfAppending() = runTest {
        val root = Files.createTempDirectory("parrot-packs").toString().toPath()
        val fs = FileSystem.SYSTEM
        val body = "dictionary data"
        val file = PackFile("english.sqlite", "https://github.com/RetRo99/tts-models/releases/download/v1/english.sqlite", body.length.toLong(), body.encodeUtf8().sha256().hex())
        val partial = root / "english.sqlite.part"
        fs.write(partial) { writeUtf8(body.take(5)) }
        val client = HttpClient(MockEngine { request ->
            assertEquals("bytes=5-", request.headers["Range"])
            respond(body, HttpStatusCode.OK)
        })
        try {
            PackDownloader(client).download(file, partial)
            assertEquals(body, fs.read(partial) { readUtf8() })
        } finally { client.close(); fs.deleteRecursively(root) }
    }

    @Test fun diskSpaceFailureDoesNotStartNetworkTraffic() = runTest {
        val root = Files.createTempDirectory("parrot-packs").toString().toPath()
        val client = HttpClient(MockEngine { error("Download must not start without enough space") })
        try {
            val file = PackFile("english.sqlite", "https://github.com/RetRo99/tts-models/releases/download/v1/english.sqlite", 10, "a".repeat(64))
            val manager = PackManager(root, PackDownloader(client), { 0 })
            val error = assertFails { manager.install(PackManifest("dictionary-en", "v1", listOf(file))) }
            assertContains(error.message.orEmpty(), "Not enough storage")
            assertNull(manager.activeVersion("dictionary-en"))
        } finally { client.close(); FileSystem.SYSTEM.deleteRecursively(root) }
    }

    @Test fun databaseValidationFailureDoesNotActivatePack() = runTest {
        val root = Files.createTempDirectory("parrot-packs").toString().toPath()
        val body = "valid hash but invalid schema"
        val client = HttpClient(MockEngine { respond(body, HttpStatusCode.OK) })
        try {
            val file = PackFile("english.sqlite", "https://github.com/RetRo99/tts-models/releases/download/v1/english.sqlite", body.length.toLong(), body.encodeUtf8().sha256().hex())
            val manager = PackManager(root, PackDownloader(client), { 100_000_000 })
            assertFails { manager.install(PackManifest("dictionary-en", "v1", listOf(file)), validateInstalled = { error("Invalid schema") }) }
            assertNull(manager.activeVersion("dictionary-en"))
        } finally { client.close(); FileSystem.SYSTEM.deleteRecursively(root) }
    }

    @Test fun downloadResumesAndOnlyVerifiedInstallBecomesActive() = runTest {
        val root = Files.createTempDirectory("parrot-packs").toString().toPath()
        val fs = FileSystem.SYSTEM
        val body = "dictionary data"
        val file = PackFile("english.sqlite", "https://github.com/RetRo99/tts-models/releases/download/v1/english.sqlite", body.length.toLong(), body.encodeUtf8().sha256().hex())
        val partial = root / "dictionary-en" / "v1" / "english.sqlite.part"
        fs.createDirectories(partial.parent!!)
        fs.write(partial) { writeUtf8(body.take(5)) }
        val client = HttpClient(MockEngine { request ->
            assertEquals("bytes=5-", request.headers["Range"])
            respond(body.drop(5), HttpStatusCode.PartialContent, headersOf("Content-Range", "bytes 5-${body.lastIndex}/${body.length}"))
        })
        try {
            val manager = PackManager(root, PackDownloader(client), { 100_000_000 })
            assertNull(manager.activeFile("dictionary-en", file.path))
            manager.install(PackManifest("dictionary-en", "v1", listOf(file)))
            assertEquals("v1", manager.activeVersion("dictionary-en"))
            assertEquals(body, fs.read(manager.activeFile("dictionary-en", file.path)!!) { readUtf8() })
        } finally { client.close(); fs.deleteRecursively(root) }
    }

    @Test fun failedUpdatePreservesPreviousActivePack() = runTest {
        val root = Files.createTempDirectory("parrot-packs").toString().toPath()
        val fs = FileSystem.SYSTEM
        fs.createDirectories(root / "dictionary-en" / "v1")
        fs.write(root / "dictionary-en" / ".active") { writeUtf8("v1") }
        fs.write(root / "dictionary-en" / "v1" / "english.sqlite") { writeUtf8("working") }
        val client = HttpClient(MockEngine { respond("corrupt", HttpStatusCode.OK) })
        try {
            val manager = PackManager(root, PackDownloader(client), { 100_000_000 })
            val file = PackFile("english.sqlite", "https://github.com/RetRo99/tts-models/releases/download/v2/english.sqlite", 7, "correct".encodeUtf8().sha256().hex())
            assertFails { manager.install(PackManifest("dictionary-en", "v2", listOf(file))) }
            assertEquals("v1", manager.activeVersion("dictionary-en"))
            assertEquals("working", fs.read(manager.activeFile("dictionary-en", file.path)!!) { readUtf8() })
        } finally { client.close(); fs.deleteRecursively(root) }
    }
}
