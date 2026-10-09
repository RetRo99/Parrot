package com.retro99.opds.implementation.transport

import io.ktor.client.engine.HttpClientEngine
import io.ktor.client.engine.HttpClientEngineFactory
import io.ktor.client.engine.darwin.Darwin
import io.ktor.client.engine.darwin.DarwinClientEngineConfig
import platform.Foundation.NSURLRequestReloadIgnoringLocalCacheData

/**
 * The Darwin engine's default session uses `NSURLSession`'s shared `NSURLCache`, which writes
 * response bodies into `Library/Caches`: feed pages, search pages with the typed words in the
 * address, covers, and whole downloaded books. None of that obeys Parrot's saved-page rules — it
 * is not per profile, it is not cleared when a catalogue is turned off or removed, and it
 * survives a profile switch — so the catalogue's session keeps no cache and reads none.
 *
 * Parrot's own conditional requests are untouched: `If-None-Match` and `If-Modified-Since` are
 * headers this client sends from the validators of Parrot's own saved pages, and a 304 still
 * serves the saved copy. Only the system's cache is gone.
 *
 * Anything that is not the Darwin engine — a mock engine in a test — is handed back untouched.
 */
actual fun catalogueHttpEngines(platform: HttpClientEngineFactory<*>): HttpClientEngineFactory<*> =
    if (platform === Darwin) CatalogueDarwinEngines else platform

private object CatalogueDarwinEngines : HttpClientEngineFactory<DarwinClientEngineConfig> {
    override fun create(block: DarwinClientEngineConfig.() -> Unit): HttpClientEngine =
        Darwin.create {
            block()
            // Appended last, so nothing a caller configured can put the cache back.
            configureSession {
                URLCache = null
                requestCachePolicy = NSURLRequestReloadIgnoringLocalCacheData
            }
        }
}
