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
    @InjectedParam sourceId: String,
    @InjectedParam publicationRef: String,
) : ViewModel() {
    val page = CatalogueBookPage(sourceId, references.book(sourceId, publicationRef), gateway, library, queue, viewModelScope)
    override fun onCleared() = page.cancel()
}
