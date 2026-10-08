package com.retro99.parrot.di

import com.retro99.catalogue.domain.CatalogueAcquisitionManager
import com.retro99.server.api.CatalogueAccessStore
import com.retro99.server.api.CatalogueWorkController
import com.retro99.server.api.OpdsCredentialStore
import com.retro99.server.api.ServerCatalogueRepositoryFactory
import com.retro99.server.api.ServerRegistry
import com.retro99.server.opds.OpdsCatalogueRepositoryFactory
import com.retro99.user.api.ProfileWorkRegistry
import com.retro99.user.api.UserRegistry
import kotlin.test.Test
import kotlin.test.assertIs
import kotlin.test.assertSame
import kotlin.test.assertTrue

/** What the running app is handed, resolved from the generated graph and not from test objects. */
class CatalogueWiringTest {

    @Test
    fun `the registry clears the same account store the catalogue requests read`() = RealAppGraph().use { graph ->
        val registry = graph.koin.get<ServerRegistry>()
        val factory = graph.koin.getAll<ServerCatalogueRepositoryFactory>().filterIsInstance<OpdsCatalogueRepositoryFactory>().single()

        assertSame(graph.koin.get<OpdsCredentialStore>(), registry.privateField("opdsCredentials"))
        assertSame(factory.privateField("credentials"), registry.privateField("opdsCredentials"))
    }

    @Test
    fun `the registry clears the same access store the catalogue requests write`() = RealAppGraph().use { graph ->
        val registry = graph.koin.get<ServerRegistry>()
        val factory = graph.koin.getAll<ServerCatalogueRepositoryFactory>().filterIsInstance<OpdsCatalogueRepositoryFactory>().single()

        assertSame(graph.koin.get<CatalogueAccessStore>(), registry.privateField("catalogueAccess"))
        assertSame(factory.privateField("access"), registry.privateField("catalogueAccess"))
    }

    @Test
    fun `the registry is handed the download queue and the catalogue sessions to cancel`() = RealAppGraph().use { graph ->
        val registry = graph.koin.get<ServerRegistry>()
        val queue = graph.koin.get<CatalogueAcquisitionManager>()

        val controllers = assertIs<List<*>>(registry.privateField("catalogueWork"))

        assertTrue(controllers.any { it === queue }, "the download queue is not among ${controllers.size} controllers")
        assertTrue(controllers.any { it is OpdsCatalogueRepositoryFactory }, "the catalogue sessions are not among the controllers")
        assertTrue(controllers.all { it is CatalogueWorkController })
    }

    @Test
    fun `a profile switch stops the work the queue and the catalogue sessions registered`() = RealAppGraph().use { graph ->
        val users = graph.koin.get<UserRegistry>()

        assertSame(graph.koin.get<ProfileWorkRegistry>(), users.privateField("profileWork"))
    }
}
