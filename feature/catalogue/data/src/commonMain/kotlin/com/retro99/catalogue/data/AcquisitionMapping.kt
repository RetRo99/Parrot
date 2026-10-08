package com.retro99.catalogue.data

import com.retro99.catalogue.domain.AcquisitionState
import com.retro99.catalogue.domain.CatalogueAcquisition
import com.retro99.catalogue.domain.CatalogueAcquisitionLimits
import com.retro99.catalogue.domain.CatalogueAcquisitionRequest
import com.retro99.database.api.catalogue.CatalogueAcquisitionEntity

internal fun CatalogueAcquisitionEntity.acquisitionState(): AcquisitionState? =
    AcquisitionState.fromStorage(state, failureReason)

/** Null for a row whose state is not one this version knows; such a row is left alone. */
internal fun CatalogueAcquisitionEntity.toAcquisition(): CatalogueAcquisition? {
    val state = acquisitionState() ?: return null
    return CatalogueAcquisition(
        requestId = requestId,
        sourceId = sourceId,
        publicationKey = publicationKey,
        representationKey = representationKey,
        detailIdentity = detailIdentity,
        title = title,
        author = author,
        coverReference = coverReference,
        catalogueName = catalogueName,
        state = state,
        queuePosition = queuePosition,
        expectedSizeBytes = expectedSizeBytes,
        bytesSoFar = bytesSoFar,
        localHash = localHash,
        stagedFilePath = stagingPath?.takeIf { state == AcquisitionState.Adding },
        libraryBookId = libraryBookId,
        createdAt = createdAt,
        updatedAt = updatedAt,
        completedAt = completedAt,
        attempts = attempts,
    )
}

internal fun CatalogueAcquisitionEntity.withState(state: AcquisitionState, now: Long) = copy(
    state = state.key,
    failureReason = state.failureReason?.key,
    updatedAt = now,
)

/** The snapshot is cut to its limits here, so no caller can store an unbounded row. */
internal fun CatalogueAcquisitionRequest.toWaitingEntity(
    requestId: String,
    queuePosition: Long,
    now: Long,
): CatalogueAcquisitionEntity {
    require(sourceId.isNotBlank() && publicationKey.isNotBlank() && representationKey.isNotBlank()) {
        "A catalogue download needs a source, a publication and a file"
    }
    require(
        listOf(sourceId, publicationKey, representationKey, listingUrl)
            .all { key -> key.length <= CatalogueAcquisitionLimits.MAX_KEY_LENGTH },
    ) { "A catalogue download key is too long to store" }
    return CatalogueAcquisitionEntity(
        requestId = requestId,
        sourceId = sourceId,
        publicationKey = publicationKey,
        detailIdentity = detailIdentity?.takeIf { it.length <= CatalogueAcquisitionLimits.MAX_IDENTITY_LENGTH },
        detailUrl = listingUrl,
        representationKey = representationKey,
        title = title.take(CatalogueAcquisitionLimits.MAX_TITLE_LENGTH),
        author = author?.take(CatalogueAcquisitionLimits.MAX_AUTHOR_LENGTH),
        coverReference = coverReference?.takeIf { it.length <= CatalogueAcquisitionLimits.MAX_COVER_REFERENCE_LENGTH },
        catalogueName = catalogueName.take(CatalogueAcquisitionLimits.MAX_CATALOGUE_NAME_LENGTH),
        state = AcquisitionState.Waiting.key,
        queuePosition = queuePosition,
        stagingPath = null,
        expectedSizeBytes = expectedSizeBytes?.takeIf { it >= 0 },
        bytesSoFar = 0,
        localHash = null,
        libraryBookId = null,
        failureReason = null,
        createdAt = now,
        updatedAt = now,
        completedAt = null,
        attempts = 0,
    )
}
