package com.retro99.catalogue.ui.browse

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.retro99.catalogue.domain.CatalogueLibraryLookup
import com.retro99.catalogue.ui.navigation.CatalogueBookPlace
import com.retro99.catalogue.ui.navigation.CataloguePlace
import com.retro99.catalogue.ui.navigation.CatalogueRouteReferences
import org.koin.core.annotation.InjectedParam
import org.koin.core.annotation.KoinViewModel
import org.koin.core.annotation.Provided

/**
 * Keeps one browser page while its screen is in the back stack: through rotation and through
 * going into a book and back, with its search text, filter, loaded books and scroll position.
 *
 * @param targetRef an in-memory reference to the page, or empty for the catalogue's first page.
 *   A reference that no longer resolves (after the app was restarted) also gives the first page.
 */
@KoinViewModel
class CatalogueBrowseViewModel(
    gateway: CatalogueBrowseGateway,
    @Provided library: CatalogueLibraryLookup,
    @Provided queue: com.retro99.catalogue.domain.CatalogueAcquisitionManager,
    private val references: CatalogueRouteReferences,
    @InjectedParam private val sourceId: String,
    @InjectedParam targetRef: String,
) : ViewModel() {
    val browser = CatalogueBrowser(
        sourceId = sourceId,
        start = targetRef.takeIf(String::isNotEmpty)?.let { references.place(sourceId, it) },
        gateway = gateway,
        library = library,
        scope = viewModelScope,
        queue = queue,
    )

    /** First visible item and its offset, per list ([CatalogueBrowseState.listId]). */
    val scroll = mutableMapOf<Int, Pair<Int, Int>>()

    fun reference(place: CataloguePlace): String = references.referenceTo(sourceId, place)
    fun reference(book: CatalogueBookPlace): String = references.referenceTo(sourceId, book)

    override fun onCleared() = browser.cancel()
}
