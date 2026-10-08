package com.retro99.catalogue.data.di

import com.retro99.catalogue.data.AcquisitionWorker
import com.retro99.catalogue.data.CatalogueAcquisitionQueue
import com.retro99.catalogue.data.CatalogueStagingFiles
import com.retro99.catalogue.data.DeferredCatalogueBookAdder
import com.retro99.catalogue.data.RegistryAcquisitionFileSource
import com.retro99.catalogue.domain.CatalogueAcquisitionManager
import com.retro99.database.api.ProfileDatabaseSession
import com.retro99.database.api.catalogue.CatalogueAcquisitionsDatabase
import com.retro99.epub.api.EpubFileChecker
import com.retro99.server.api.CatalogueRepositoryProvider
import com.retro99.server.api.CatalogueWorkController
import com.retro99.user.api.ProfileWorkRegistry
import com.retro99.user.api.UserRegistry
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import org.koin.core.annotation.ComponentScan
import org.koin.core.annotation.Configuration
import org.koin.core.annotation.Module
import org.koin.core.annotation.Provided
import org.koin.core.annotation.Single
import kotlin.time.Clock
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

@Module
@Configuration
@ComponentScan("com.retro99.catalogue.data")
class CatalogueDataModule {

    @OptIn(ExperimentalUuidApi::class)
    @Single(binds = [CatalogueAcquisitionManager::class, CatalogueWorkController::class])
    fun catalogueAcquisitionQueue(
        @Provided session: ProfileDatabaseSession,
        @Provided database: CatalogueAcquisitionsDatabase,
        @Provided users: UserRegistry,
        @Provided profileWork: ProfileWorkRegistry,
        // Lazy: the provider needs the server registry, and the registry is meant to be handed
        // every CatalogueWorkController, this queue included.
        @Provided repositories: Lazy<CatalogueRepositoryProvider>,
        @Provided checker: EpubFileChecker,
        files: CatalogueStagingFiles,
    ): CatalogueAcquisitionQueue {
        val activeProfileId = { users.getActiveProfileId() }
        return CatalogueAcquisitionQueue(
            session = session,
            database = database,
            activeProfileId = activeProfileId,
            profileWork = profileWork,
            worker = AcquisitionWorker(RegistryAcquisitionFileSource(repositories, activeProfileId), files, checker),
            files = files,
            // Adding to the library is the next step of Phase 3; until then a download stops
            // checked and staged.
            adder = DeferredCatalogueBookAdder(),
            scope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
            now = { Clock.System.now().toEpochMilliseconds() },
            newRequestId = { Uuid.random().toString() },
        )
    }
}
