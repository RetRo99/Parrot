package com.retro99.catalogue.data

import com.retro99.base.AppInitializer
import com.retro99.catalogue.domain.CatalogueAcquisitionManager
import com.retro99.user.api.UserRegistry
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import org.koin.core.annotation.Provided
import org.koin.core.annotation.Single

/**
 * App start, and every profile that is opened after it: settle what Parrot was doing to that
 * profile's downloads when it last closed. Off the main thread, and nothing is downloaded.
 */
@Single(binds = [AppInitializer::class])
class CatalogueAcquisitionStartup(
    private val acquisitions: CatalogueAcquisitionManager,
    @Provided private val users: UserRegistry,
) : AppInitializer {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    override fun initialize() {
        settleDownloadsWhenProfileOpens(acquisitions, users, scope)
    }
}

/** The first profile may not exist yet when Parrot starts; it arrives through the flow. */
internal fun settleDownloadsWhenProfileOpens(
    acquisitions: CatalogueAcquisitionManager,
    users: UserRegistry,
    scope: CoroutineScope,
): Job = users.observeActiveProfile()
    .map { profile -> profile?.id }
    .distinctUntilChanged()
    .filterNotNull()
    .onEach {
        try {
            acquisitions.restoreAfterRestart()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            // One profile that cannot be read must not stop the next one from being settled.
            // The same recovery runs the first time the queue is used.
        }
    }
    .launchIn(scope)
