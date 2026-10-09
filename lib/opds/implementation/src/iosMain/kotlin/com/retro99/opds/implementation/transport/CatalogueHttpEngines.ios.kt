package com.retro99.opds.implementation.transport

import io.ktor.client.engine.HttpClientEngineFactory

actual fun catalogueHttpEngines(platform: HttpClientEngineFactory<*>): HttpClientEngineFactory<*> = platform
