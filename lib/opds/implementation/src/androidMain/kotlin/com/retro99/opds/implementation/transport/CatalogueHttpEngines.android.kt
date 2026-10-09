package com.retro99.opds.implementation.transport

import io.ktor.client.engine.HttpClientEngineFactory

/**
 * Nothing to do. OkHttp keeps no response cache unless one is installed on the client, and
 * neither the app's shared engine nor Ktor's `HttpCache` plugin is in play here, so the HTTP
 * layer writes no catalogue page, cover or book file to disk on Android.
 */
actual fun catalogueHttpEngines(platform: HttpClientEngineFactory<*>): HttpClientEngineFactory<*> = platform
