package com.retro99.catalogue.ui.publication

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.retro99.catalogue.domain.CatalogueAcquisitionManager
import com.retro99.catalogue.domain.CatalogueLibraryLookup
import com.retro99.catalogue.ui.browse.CatalogueBrowseGateway
import com.retro99.catalogue.ui.navigation.CatalogueRouteReferences
import org.koin.core.annotation.*

@KoinViewModel
class CatalogueBookViewModel(
    gateway: CatalogueBrowseGateway,
    @Provided library: CatalogueLibraryLookup,
    @Provided queue: CatalogueAcquisitionManager,
    references: CatalogueRouteReferences,
    private val announcer: com.retro99.catalogue.ui.downloads.CatalogueDownloadAnnouncer,
    @InjectedParam private val sourceId: String,
    @InjectedParam publicationRef: String,
) : ViewModel() {
    val page = CatalogueBookPage(sourceId, references.book(sourceId, publicationRef), gateway, library, queue, viewModelScope, announcer::cancelled)
    fun visible() = announcer.visible(this, sourceId, page.announcementKeys)
    fun hidden() = announcer.hidden(this)
    override fun onCleared() { hidden(); page.cancel() }
}
