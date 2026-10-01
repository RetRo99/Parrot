package com.retro99.reader.domain.write

import com.retro99.books.domain.model.links.CopySource
import com.retro99.books.domain.model.links.LinkedCopy
import com.retro99.reader.domain.model.PositionDomainModel
import com.retro99.reader.domain.translate.progressKind
import com.retro99.sync.domain.ProgressKind

/** The write guards of §1.4 that don't need storage, shared by the panel and propagation. */
object CopyWriteGuards {

    private const val EDGE = 0.005
    private const val END_GAP = 0.05
    private const val MIN_PROGRESSION_DELTA = 0.01
    private const val MIN_CHARACTER_DELTA = 2_000

    /**
     * Guard 11: each server gets the locator format its own apps read. Audiobookshelf takes
     * audio positions with a time, and ebook positions with a chapter or a progress: its
     * transport writes the CFI or JSON locator its readers stored (B4), never a bare href.
     */
    fun isWritable(target: LinkedCopy, position: PositionDomainModel?): Boolean {
        if (target.key.source != CopySource.Audiobookshelf) return true
        if (position == null) return false
        return when (target.progressKind) {
            ProgressKind.AUDIO -> position.bookTimeMs != null || position.audioTimestampMs != null
            ProgressKind.EBOOK -> position.locatorHref != null || position.totalProgression != null
        }
    }

    /** Guard 7: a match that fell back to the start would wipe real progress. */
    fun collapsesToStart(sourceProgression: Double?, targetProgression: Double?): Boolean {
        if (sourceProgression == null || targetProgression == null) return false
        return targetProgression <= EDGE && sourceProgression > EDGE
    }

    /** Guard 8: a match in the back matter would mark the book finished. */
    fun collapsesToEnd(sourceProgression: Double?, targetProgression: Double?): Boolean {
        if (sourceProgression == null || targetProgression == null) return false
        return targetProgression >= 1.0 - EDGE && targetProgression - sourceProgression > END_GAP
    }

    /**
     * Guard 4: worth writing when it moves the target at least 1% of the book or, for ebooks,
     * at least 2000 characters (about 400 words).
     */
    fun exceedsThreshold(
        kind: ProgressKind,
        currentProgression: Double?,
        newProgression: Double?,
        movedCharacters: Int?,
    ): Boolean {
        if (currentProgression == null || newProgression == null) return true
        if (kotlin.math.abs(newProgression - currentProgression) >= MIN_PROGRESSION_DELTA) {
            return true
        }
        return kind == ProgressKind.EBOOK &&
            movedCharacters != null &&
            movedCharacters >= MIN_CHARACTER_DELTA
    }
}
