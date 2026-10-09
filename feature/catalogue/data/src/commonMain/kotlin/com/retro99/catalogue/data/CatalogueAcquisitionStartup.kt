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
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import org.koin.core.annotation.Provided
import org.koin.core.annotation.Single

/**
 * App start, and every profile that is opened after it: settle what Parrot was doing to that
 * profile's downloads when it last closed. Off the main thread, and nothing is downloaded.
 * It also removes the staging folders of profiles that no longer exist.
 */
@Single(binds = [AppInitializer::class])
class CatalogueAcquisitionStartup(
    private val acquisitions: CatalogueAcquisitionManager,
    @Provided private val users: UserRegistry,
    private val files: CatalogueStagingFiles,
) : AppInitializer {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    override fun initialize() {
        settleDownloadsWhenProfileOpens(acquisitions, users, scope)
        removeStagingOfDeletedProfiles(files, users, scope)
    }
}

/**
 * A deleted profile's staging folder goes with it, and so does one left by a profile deleted
 * while Parrot was closed. An empty list is "not loaded yet", never "delete everything".
 * The registry stops a profile's downloads before it takes the profile off the list.
 */
internal fun removeStagingOfDeletedProfiles(
    files: CatalogueStagingFiles,
    users: UserRegistry,
    scope: CoroutineScope,
): Job = users.observeAllProfiles()
    .map { profiles -> profiles.map { profile -> profile.id }.toSet() }
    .distinctUntilChanged()
    .filter { profileIds -> profileIds.isNotEmpty() }
    .onEach { profileIds -> files.deleteFoldersExcept(profileIds) }
    .launchIn(scope)

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
