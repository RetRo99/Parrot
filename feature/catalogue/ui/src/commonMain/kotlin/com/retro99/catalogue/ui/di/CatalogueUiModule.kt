package com.retro99.catalogue.ui.di

import com.retro99.catalogue.domain.CatalogueAcquisitionManager
import com.retro99.catalogue.ui.downloads.CatalogueDownloadAnnouncer
import com.retro99.user.api.UserRegistry
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import org.koin.core.annotation.ComponentScan
import org.koin.core.annotation.Configuration
import org.koin.core.annotation.Module
import org.koin.core.annotation.Provided
import org.koin.core.annotation.Single

@Module
@Configuration
@ComponentScan("com.retro99.catalogue.ui")
class CatalogueUiModule {
    @Single
    fun catalogueDownloadAnnouncer(
        @Provided queue: CatalogueAcquisitionManager,
        @Provided users: UserRegistry,
    ): CatalogueDownloadAnnouncer = CatalogueDownloadAnnouncer(queue, users) { CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate) }
}
