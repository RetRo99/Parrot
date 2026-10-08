package com.retro99.catalogue.data.di

import com.retro99.catalogue.data.AcquisitionWorker
import com.retro99.catalogue.data.CatalogueAcquisitionQueue
import com.retro99.catalogue.data.CatalogueStagingFiles
import com.retro99.catalogue.data.DatabaseCatalogueLibraryLookup
import com.retro99.catalogue.data.LibraryCatalogueBookAdder
import com.retro99.catalogue.data.RegistryAcquisitionFileSource
import com.retro99.books.domain.StagedBookImportManager
import com.retro99.catalogue.domain.CatalogueAcquisitionManager
import com.retro99.catalogue.domain.CatalogueLibraryLookup
import com.retro99.database.api.catalogue.CatalogueBookSourcesDatabase
import com.retro99.preferences.api.Preferences
import com.retro99.preferences.api.PreferencesKey
import com.retro99.preferences.api.getObject
import com.retro99.server.api.ServerConfig
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
        @Provided sources: CatalogueBookSourcesDatabase,
        @Provided users: UserRegistry,
        @Provided profileWork: ProfileWorkRegistry,
        // Lazy: the provider needs the server registry, and the registry is meant to be handed
        // every CatalogueWorkController, this queue included.
        @Provided repositories: Lazy<CatalogueRepositoryProvider>,
        // Lazy for the same reason: the library import reaches the registry too.
        @Provided importer: Lazy<StagedBookImportManager>,
        @Provided checker: EpubFileChecker,
        @Provided preferences: Preferences,
        files: CatalogueStagingFiles,
    ): CatalogueAcquisitionQueue {
        val activeProfileId = { users.getActiveProfileId() }
        return CatalogueAcquisitionQueue(
            session = session,
            database = database,
            sources = sources,
            activeProfileId = activeProfileId,
            profileWork = profileWork,
            worker = AcquisitionWorker(RegistryAcquisitionFileSource(repositories, activeProfileId), files, checker),
            files = files,
            adder = LibraryCatalogueBookAdder(importer, activeProfileId),
            // Read where the registry stores it, without the registry: the registry calls
            // into this queue while it holds its own lock.
            sourceAddress = { profileId, sourceId ->
                preferences
                    .getObject<List<ServerConfig>>(PreferencesKey.UserScoped(profileId, PreferencesKey.CatalogueSources.name))
                    ?.firstOrNull { source -> source.id == sourceId }
                    ?.baseUrl
            },
            scope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
            now = { Clock.System.now().toEpochMilliseconds() },
            newRequestId = { Uuid.random().toString() },
        )
    }

    @Single(binds = [CatalogueLibraryLookup::class])
    fun catalogueLibraryLookup(
        @Provided session: ProfileDatabaseSession,
        @Provided sources: CatalogueBookSourcesDatabase,
        @Provided users: UserRegistry,
    ): CatalogueLibraryLookup = DatabaseCatalogueLibraryLookup(session, sources) { users.getActiveProfileId() }
}
