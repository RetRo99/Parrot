package com.retro99.reader.domain.usecase

import com.retro99.books.domain.model.links.CopyKey
import com.retro99.books.domain.model.links.LinkedCopy
import com.retro99.database.api.books.PositionDatabase
import com.retro99.database.api.links.LinkedCopyWritesDatabase
import com.retro99.reader.domain.linked.LinkedCopiesSource
import com.retro99.reader.domain.linked.PositionCandidate
import com.retro99.reader.domain.linked.RemoteCopyPositions
import com.retro99.reader.domain.linked.RemoteFetch
import com.retro99.reader.domain.linked.latestRealReading
import com.retro99.reader.domain.linked.observedAtMillis
import com.retro99.reader.domain.model.PositionDomainModel
import com.retro99.reader.domain.model.toPositionDomainModel
import com.retro99.reader.domain.positions.CopyPositionRow
import com.retro99.reader.domain.positions.PositionSource
import com.retro99.reader.domain.translate.CopyContentCache
import com.retro99.reader.domain.translate.CopyFileLocator
import com.retro99.reader.domain.translate.anchorAt
import com.retro99.reader.domain.translate.pointOf
import com.retro99.server.api.PositionOrigin
import com.retro99.server.api.TextAnchor
import org.koin.core.annotation.Factory
import org.koin.core.annotation.Provided
import kotlin.time.Clock
import kotlin.time.Duration.Companion.days

/**
 * Every linked copy's current position, for the positions panel (§1.3b). Server copies are
 * fetched with a 2 second budget; one that fails shows its last known position as stale.
 * Fetched positions go through the echo check. Returns null for a book that isn't linked.
 */
@Factory
class ObserveCopyPositionsUseCase(
    private val linkedCopiesSource: LinkedCopiesSource,
    private val remoteCopyPositions: RemoteCopyPositions,
    private val fileLocator: CopyFileLocator,
    private val contentCache: CopyContentCache,
    @Provided private val positionDatabase: PositionDatabase,
    @Provided private val linkedCopyWritesDatabase: LinkedCopyWritesDatabase,
) {
    suspend operator fun invoke(serverId: String, bookUuid: String): List<CopyPositionRow>? {
        val copies = linkedCopiesSource.linkedCopies(serverId, bookUuid) ?: return null
        val fetched = remoteCopyPositions.fetch(copies.all)

        val chosen = copies.all.map { copy ->
            val local = positionDatabase.getPositionByBookUuid(copy.uuid)
                ?.toPositionDomainModel(copy.serverId)
            val fetch = fetched[copy]
            val remote = (fetch as? RemoteFetch.Fetched)?.position
            Choice(copy, newer(local, remote), isStale = fetch == RemoteFetch.Failed)
        }
        val latest = latestRealReading(
            chosen.mapNotNull { choice ->
                choice.position?.let { position -> PositionCandidate(choice.copy, position) }
            },
        )

        return chosen.map { choice ->
            val position = choice.position
            CopyPositionRow(
                copy = choice.copy,
                position = position,
                observedAt = position?.observedAt,
                origin = position?.origin,
                sourceLabel = sourceOf(position, choice.copy, copies.all),
                isLatest = latest != null && latest.copy.key == choice.copy.key,
                isStale = choice.isStale,
                excerpt = position?.let { found -> excerptOf(choice.copy, found) },
            )
        }
    }

    private class Choice(
        val copy: LinkedCopy,
        val position: PositionDomainModel?,
        val isStale: Boolean,
    )

    /** The server's position when it's newer than the stored one. */
    private fun newer(
        local: PositionDomainModel?,
        remote: PositionDomainModel?,
    ): PositionDomainModel? {
        if (local == null || remote == null) return remote ?: local
        val localMillis = local.observedAtMillis ?: return remote
        val remoteMillis = remote.observedAtMillis ?: return local
        return if (remoteMillis > localMillis) remote else local
    }

    private suspend fun sourceOf(
        position: PositionDomainModel?,
        copy: LinkedCopy,
        copies: List<LinkedCopy>,
    ): PositionSource = when (position?.origin) {
        PositionOrigin.User -> PositionSource.ThisDevice
        PositionOrigin.Remote -> PositionSource.Server
        PositionOrigin.Manual, PositionOrigin.LinkedCopy -> {
            val cutoff = Clock.System.now().minus(WRITE_LOG_DAYS.days).toString()
            val sourceKey = linkedCopyWritesDatabase.getWrite(copy.key.value, notBefore = cutoff)
                ?.sourceKey
                ?.let(CopyKey::parse)
            PositionSource.SetFrom(copies.firstOrNull { other -> other.key == sourceKey })
        }
        PositionOrigin.Restore, null -> PositionSource.Unknown
    }

    /** The stored anchor, or the text at the locator when the copy's file is here. */
    private suspend fun excerptOf(copy: LinkedCopy, position: PositionDomainModel): TextAnchor? {
        position.textAnchor?.let { anchor -> return anchor }
        if (position.locatorHref == null) return null
        val file = fileLocator.locate(copy) ?: return null
        val chapters = contentCache.chapters(file.path) ?: return null
        val point = chapters.pointOf(
            href = position.locatorHref,
            progression = position.progression,
            cssSelector = position.cssSelector,
        ) ?: return null
        return chapters.anchorAt(point)
    }

    private companion object {
        const val WRITE_LOG_DAYS = 7
    }
}
