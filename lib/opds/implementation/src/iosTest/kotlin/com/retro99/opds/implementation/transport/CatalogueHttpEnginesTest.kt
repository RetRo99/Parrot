package com.retro99.opds.implementation.transport

import io.ktor.client.engine.darwin.Darwin
import io.ktor.client.engine.darwin.DarwinClientEngineConfig
import platform.Foundation.NSURLRequestReloadIgnoringLocalCacheData
import platform.Foundation.NSURLSessionConfiguration
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * The first iPhone run found feed pages, search addresses, covers and whole downloaded books in
 * `Library/Caches`, written by `NSURLSession`'s shared cache. See
 * docs/opds-ios-check-report.md.
 */
class CatalogueHttpEnginesTest {

    @Test
    fun catalogueSessionKeepsNothingInTheSystemHttpCache() {
        val engine = catalogueHttpEngines(Darwin).create()
        try {
            val config = engine.config as DarwinClientEngineConfig
            // Exactly what the Darwin engine does with the block: applies it to the default
            // session configuration, which is the one that caches.
            val session = NSURLSessionConfiguration.defaultSessionConfiguration()
            assertNotNull(session.URLCache, "the default session caches; without that this test proves nothing")
            assertNotEquals(NSURLRequestReloadIgnoringLocalCacheData, session.requestCachePolicy)

            config.sessionConfig(session)

            assertNull(session.URLCache, "catalogue responses would be written to Library/Caches")
            assertEquals(NSURLRequestReloadIgnoringLocalCacheData, session.requestCachePolicy)
        } finally {
            engine.close()
        }
    }

    @Test
    fun anythingThatIsNotTheDarwinEngineIsHandedBackUntouched() {
        val other = object : io.ktor.client.engine.HttpClientEngineFactory<io.ktor.client.engine.HttpClientEngineConfig> {
            override fun create(block: io.ktor.client.engine.HttpClientEngineConfig.() -> Unit) =
                throw AssertionError("not created in this test")
        }
        assertEquals(other, catalogueHttpEngines(other))
    }
}
