package com.retro99.reader.domain.usecase

import com.retro99.books.domain.model.links.LinkedCopy
import com.retro99.database.api.books.PositionDatabase
import com.retro99.reader.domain.model.PositionDomainModel
import com.retro99.reader.domain.translate.CopyContent
import com.retro99.reader.domain.translate.CopyContentCache
import com.retro99.reader.domain.translate.CopyFileLocator
import com.retro99.reader.domain.translate.CopyPositionTranslator
import com.retro99.reader.domain.translate.TranslatedPosition
import com.retro99.reader.domain.translate.TranslationCache
import com.retro99.reader.domain.translate.progressKind
import com.retro99.sync.domain.ProgressKind
import org.koin.core.annotation.Factory
import org.koin.core.annotation.Provided

/**
 * Translates a position in one linked copy into another (§1.5). It finds the copies' files on
 * this device, loads their text and SMIL timing through `lib/epub`, and runs the translator.
 * Results are cached per (source copy, source observation time, target copy).
 */
/** Translates a position in one linked copy into another (§1.5). */
fun interface PositionTranslation {
    suspend fun translate(
        source: LinkedCopy,
        position: PositionDomainModel,
        target: LinkedCopy,
        others: List<LinkedCopy>,
    ): TranslatedPosition?
}

@Factory(binds = [PositionTranslation::class])
class TranslatePositionUseCase(
    private val fileLocator: CopyFileLocator,
    private val contentCache: CopyContentCache,
    private val translationCache: TranslationCache,
    @Provided private val positionDatabase: PositionDatabase,
) : PositionTranslation {
    private val translator = CopyPositionTranslator()

    override suspend fun translate(
        source: LinkedCopy,
        position: PositionDomainModel,
        target: LinkedCopy,
        others: List<LinkedCopy>,
    ): TranslatedPosition? = invoke(source, position, target, others)

    suspend operator fun invoke(
        source: LinkedCopy,
        position: PositionDomainModel,
        target: LinkedCopy,
        others: List<LinkedCopy> = emptyList(),
    ): TranslatedPosition? {
        val cacheKey = position.observedAt
            ?.let { observedAt -> "${source.key.value}|$observedAt|${target.key.value}" }
        cacheKey?.let { key -> translationCache.get(key) }?.let { cached -> return cached }

        val bridges = others
            .filter { copy -> copy.key != source.key && copy.key != target.key }
            .filter { copy -> copy.hasReadaloud }
            .map { copy -> content(copy) }
            .filter { content -> content.readaloud != null }
        val result = translator.translate(
            source = content(source, knownDurationMs = position.totalDurationMs),
            position = position,
            target = content(target),
            others = bridges,
        )
        if (result != null && cacheKey != null) translationCache.put(cacheKey, result)
        return result
    }

    private suspend fun content(copy: LinkedCopy, knownDurationMs: Long? = null): CopyContent {
        val kind = copy.progressKind
        val hasFile = kind != ProgressKind.AUDIO || copy.hasEbook
        val file = if (hasFile) fileLocator.locate(copy) else null
        val audioDuration = if (kind == ProgressKind.AUDIO) {
            knownDurationMs ?: positionDatabase.getPositionByBookUuid(copy.uuid)?.totalDurationMs
        } else {
            null
        }
        return CopyContent(
            key = copy.key,
            serverId = copy.serverId,
            bookUuid = copy.uuid,
            kind = kind,
            contentHash = file?.contentHash,
            chapters = file?.let { found -> contentCache.chapters(found.path) },
            timing = file?.takeIf { found -> found.isReadaloud }
                ?.let { found -> contentCache.timing(found.path) },
            audioDurationMs = audioDuration,
        )
    }
}
