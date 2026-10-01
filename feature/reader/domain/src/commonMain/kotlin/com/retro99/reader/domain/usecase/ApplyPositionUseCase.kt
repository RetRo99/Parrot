package com.retro99.reader.domain.usecase

import com.retro99.books.domain.model.links.LinkedCopy
import com.retro99.reader.domain.positions.ApplyPreview
import com.retro99.reader.domain.positions.CopyPositionRow
import com.retro99.reader.domain.write.CopyWrite
import com.retro99.reader.domain.write.CopyWriteResult
import com.retro99.reader.domain.write.WriteCopyPositionUseCase
import com.retro99.server.api.PositionOrigin
import org.koin.core.annotation.Factory

data class ApplyResult(
    val target: LinkedCopy,
    val result: CopyWriteResult,
)

/**
 * Applies the chosen position to each ticked copy (§1.3b): origin `manual`, observed now, no
 * threshold, a write-log row each. The source copy is never written. One failure doesn't stop
 * the others; the result lists every target.
 */
@Factory
class ApplyPositionUseCase(
    private val writeCopyPositionUseCase: WriteCopyPositionUseCase,
) {
    suspend operator fun invoke(
        source: CopyPositionRow,
        ticked: List<ApplyPreview>,
    ): List<ApplyResult> = ticked
        .filter { preview -> preview.target.key != source.copy.key && preview.enabled }
        .mapNotNull { preview ->
            val translated = preview.translated ?: return@mapNotNull null
            val result = writeCopyPositionUseCase(
                CopyWrite(
                    source = source.copy,
                    sourceObservedAt = null,
                    target = preview.target,
                    position = translated.position,
                    origin = PositionOrigin.Manual,
                ),
            )
            ApplyResult(preview.target, result)
        }
}
