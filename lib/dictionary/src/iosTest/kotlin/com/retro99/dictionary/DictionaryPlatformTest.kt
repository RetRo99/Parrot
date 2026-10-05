package com.retro99.dictionary

import okio.FileSystem
import okio.Path.Companion.toPath
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlin.test.*

class DictionaryPlatformTest {
    @Test fun realBundledResourceWorksWithoutDownloading() = runTest {
        val platform = PlatformDictionaryModule().platform()
        val root = platform.packRoot.toPath() / "bundled-resource-test"
        try {
            val path = BundledDictionaryPack(root, platform::availableBytes).file()
            assertEquals(ENGLISH_PACK.files.single().size, FileSystem.SYSTEM.metadata(path).size)
            platform.open(path.toString()).let { driver ->
                try {
                    val queries = DictionaryDatabase(driver).dictionaryQueries
                    assertEquals(ENGLISH_PACK.version, queries.version().executeAsOne())
                    val json = Json { ignoreUnknownKeys = true }
                    val entry = lookupDictionary("mice",
                        exact = { key -> queries.entry(key).executeAsOneOrNull()?.let { json.decodeFromString<DictionaryEntry>(it) } },
                        forms = { key -> queries.forms(key).executeAsList() })
                    assertEquals("mouse", entry?.headword)
                    assertTrue(entry!!.firstSense.gloss.isNotBlank())
                } finally { driver.close() }
            }
        } finally { FileSystem.SYSTEM.deleteRecursively(root) }
    }

    @Test fun packOpensAtItsOwnPathAndReportsFreeSpace() {
        val platform = PlatformDictionaryModule().platform()
        assertTrue(platform.availableBytes() > 0)
        val root = platform.packRoot.toPath() / "platform-test"
        val path = root / "dictionary.sqlite"
        FileSystem.SYSTEM.createDirectories(root)
        try {
            platform.open(path.toString()).let { driver ->
                try {
                    driver.execute(null, "INSERT INTO metadata VALUES ('version','test')", 0)
                    assertEquals("test", DictionaryDatabase(driver).dictionaryQueries.version().executeAsOne())
                } finally { driver.close() }
            }
            assertTrue(FileSystem.SYSTEM.exists(path))
            platform.open(path.toString()).let { driver ->
                try { assertEquals("test", DictionaryDatabase(driver).dictionaryQueries.version().executeAsOne()) }
                finally { driver.close() }
            }
        } finally { FileSystem.SYSTEM.deleteRecursively(root) }
    }
}
