package com.retro99.reader.domain.usecase

import com.retro99.reader.domain.positions.ApplyDisabledReason
import com.retro99.reader.domain.positions.ApplyPreview
import com.retro99.reader.domain.positions.ApplyWarning
import com.retro99.reader.domain.positions.CopyPositionRow
import com.retro99.reader.domain.write.CopyWriteGuards
import org.koin.core.annotation.Factory

/**
 * Where "Use this position" would put each other copy (§1.3b). Exact and High targets are
 * ticked. Approximate targets, and targets the collapse guards (7 and 8) would block, are
 * unticked; the latter with a warning. Targets that can't be written are disabled.
 */
@Factory
class PreviewApplyPositionUseCase(
    private val translatePositionUseCase: TranslatePositionUseCase,
) {
    suspend operator fun invoke(
        source: CopyPositionRow,
        rows: List<CopyPositionRow>,
    ): List<ApplyPreview> {
        val sourcePosition = source.position ?: return emptyList()
        val copies = rows.map { row -> row.copy }
        return rows
            .filter { row -> row.copy.key != source.copy.key }
            .map { row ->
                val target = row.copy
                val translated = translatePositionUseCase(
                    source.copy,
                    sourcePosition,
                    target,
                    copies,
                )
                val disabledReason = when {
                    translated == null -> ApplyDisabledReason.NoTranslation
                    !CopyWriteGuards.isWritable(target, translated.position) ->
                        ApplyDisabledReason.NotSupported
                    else -> null
                }
                val targetProgression = translated?.position?.totalProgression
                val warning = when {
                    CopyWriteGuards.collapsesToStart(
                        sourcePosition.totalProgression,
                        targetProgression,
                    ) -> ApplyWarning.CollapseToStart
                    CopyWriteGuards.collapsesToEnd(
                        sourcePosition.totalProgression,
                        targetProgression,
                    ) -> ApplyWarning.CollapseToEnd
                    else -> null
                }
                val enabled = disabledReason == null
                ApplyPreview(
                    target = target,
                    translated = translated,
                    defaultChecked = enabled && warning == null &&
                        translated?.confidence?.isReliable == true,
                    enabled = enabled,
                    warning = warning.takeIf { _ -> enabled },
                    disabledReason = disabledReason,
                )
            }
    }
}
