package com.retro99.catalogue.data.di

import com.retro99.catalogue.data.CatalogueAcquisitionQueue
import com.retro99.catalogue.data.CatalogueStagingFiles
import com.retro99.catalogue.domain.CatalogueAcquisitionManager
import com.retro99.database.api.ProfileDatabaseSession
import com.retro99.database.api.catalogue.CatalogueAcquisitionsDatabase
import com.retro99.epub.api.EpubFileChecker
import com.retro99.server.api.CatalogueRepositoryProvider
import com.retro99.server.api.CatalogueWorkController
import com.retro99.user.api.ProfileWorkRegistry
import com.retro99.user.api.UserRegistry
import org.koin.core.Koin
import org.koin.core.module.Module
import org.koin.dsl.koinApplication
import org.koin.dsl.module
import java.lang.reflect.Proxy
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertSame

/** Resolve the compiler-generated production module, not a hand-written test factory. */
class CatalogueDataModuleRegressionTest {
    private var repositoryProvidersCreated = 0

    @Test
    fun `the queue is one instance behind both of its contracts`() = withProductionModule { koin ->
        val manager = koin.get<CatalogueAcquisitionManager>()

        assertIs<CatalogueAcquisitionQueue>(manager)
        assertSame<Any>(manager, koin.getAll<CatalogueWorkController>().single())
    }

    @Test
    fun `building the queue does not build the catalogue repository provider`() = withProductionModule { koin ->
        // The provider depends on the server registry, which is handed every work controller.
        koin.get<CatalogueAcquisitionManager>()
        koin.getAll<CatalogueWorkController>()

        assertEquals(0, repositoryProvidersCreated)
    }

    private fun withProductionModule(check: (Koin) -> Unit) {
        // The generated extension is emitted after source analysis, so load its JVM entry point.
        val production = Class.forName("com.retro99.catalogue.data.di.ComRetro99CatalogueDataDiCatalogueDataModuleModuleKt")
            .getMethod("module", CatalogueDataModule::class.java).invoke(null, CatalogueDataModule()) as Module
        val app = koinApplication {
            // Later definitions win: the staging store here replaces the one that needs a Context.
            allowOverride(true)
            modules(production, module {
                single<ProfileDatabaseSession> { stub() }
                single<CatalogueAcquisitionsDatabase> { stub() }
                single<UserRegistry> { stub() }
                single<ProfileWorkRegistry> { stub() }
                single<EpubFileChecker> { stub() }
                single<CatalogueStagingFiles> { stub() }
                single<CatalogueRepositoryProvider> {
                    repositoryProvidersCreated++
                    stub()
                }
            })
        }
        try {
            check(app.koin)
        } finally {
            app.close()
        }
    }

    private inline fun <reified T> stub(): T =
        Proxy.newProxyInstance(T::class.java.classLoader, arrayOf(T::class.java)) { proxy, method, args ->
            when (method.name) {
                "hashCode" -> System.identityHashCode(proxy)
                "equals" -> proxy === args?.firstOrNull()
                "toString" -> T::class.java.simpleName
                else -> error("Unexpected call: ${method.name}")
            }
        } as T
}
