package com.retro99.catalogue.data

import com.retro99.server.api.CatalogueAcquisitionLocator
import com.retro99.server.api.CatalogueAcquisitionRepository
import com.retro99.server.api.CatalogueDownloadFailure
import com.retro99.server.api.CatalogueDownloadOutcome
import com.retro99.server.api.CatalogueFileSink
import com.retro99.server.api.CatalogueRepositoryProvider
import kotlinx.coroutines.CancellationException

/** Streams one catalogue file for one profile. The link is looked up again on every call. */
interface AcquisitionFileSource {
    suspend fun download(
        profileId: String,
        sourceId: String,
        locator: CatalogueAcquisitionLocator,
        sink: CatalogueFileSink,
    ): CatalogueDownloadOutcome
}

/** Goes through the registered catalogue source, so its account details and rules apply. */
internal class RegistryAcquisitionFileSource(
    private val repositories: CatalogueRepositoryProvider,
    private val activeProfileId: () -> String?,
) : AcquisitionFileSource {
    override suspend fun download(
        profileId: String,
        sourceId: String,
        locator: CatalogueAcquisitionLocator,
        sink: CatalogueFileSink,
    ): CatalogueDownloadOutcome {
        // The provider hands out sessions of the open profile only.
        if (activeProfileId() != profileId) throw CancellationException("Profile is no longer open")
        val repository = try {
            repositories.getRepository(sourceId) as? CatalogueAcquisitionRepository
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: IllegalStateException) {
            null
        } catch (_: IllegalArgumentException) {
            null
        }
        // Removed, turned off, or not a catalogue that hands over files.
            ?: return CatalogueDownloadOutcome.Failed(CatalogueDownloadFailure.Refused)
        if (activeProfileId() != profileId) throw CancellationException("Profile is no longer open")
        return repository.download(locator, sink)
    }
}
