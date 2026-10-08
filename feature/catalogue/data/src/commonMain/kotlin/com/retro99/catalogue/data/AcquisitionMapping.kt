package com.retro99.catalogue.data

import com.retro99.catalogue.domain.AcquisitionState
import com.retro99.catalogue.domain.CatalogueAcquisition
import com.retro99.catalogue.domain.CatalogueAcquisitionLimits
import com.retro99.catalogue.domain.CatalogueAcquisitionRequest
import com.retro99.database.api.catalogue.CatalogueAcquisitionEntity
import com.retro99.database.api.catalogue.CatalogueBookSourceEntity

internal fun CatalogueAcquisitionEntity.acquisitionState(): AcquisitionState? =
    AcquisitionState.fromStorage(state, failureReason)

/** Null for a row whose state is not one this version knows; loading a profile makes such a row interrupted. */
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
        neededBytes = neededBytes,
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
        rightsText = rightsText?.take(CatalogueAcquisitionLimits.MAX_RIGHTS_LENGTH),
        catalogueUpdated = catalogueUpdated?.takeIf { it.length <= CatalogueAcquisitionLimits.MAX_CATALOGUE_UPDATED_LENGTH },
    )
}

/**
 * Where the book came from, written once it is in the library. The row's id is the request's
 * id, so writing it again for the same request changes nothing.
 *
 * @param sourceAddress the catalogue's address as registered, when it still is; only its
 *   scheme, host and port are kept
 */
internal fun CatalogueAcquisitionEntity.toBookSource(
    libraryBookId: String,
    sourceAddress: String?,
    now: Long,
) = CatalogueBookSourceEntity(
    id = requestId,
    libraryBookId = libraryBookId,
    sourceId = sourceId,
    catalogueName = catalogueName,
    catalogueOrigin = originOf(sourceAddress) ?: originOf(detailUrl).orEmpty(),
    publicationKey = publicationKey,
    detailIdentity = detailIdentity,
    selectedFormat = representationKey.substringBeforeLast('#'),
    rightsText = rightsText,
    catalogueUpdated = catalogueUpdated,
    contentHash = localHash.orEmpty(),
    acquiredAt = now,
)

/**
 * Scheme, host and port of [address], lower-cased, with the scheme's usual port filled in:
 * `https://books.example:443`. Never the path, the query or a user name: those can hold a key.
 * Null when [address] is not an http or https address.
 */
internal fun originOf(address: String?): String? {
    if (address == null) return null
    val scheme = address.substringBefore("://", missingDelimiterValue = "").lowercase()
    val defaultPort = when (scheme) {
        "https" -> 443
        "http" -> 80
        else -> return null
    }
    val authority = address.substringAfter("://").takeWhile { it != '/' && it != '?' && it != '#' }
        .substringAfterLast('@')
    val portStart = authority.lastIndexOf(':').takeIf { it > authority.lastIndexOf(']') }
    val host = (if (portStart == null) authority else authority.substring(0, portStart)).lowercase()
    if (host.isEmpty()) return null
    val port = if (portStart == null) defaultPort else authority.substring(portStart + 1).toIntOrNull() ?: return null
    return "$scheme://$host:$port"
}
