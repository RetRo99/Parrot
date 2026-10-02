package com.retro99.reader.data.recap

import com.retro99.reader.domain.recap.RecapEngine
import com.retro99.reader.domain.recap.RecapEngineSelector
import com.retro99.reader.domain.recap.RecapSettings
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged

/**
 * Cloud only, and only with consent and a signed-in user. An offline
 * engine would be chosen here when it exists and cloud isn't usable.
 */
class DefaultRecapEngineSelector(
    private val settings: RecapSettings,
    private val auth: RecapAuthTokens,
    private val cloudEngine: RecapEngine,
) : RecapEngineSelector {

    override suspend fun select(): RecapEngine? =
        cloudEngine.takeIf { settings.isCloudRecapsEnabled() && auth.isSignedIn() }

    override fun observeAvailable(): Flow<Boolean> =
        combine(settings.observeCloudRecapsEnabled(), auth.observeSignedIn()) { enabled, signedIn ->
            enabled && signedIn
        }.distinctUntilChanged()
}
