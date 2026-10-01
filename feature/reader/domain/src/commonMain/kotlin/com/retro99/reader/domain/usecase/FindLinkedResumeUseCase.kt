package com.retro99.reader.domain.usecase

import com.github.michaelbull.result.get
import com.retro99.books.domain.model.links.CopySource
import com.retro99.books.domain.model.links.LinkedCopy
import com.retro99.database.api.books.PositionDatabase
import com.retro99.reader.domain.linked.LinkedCopiesSource
import com.retro99.reader.domain.linked.LinkedResumeDismissals
import com.retro99.reader.domain.linked.LinkedResumeOffer
import com.retro99.reader.domain.linked.PositionCandidate
import com.retro99.reader.domain.linked.latestRealReading
import com.retro99.reader.domain.model.PositionDomainModel
import com.retro99.reader.domain.model.toPositionDomainModel
import com.retro99.reader.domain.translate.TranslatedPosition
import com.retro99.reader.domain.translate.progressKind
import com.retro99.server.api.AuthenticatedRepositoryProvider
import com.retro99.server.api.PositionOrigin
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withTimeoutOrNull
import org.koin.core.annotation.Factory
import org.koin.core.annotation.Provided
import kotlin.math.abs

/**
 * Decides whether opening a linked copy should offer to continue from another copy (§1.3,
 * steps 1–2). Both prompt places use this: book detail before opening, and the reader when it
 * starts. The latest real reading wins, never the furthest (P1).
 */
@Factory
class FindLinkedResumeUseCase(
    private val linkedCopiesSource: LinkedCopiesSource,
    private val translatePositionUseCase: TranslatePositionUseCase,
    @Provided private val dismissals: LinkedResumeDismissals,
    @Provided private val positionDatabase: PositionDatabase,
    @Provided private val repositoryProvider: AuthenticatedRepositoryProvider,
) {

    suspend operator fun invoke(serverId: String, bookUuid: String): LinkedResumeOffer? {
        val copies = linkedCopiesSource.linkedCopies(serverId, bookUuid) ?: return null
        val target = copies.self

        val targetCandidates = listOfNotNull(
            positionDatabase.getPositionByBookUuid(target.uuid)?.let { entity ->
                PositionCandidate(target, entity.toPositionDomainModel(serverId))
            },
            positionDatabase.getRemotePositionByBookUuid(target.uuid)?.let { entity ->
                // The remote baseline is a pulled server position.
                val position = entity.toPositionDomainModel(serverId)
                PositionCandidate(target, position.copy(origin = PositionOrigin.Remote))
            },
        )
        val otherCandidates = copies.others.mapNotNull { copy ->
            positionDatabase.getPositionByBookUuid(copy.uuid)?.let { entity ->
                PositionCandidate(copy, entity.toPositionDomainModel(copy.serverId))
            }
        } + fetchRemoteCandidates(copies.others)

        val latest = latestRealReading(targetCandidates + otherCandidates) ?: return null
        if (latest.copy.key == target.key) return null
        val latestMillis = requireNotNull(latest.observedAtMillis)
        val targetLatestMillis = latestRealReading(targetCandidates)?.observedAtMillis
        if (targetLatestMillis != null && latestMillis - targetLatestMillis <= NEWER_BY_MS) {
            return null
        }

        val observedAt = latest.position.observedAt ?: return null
        val offer = translatePositionUseCase(
            source = latest.copy,
            position = latest.position,
            target = target,
            others = copies.others,
        )?.let { translated ->
            LinkedResumeOffer(
                target = target,
                source = latest.copy,
                sourceKind = latest.copy.progressKind,
                observedAt = observedAt,
                translated = translated,
            )
        } ?: return null

        val current = targetCandidates.firstOrNull()?.position
        if (!isDifferentPlace(offer.translated, current)) return null
        if (dismissals.isDismissed(offer.dismissalEntry)) return null
        return offer
    }

    /**
     * The server's own latest position for each linked Storyteller or Audiobookshelf copy,
     * waiting at most 2 seconds. Copies that fail or time out are skipped.
     */
    private suspend fun fetchRemoteCandidates(copies: List<LinkedCopy>): List<PositionCandidate> =
        coroutineScope {
            copies
                .filter { copy -> copy.key.source != CopySource.Library }
                .map { copy ->
                    async {
                        withTimeoutOrNull(REMOTE_BUDGET_MS) {
                            repositoryProvider.getReaderRepository(copy.serverId)
                                ?.getRemotePosition(copy.uuid)
                                ?.get()
                                ?.toPositionDomainModel()
                                ?.copy(serverId = copy.serverId, origin = PositionOrigin.Remote)
                                ?.let { position -> PositionCandidate(copy, position) }
                        }
                    }
                }
                .awaitAll()
                .filterNotNull()
        }

    /** A different chapter, or more than 1% apart. No current position counts as the start. */
    private fun isDifferentPlace(
        translated: TranslatedPosition,
        current: PositionDomainModel?,
    ): Boolean {
        val offered = translated.position
        val currentProgression = current?.totalProgression ?: 0.0
        val offeredProgression = offered.totalProgression ?: return true
        val currentHref = current?.locatorHref?.substringBefore('#')?.trimStart('/')
        val offeredHref = offered.locatorHref?.substringBefore('#')?.trimStart('/')
        if (currentHref != null && offeredHref != null && currentHref != offeredHref) return true
        return abs(offeredProgression - currentProgression) > PLACE_TOLERANCE
    }

    private companion object {
        const val NEWER_BY_MS = 60_000L
        const val REMOTE_BUDGET_MS = 2_000L
        const val PLACE_TOLERANCE = 0.01
    }
}
