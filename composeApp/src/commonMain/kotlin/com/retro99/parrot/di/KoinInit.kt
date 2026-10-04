package com.retro99.parrot.di

import com.retro99.base.AppInitializer
import io.kotzilla.sdk.analytics.koin.analytics
import org.koin.core.KoinApplication
import org.koin.core.module.Module
import org.koin.plugin.module.dsl.startKoin

/**
 * Initialize Koin with all application modules.
 *
 * Uses KSP-generated startKoin extension from @KoinApplication annotation
 * which automatically includes all @Configuration annotated modules.
 *
 * @param additionalModules Additional modules to include
 * @param platformConfiguration Lambda for platform-specific configuration (e.g., androidContext, androidLogger)
 */
fun initKoin(
    additionalModules: List<Module> = emptyList(),
    platformConfiguration: KoinApplication.() -> Unit = {},
): KoinApplication {
    return startKoin<ParrotKoinApp> {
        platformConfiguration()
        modules(additionalModules)
        analytics()
    }.also { koinApp ->
        koinApp.koin.getAll<AppInitializer>().forEach {
            try {
                it.initialize()
            } catch (e: Exception) {
                // One broken initializer must not silently disable the rest.
                println("RECAPDBG initializer_failed ${it::class.simpleName}: ${e::class.simpleName}")
            }
        }
    }
}

