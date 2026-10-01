package com.retro99.reader.domain.translate

import com.retro99.books.domain.model.links.CopyKey
import com.retro99.epub.api.EpubChapterText
import com.retro99.epub.api.ReadaloudTiming
import com.retro99.reader.domain.model.PositionDomainModel
import com.retro99.server.api.audio.trackPosition
import com.retro99.sync.domain.ProgressKind

/** What the translator knows about one copy of a linked book. */
data class CopyContent(
    val key: CopyKey,
    val serverId: String,
    val bookUuid: String,
    /** [ProgressKind.AUDIO] for an audiobook; ebooks and read-alouds are [ProgressKind.EBOOK]. */
    val kind: ProgressKind,
    val contentHash: String? = null,
    /** The copy's text, when its file is on this device. */
    val chapters: List<EpubChapterText>? = null,
    /** SMIL timing, when the copy is a read-aloud on this device. */
    val timing: ReadaloudTiming? = null,
    /** The length of the copy's audio, when known. */
    val audioDurationMs: Long? = null,
    val chapterCount: Int? = null,
    /** An audiobook's file lengths in playlist order, when known. */
    val trackDurationsMs: List<Long>? = null,
) {
    val isAudio: Boolean get() = kind == ProgressKind.AUDIO

    val durationMs: Long? get() = audioDurationMs ?: timing?.totalDurationMs

    val readaloud: ReadaloudContent?
        get() = if (chapters != null && timing != null) ReadaloudContent(chapters, timing) else null
}

/**
 * Translates a position in one copy into another copy (§1.5). Strategies are tried in order:
 * same file (Exact), text anchor (High), SMIL bridge (High), proportional (Approximate).
 * Audio maps to audio by time and text to text by anchor; a translation is never routed
 * through the other kind when a direct mapping exists (guard 9). Every mapped time and
 * progression is clamped (guard 10).
 */
class CopyPositionTranslator(
    private val matcher: TextAnchorMatcher = TextAnchorMatcher(),
    private val smilBridge: SmilBridge = SmilBridge(),
    private val proportional: ProportionalMapper = ProportionalMapper(),
) {

    fun translate(
        source: CopyContent,
        position: PositionDomainModel,
        target: CopyContent,
        others: List<CopyContent> = emptyList(),
    ): TranslatedPosition? {
        val request = Request(source, position, target, others)
        return sameFile(request)
            ?: textAnchor(request)
            ?: smil(request)
            ?: trackToText(request).takeIf { _ -> !target.isAudio }
            ?: proportional(request)
    }

    private class Request(
        val source: CopyContent,
        val position: PositionDomainModel,
        val target: CopyContent,
        val others: List<CopyContent>,
    ) {
        val hasText: Boolean = !source.isAudio && position.locatorHref != null

        /**
         * The audiobook player keeps time within the current track, with the track as the
         * chapter. Such a time isn't on the book's global timeline, so it never maps directly.
         * Positions saved with their book time ([PositionDomainModel.bookTimeMs]) don't need it.
         */
        val trackRelative: Boolean = source.isAudio && position.bookTimeMs == null &&
            position.chapterIndex != null && (position.totalChapters ?: 1) > 1

        /** The source's time on its book's global timeline, when it has one. */
        val audioMs: Long? = position.bookTimeMs?.takeIf { _ -> source.isAudio }
            ?: position.audioTimestampMs
                ?.takeIf { _ -> source.isAudio || source.timing != null }
                ?.takeIf { _ -> !trackRelative }

        /** How far through the current track a track-relative position is. */
        val trackFraction: Double? = if (trackRelative) {
            position.progression ?: position.audioTimestampMs?.let { ms ->
                position.totalDurationMs?.takeIf { length -> length > 0 }
                    ?.let { length -> ms.toDouble() / length }
            }
        } else {
            null
        }
        val sourceDurationMs: Long? = source.durationMs ?: position.totalDurationMs

        /** A read-aloud on this device that bridges text and audio, if any copy has one. */
        val bridge: CopyContent? = (listOf(target, source) + others)
            .firstOrNull { copy -> copy.readaloud != null }
    }

    private fun sameFile(request: Request): TranslatedPosition? {
        val source = request.source
        val target = request.target
        if (!request.hasText || target.isAudio) return null
        if (source.contentHash == null || source.contentHash != target.contentHash) return null
        val position = request.position.copy(
            bookUuid = target.bookUuid,
            serverId = target.serverId,
            timestamp = null,
            observedAt = null,
        )
        return TranslatedPosition(
            target = target.key,
            position = position,
            kind = target.kind,
            confidence = TranslationConfidence.Exact,
            strategy = TranslationStrategy.SameFile,
        )
    }

    private fun textAnchor(request: Request): TranslatedPosition? {
        if (!request.hasText || request.target.isAudio) return null
        val targetChapters = request.target.chapters ?: return null
        val anchor = sourceAnchor(request) ?: return null
        val point = matcher.match(anchor, targetChapters, request.position.totalProgression)
            ?: return null
        return textResult(
            target = request.target,
            chapters = targetChapters,
            point = point,
            confidence = TranslationConfidence.High,
            strategy = TranslationStrategy.TextAnchor,
        )
    }

    private fun sourceAnchor(request: Request) = request.position.textAnchor
        ?: request.source.chapters?.let { chapters ->
            chapters.pointOf(
                href = request.position.locatorHref,
                progression = request.position.progression,
                cssSelector = request.position.cssSelector,
            )?.let { point -> chapters.anchorAt(point) }
        }

    private fun smil(request: Request): TranslatedPosition? {
        val audioMs = request.audioMs
        return when {
            // Audio to audio: time to time, never through text (guard 9).
            audioMs != null && request.target.isAudio -> audioToAudio(request, audioMs)
            audioMs != null && request.source.isAudio -> audioToText(request, audioMs)
            request.hasText && request.target.isAudio -> textToAudio(request)
            else -> null
        }
    }

    /**
     * P6b fallback: an audiobook position kept per track, against a read-aloud with as many
     * audio files. The same track and the same fraction through it: Approximate.
     */
    private fun trackToText(request: Request): TranslatedPosition? {
        val position = request.position
        val chapterIndex = position.chapterIndex ?: return null
        val fraction = request.trackFraction ?: return null
        val bridge = request.bridge ?: return null
        val readaloud = requireNotNull(bridge.readaloud)
        if (readaloud.timing.audioFileOffsetsMs.size != position.totalChapters) return null
        val globalMs = smilBridge.trackToGlobalMs(readaloud.timing, chapterIndex, fraction)
            ?: return null
        val point = smilBridge.audioToText(readaloud, globalMs) ?: return null
        if (bridge.key == request.target.key) {
            return textResult(
                target = request.target,
                chapters = readaloud.chapters,
                point = point,
                confidence = TranslationConfidence.Approximate,
                strategy = TranslationStrategy.Proportional,
                audioMs = globalMs,
            )
        }
        val targetChapters = request.target.chapters ?: return null
        val anchor = readaloud.chapters.anchorAt(point) ?: return null
        val match = matcher.match(
            anchor,
            targetChapters,
            readaloud.chapters.totalProgressionAt(point),
        ) ?: return null
        return textResult(
            target = request.target,
            chapters = targetChapters,
            point = match,
            confidence = TranslationConfidence.Approximate,
            strategy = TranslationStrategy.Proportional,
        )
    }

    private fun audioToAudio(request: Request, audioMs: Long): TranslatedPosition? {
        val targetDuration = request.target.durationMs
        val mapped = smilBridge.mapTime(audioMs, request.sourceDurationMs, targetDuration)
            ?: return null
        return audioResult(
            request.target,
            mapped,
            TranslationConfidence.High,
            TranslationStrategy.SmilBridge,
        )
    }

    private fun audioToText(request: Request, audioMs: Long): TranslatedPosition? {
        val bridge = request.bridge ?: return null
        val readaloud = requireNotNull(bridge.readaloud)
        val bridgeMs = if (bridge.key == request.source.key) {
            audioMs.coerceIn(0L, readaloud.timing.totalDurationMs)
        } else {
            smilBridge.mapTime(audioMs, request.sourceDurationMs, readaloud.timing.totalDurationMs)
                ?: return null
        }
        val point = smilBridge.audioToText(readaloud, bridgeMs) ?: return null
        if (bridge.key == request.target.key) {
            return textResult(
                target = request.target,
                chapters = readaloud.chapters,
                point = point,
                confidence = TranslationConfidence.High,
                strategy = TranslationStrategy.SmilBridge,
                audioMs = bridgeMs,
            )
        }
        val targetChapters = request.target.chapters ?: return null
        val anchor = readaloud.chapters.anchorAt(point) ?: return null
        val match = matcher.match(
            anchor,
            targetChapters,
            readaloud.chapters.totalProgressionAt(point),
        ) ?: return null
        return textResult(
            target = request.target,
            chapters = targetChapters,
            point = match,
            confidence = TranslationConfidence.High,
            strategy = TranslationStrategy.SmilBridge,
        )
    }

    private fun textToAudio(request: Request): TranslatedPosition? {
        val bridge = request.bridge ?: return null
        val readaloud = requireNotNull(bridge.readaloud)
        val point = if (bridge.key == request.source.key) {
            readaloud.chapters.pointOf(
                href = request.position.locatorHref,
                progression = request.position.progression,
                cssSelector = request.position.cssSelector,
            )
        } else {
            sourceAnchor(request)?.let { anchor ->
                matcher.match(anchor, readaloud.chapters, request.position.totalProgression)
            }
        } ?: return null
        val bridgeMs = smilBridge.textToAudioMs(readaloud, point) ?: return null
        val mapped = smilBridge.mapTime(
            bridgeMs,
            readaloud.timing.totalDurationMs,
            request.target.durationMs,
        ) ?: return null
        return audioResult(
            request.target,
            mapped,
            TranslationConfidence.High,
            TranslationStrategy.SmilBridge,
        )
    }

    private fun proportional(request: Request): TranslatedPosition? {
        val position = request.position
        val trackFraction = request.trackFraction
        val chapters = position.totalChapters
        val totalProgression = if (trackFraction != null && chapters != null) {
            // A track-relative position: whole tracks before it, plus the way through it.
            ((requireNotNull(position.chapterIndex) + trackFraction) / chapters).coerceIn(0.0, 1.0)
        } else {
            proportional.sourceProgression(
                totalProgression = position.totalProgression,
                audioMs = request.audioMs ?: position.audioTimestampMs,
                durationMs = request.sourceDurationMs,
            )
        } ?: return null
        val target = request.target
        if (target.isAudio) {
            val duration = target.durationMs
            val ms = duration?.let { length ->
                (totalProgression * length).toLong().coerceIn(0L, length)
            }
            return TranslatedPosition(
                target = target.key,
                position = audiobookPlace(target, ms).copy(
                    totalDurationMs = duration,
                    progression = totalProgression,
                    totalProgression = totalProgression,
                ),
                kind = target.kind,
                confidence = TranslationConfidence.Approximate,
                strategy = TranslationStrategy.Proportional,
            )
        }
        val sourceChapters = request.source.chapters
        val place = proportional.map(
            totalProgression = totalProgression,
            sourceChapterIndex = position.chapterIndex
                ?: sourceChapters?.indexOfHref(position.locatorHref)
                    ?.takeIf { index -> index >= 0 },
            sourceChapterProgression = position.progression.takeIf { _ -> request.hasText },
            sourceChapterCount = sourceChapters?.size
                ?: position.totalChapters
                ?: request.source.chapterCount,
            targetChapters = target.chapters,
            targetChapterCount = target.chapterCount,
        )
        val chapter = place.chapterIndex?.let { index -> target.chapters?.getOrNull(index) }
        return TranslatedPosition(
            target = target.key,
            position = emptyPosition(target).copy(
                locatorHref = chapter?.href,
                locatorType = chapter?.let { _ -> XHTML_TYPE },
                locatorTitle = chapter?.title,
                chapterIndex = place.chapterIndex,
                totalChapters = target.chapters?.size ?: target.chapterCount,
                progression = place.progression,
                totalProgression = place.totalProgression,
            ),
            kind = target.kind,
            confidence = TranslationConfidence.Approximate,
            strategy = TranslationStrategy.Proportional,
        )
    }

    private fun textResult(
        target: CopyContent,
        chapters: List<EpubChapterText>,
        point: TextPoint,
        confidence: TranslationConfidence,
        strategy: TranslationStrategy,
        audioMs: Long? = null,
    ): TranslatedPosition {
        val chapter = chapters[point.chapterIndex]
        // A read-aloud target also gets the audio time, so its narration resumes there too.
        val targetAudioMs = audioMs ?: target.readaloud?.let { readaloud ->
            smilBridge.textToAudioMs(readaloud, point)
        }
        return TranslatedPosition(
            target = target.key,
            position = emptyPosition(target).copy(
                locatorHref = chapter.href,
                locatorType = XHTML_TYPE,
                locatorTitle = chapter.title,
                chapterIndex = point.chapterIndex,
                totalChapters = chapters.size,
                progression = chapters.progressionAt(point),
                totalProgression = chapters.totalProgressionAt(point),
                cssSelector = chapters.cssSelectorAt(point),
                audioTimestampMs = targetAudioMs,
                totalDurationMs = target.durationMs.takeIf { _ -> targetAudioMs != null },
                textAnchor = chapters.anchorAt(point),
            ),
            kind = target.kind,
            confidence = confidence,
            strategy = strategy,
        )
    }

    private fun audioResult(
        target: CopyContent,
        audioMs: Long,
        confidence: TranslationConfidence,
        strategy: TranslationStrategy,
    ): TranslatedPosition {
        val duration = target.durationMs
        val progression = duration?.takeIf { length -> length > 0 }
            ?.let { length -> (audioMs.toDouble() / length).coerceIn(0.0, 1.0) }
        val bookTimeMs = duration?.let { length -> audioMs.coerceIn(0L, length) }
            ?: audioMs.coerceAtLeast(0L)
        return TranslatedPosition(
            target = target.key,
            position = audiobookPlace(target, bookTimeMs).copy(
                totalDurationMs = duration,
                progression = progression,
                totalProgression = progression,
            ),
            kind = target.kind,
            confidence = confidence,
            strategy = strategy,
        )
    }

    /**
     * An audiobook target at [bookTimeMs] from the start of the book: the book time, plus the
     * file and the offset in it when the target's file lengths are known (null otherwise, as
     * the player and Audiobookshelf resolve the book time themselves).
     */
    private fun audiobookPlace(target: CopyContent, bookTimeMs: Long?): PositionDomainModel {
        val durations = target.trackDurationsMs?.takeIf { lengths -> lengths.isNotEmpty() }
        val place = if (bookTimeMs != null && durations != null) {
            trackPosition(durations, bookTimeMs)
        } else {
            null
        }
        return emptyPosition(target).copy(
            bookTimeMs = bookTimeMs,
            chapterIndex = place?.first,
            audioTimestampMs = place?.second,
            totalChapters = durations?.size,
        )
    }

    private fun emptyPosition(target: CopyContent) = PositionDomainModel(
        bookUuid = target.bookUuid,
        serverId = target.serverId,
        timestamp = null,
        createdAt = null,
        updatedAt = null,
        locatorHref = null,
        locatorType = null,
        locatorTitle = null,
        locatorTarget = null,
        audioTimestampMs = null,
        chapterIndex = null,
        progression = null,
        totalChapters = null,
        totalDurationMs = null,
        totalProgression = null,
        position = null,
    )

    private companion object {
        const val XHTML_TYPE = "application/xhtml+xml"
    }
}
