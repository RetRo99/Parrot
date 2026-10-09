package com.retro99.opds.implementation.transport

import io.ktor.client.engine.HttpClientEngineFactory

/**
 * The engine factory every catalogue client must be built from: feed pages, pictures, downloaded
 * files, and the check made when a catalogue is added. [platform] is the app's shared engine
 * factory; what comes back is the catalogue's own adaptation of it, so no other feature's client
 * changes.
 *
 * It exists because a platform's default HTTP stack may keep responses of its own accord, in a
 * place none of Parrot's saved-page rules reach. Whatever a platform needs to stop that belongs
 * here, next to where the catalogue's transport is built.
 */
expect fun catalogueHttpEngines(platform: HttpClientEngineFactory<*>): HttpClientEngineFactory<*>
