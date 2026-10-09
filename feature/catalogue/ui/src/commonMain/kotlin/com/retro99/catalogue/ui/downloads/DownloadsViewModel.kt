package com.retro99.catalogue.ui.downloads

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.retro99.catalogue.domain.CatalogueAcquisitionManager
import com.retro99.catalogue.ui.browse.CatalogueBrowseGateway
import com.retro99.user.api.UserRegistry
import kotlinx.coroutines.flow.map
import org.koin.core.annotation.KoinViewModel
import org.koin.core.annotation.Provided

@KoinViewModel
class DownloadsViewModel(@Provided queue: CatalogueAcquisitionManager, gateway: CatalogueBrowseGateway, @Provided users: UserRegistry, private val announcer: CatalogueDownloadAnnouncer) : ViewModel() {
    val page = DownloadsPage(queue, gateway, users.observeActiveProfile().map { it?.id }, viewModelScope, onCancelled = announcer::cancelled,
        isProfileCurrent = { it == users.getActiveProfileId() })
    fun visible() = announcer.visible(this, "", emptyList(), all = true)
    fun hidden() = announcer.hidden(this)
    override fun onCleared() { hidden(); page.leave(); page.cancel() }
}
