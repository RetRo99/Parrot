package com.retro99.reader.domain.usecase

import com.retro99.books.domain.model.links.LinkedCopy
import com.retro99.database.api.books.PositionDatabase
import com.retro99.database.api.links.LinkedCopyWritesDatabase
import com.retro99.reader.domain.linked.LinkedCopiesSource
import com.retro99.reader.domain.linked.observedAtMillis
import com.retro99.reader.domain.model.PositionDomainModel
import com.retro99.reader.domain.model.toPositionDomainModel
import com.retro99.reader.domain.translate.CopyContentCache
import com.retro99.reader.domain.translate.CopyFileLocator
import com.retro99.reader.domain.translate.progressKind
import com.retro99.reader.domain.write.CopyWrite
import com.retro99.reader.domain.write.CopyWriteGuards
import com.retro99.reader.domain.write.CopyWriteResult
import com.retro99.reader.domain.write.LinkedCopyPropagationSetting
import com.retro99.reader.domain.write.WriteCopyPositionUseCase
import com.retro99.server.api.PositionOrigin
import com.retro99.sync.domain.ObservedTime
import org.koin.core.annotation.Factory
import org.koin.core.annotation.Provided
import kotlin.math.abs
import kotlin.time.Clock
import kotlin.time.Duration.Companion.days

/**
 * After reading on this device, moves the other linked copies to the same place (P5, slice 4),
 * so Storyteller's and Audiobookshelf's own apps resume there too. For each other copy:
 * translate, keep only Exact or High, skip when that copy was read more recently, apply the
 * threshold (guard 4), deduplication (guard 6) and collapse guards (7 and 8), then write it
 * with origin `linked_copy` and the source's observation time. Only `user` positions
 * propagate, so written, pulled and manual positions never start a chain (loop guard).
 * Backward moves propagate like any other (guard 5).
 */
@Factory
class PropagateToLinkedCopiesUseCase(
    private val linkedCopiesSource: LinkedCopiesSource,
    private val translation: PositionTranslation,
    private val writeCopyPositionUseCase: WriteCopyPositionUseCase,
    private val fileLocator: CopyFileLocator,
    private val contentCache: CopyContentCache,
    @Provided private val setting: LinkedCopyPropagationSetting,
    @Provided private val positionDatabase: PositionDatabase,
    @Provided private val linkedCopyWritesDatabase: LinkedCopyWritesDatabase,
) {
    /**
     * @param position the position just saved for `(serverId, bookUuid)`; read from storage
     * when null.
     * @return what was written, per target copy.
     */
    suspend operator fun invoke(
        serverId: String,
        bookUuid: String,
        position: PositionDomainModel? = null,
    ): List<ApplyResult> {
        if (!setting.isEnabled()) return emptyList()
        val copies = linkedCopiesSource.linkedCopies(serverId, bookUuid) ?: return emptyList()
        val source = copies.self
        val sourcePosition = position
            ?: positionDatabase.getPositionByBookUuid(bookUuid)?.toPositionDomainModel(serverId)
            ?: return emptyList()
        if (sourcePosition.origin != PositionOrigin.User) return emptyList()
        val sourceObservedAt = sourcePosition.observedAt ?: return emptyList()
        val sourceMillis = sourcePosition.observedAtMillis ?: return emptyList()
        val cutoff = Clock.System.now().minus(WRITE_LOG_DAYS.days).toString()

        return copies.others.mapNotNull { target ->
            val translated = translation.translate(source, sourcePosition, target, copies.all)
                ?.takeIf { result -> result.confidence.isReliable }
                ?: return@mapNotNull null
            val current = positionDatabase.getPositionByBookUuid(target.uuid)
                ?.toPositionDomainModel(target.serverId)
            // The target was read more recently: never overwrite newer reading.
            val currentMillis = current?.observedAtMillis
            if (current != null && current.origin.isRealReading && currentMillis != null &&
                currentMillis > sourceMillis
            ) {
                return@mapNotNull null
            }
            // Guard 6: the same source reading is written to a target once.
            val lastWrite = linkedCopyWritesDatabase.getWrite(target.key.value, notBefore = cutoff)
            val alreadyWritten = lastWrite?.sourceKey == source.key.value &&
                ObservedTime.toEpochMillis(lastWrite.sourceObservedAt) == sourceMillis
            if (alreadyWritten) return@mapNotNull null

            val newProgression = translated.position.totalProgression
            val movedCharacters = movedCharacters(
                target = target,
                currentProgression = current?.totalProgression,
                newProgression = newProgression,
            )
            val worthWriting = CopyWriteGuards.exceedsThreshold(
                kind = target.progressKind,
                currentProgression = current?.totalProgression,
                newProgression = newProgression,
                movedCharacters = movedCharacters,
            )
            if (!worthWriting) return@mapNotNull null
            val sourceProgression = sourcePosition.totalProgression
            if (CopyWriteGuards.collapsesToStart(sourceProgression, newProgression) ||
                CopyWriteGuards.collapsesToEnd(sourceProgression, newProgression)
            ) {
                return@mapNotNull null
            }

            val result = writeCopyPositionUseCase(
                CopyWrite(
                    source = source,
                    sourceObservedAt = sourceObservedAt,
                    target = target,
                    position = translated.position,
                    origin = PositionOrigin.LinkedCopy,
                ),
            )
            ApplyResult(target, result).takeIf { _ -> result !is CopyWriteResult.Refused }
        }
    }

    /** How far, in characters, the target's text position moves; null when unknown. */
    private suspend fun movedCharacters(
        target: LinkedCopy,
        currentProgression: Double?,
        newProgression: Double?,
    ): Int? {
        if (currentProgression == null || newProgression == null) return null
        val file = fileLocator.locate(target) ?: return null
        val length = contentCache.chapters(file.path)
            ?.sumOf { chapter -> chapter.text.length }
            ?: return null
        return (abs(newProgression - currentProgression) * length).toInt()
    }

    private companion object {
        const val WRITE_LOG_DAYS = 7
    }
}
