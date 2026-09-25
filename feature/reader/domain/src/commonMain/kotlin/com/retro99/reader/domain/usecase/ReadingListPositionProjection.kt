package com.retro99.reader.domain.usecase

import com.retro99.books.domain.model.UnifiedBookProgressSource
import com.retro99.server.api.ServerPosition

internal fun latestPositionsByLibraryBookId(
    positions: List<ServerPosition>,
): Map<String, ServerPosition> = positions
    .filter { position -> position.libraryBookId != null }
    .groupBy { position -> requireNotNull(position.libraryBookId) }
    .mapValues { (_, libraryPositions) ->
        requireNotNull(libraryPositions.maxWithOrNull(positionRevisionAndUpdateOrder()))
    }

internal fun resolveMemberLocalPosition(
    source: UnifiedBookProgressSource,
    positions: List<ServerPosition>,
    positionsByLibraryBookId: Map<String, ServerPosition>,
    ambiguousBookUuids: Set<String>,
    ambiguousSourceScopes: Set<Pair<String, String?>>,
    mediaType: String,
): ServerPosition? {
    val uuidPositions = positions.filter { position -> position.bookUuid == source.bookUuid }
    val sourceServerId = source.serverId
    if (sourceServerId != null) {
        val sourceScopedCandidates = uuidPositions
            .filter { position -> position.serverId == sourceServerId }
        val exactSourceScopedCandidates = if (
            (source.bookUuid to sourceServerId) in ambiguousSourceScopes
        ) {
            source.libraryBookId?.let { libraryBookId ->
                sourceScopedCandidates.filter { position ->
                    position.libraryBookId == libraryBookId
                }
            }.orEmpty()
        } else {
            sourceScopedCandidates
        }
        val sourceScopedPosition = exactSourceScopedCandidates
            .filter { position -> position.supportsMediaType(mediaType, source.mediaTypes) }
            .maxWithOrNull(positionRevisionAndUpdateOrder())
        if (sourceScopedPosition != null) return sourceScopedPosition
    }

    val unscopedUuidPositions = uuidPositions
        .filter { position -> position.serverId.isBlank() }
        .filter { position -> position.supportsMediaType(mediaType, source.mediaTypes) }
    val hasAmbiguousUuid = source.bookUuid in ambiguousBookUuids
    val exactUnscopedPosition = when {
        unscopedUuidPositions.isEmpty() -> null
        !hasAmbiguousUuid -> unscopedUuidPositions.maxWithOrNull(positionRevisionAndUpdateOrder())
        source.libraryBookId == null -> null
        else -> unscopedUuidPositions
            .filter { position -> position.libraryBookId == source.libraryBookId }
            .maxWithOrNull(positionRevisionAndUpdateOrder())
    }
    if (exactUnscopedPosition != null) return exactUnscopedPosition

    return source.libraryBookId
        ?.let { libraryBookId -> positionsByLibraryBookId[libraryBookId] }
        ?.takeIf { position -> position.supportsMediaType(mediaType, source.mediaTypes) }
}

private fun positionRevisionAndUpdateOrder(): Comparator<ServerPosition> =
    compareBy<ServerPosition> { position -> position.remoteRevision }
        .thenBy { position -> position.updatedAt }
