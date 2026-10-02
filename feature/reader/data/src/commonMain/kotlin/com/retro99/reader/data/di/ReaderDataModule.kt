package com.retro99.reader.data.di

import com.retro99.analytics.api.Analytics
import com.retro99.base.AppInitializer
import com.retro99.cloud.implementation.CloudConfiguration
import com.retro99.cloud.implementation.SupabaseClientProvider
import com.retro99.database.api.recap.SessionRecapDatabase
import com.retro99.reader.data.recap.CloudRecapEngine
import com.retro99.reader.data.recap.DefaultRecapEngineSelector
import com.retro99.reader.data.recap.RecapAuthTokens
import com.retro99.reader.data.recap.RecapDiagnostics
import com.retro99.reader.data.recap.RecapEndpoint
import com.retro99.reader.data.recap.RecapJobRunner
import com.retro99.reader.data.recap.RecapSessionRecorderImpl
import com.retro99.reader.data.recap.RecapStartupInitializer
import com.retro99.reader.data.recap.RecapTrigger
import com.retro99.reader.data.recap.SessionRecapDataRepository
import com.retro99.reader.data.recap.SupabaseRecapAuthTokens
import com.retro99.reader.domain.recap.RecapEngineSelector
import com.retro99.reader.domain.recap.RecapRepository
import com.retro99.reader.domain.recap.RecapSessionRecorder
import com.retro99.reader.domain.recap.RecapSettings
import com.retro99.user.api.UserRegistry
import io.ktor.client.HttpClient
import io.ktor.client.engine.HttpClientEngineFactory
import io.ktor.client.plugins.HttpTimeout
import org.koin.core.annotation.ComponentScan
import org.koin.core.annotation.Configuration
import org.koin.core.annotation.Module
import org.koin.core.annotation.Single

@Module
@Configuration
@ComponentScan("com.retro99.reader.data")
class ReaderDataModule {

    // Session recaps. Plain classes wired here so tests can pass fakes.

    @Single
    internal fun provideRecapAuthTokens(clientProvider: SupabaseClientProvider): RecapAuthTokens =
        SupabaseRecapAuthTokens(clientProvider)

    @Single
    internal fun provideCloudRecapEngine(
        configuration: CloudConfiguration,
        auth: RecapAuthTokens,
        engineFactory: HttpClientEngineFactory<*>,
    ): CloudRecapEngine = CloudRecapEngine(
        endpoint = RecapEndpoint.from(configuration),
        auth = auth,
        // Own client: this call alone needs a ~90 s timeout.
        httpClient = HttpClient(engineFactory) {
            install(HttpTimeout) {
                connectTimeoutMillis = RECAP_CONNECT_TIMEOUT_MS
                requestTimeoutMillis = CloudRecapEngine.REQUEST_TIMEOUT_MS
                socketTimeoutMillis = CloudRecapEngine.REQUEST_TIMEOUT_MS
            }
        },
    )

    @Single
    internal fun provideRecapEngineSelector(
        settings: RecapSettings,
        auth: RecapAuthTokens,
        cloudEngine: CloudRecapEngine,
    ): RecapEngineSelector = DefaultRecapEngineSelector(settings, auth, cloudEngine)

    @Single
    internal fun provideRecapJobRunner(
        database: SessionRecapDatabase,
        selector: RecapEngineSelector,
        analytics: Analytics,
        userRegistry: UserRegistry,
    ): RecapJobRunner = RecapJobRunner(
        database = database,
        selector = selector,
        diagnostics = RecapDiagnostics(analytics),
        activeProfileId = userRegistry::getActiveProfileIdOrDefault,
    )

    @Single
    internal fun provideRecapSessionRecorderImpl(
        database: SessionRecapDatabase,
        settings: RecapSettings,
        analytics: Analytics,
        runner: RecapJobRunner,
    ): RecapSessionRecorderImpl = RecapSessionRecorderImpl(
        database = database,
        settings = settings,
        diagnostics = RecapDiagnostics(analytics),
        onSessionReady = { runner.trigger(RecapTrigger.SESSION_ENDED) },
    )

    @Single
    internal fun provideRecapSessionRecorder(
        impl: RecapSessionRecorderImpl,
    ): RecapSessionRecorder = impl

    @Single
    internal fun provideRecapRepository(
        database: SessionRecapDatabase,
        runner: RecapJobRunner,
    ): RecapRepository = SessionRecapDataRepository(database, runner)

    @Single(binds = [AppInitializer::class])
    internal fun provideRecapStartupInitializer(
        recorder: RecapSessionRecorderImpl,
        runner: RecapJobRunner,
    ): RecapStartupInitializer = RecapStartupInitializer(recorder, runner)

    private companion object {
        const val RECAP_CONNECT_TIMEOUT_MS = 15_000L
    }
}
