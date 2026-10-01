package com.retro99.books.ui.detail

import com.retro99.books.domain.model.BookHome
import com.retro99.books.domain.model.links.CopyKey
import com.retro99.books.domain.model.links.CopySource
import com.retro99.books.domain.model.links.LinkedCopy
import com.retro99.reader.domain.linked.LinkedResumeOffer
import com.retro99.reader.domain.model.PositionDomainModel
import com.retro99.reader.domain.translate.TranslatedPosition
import com.retro99.reader.domain.translate.TranslationConfidence
import com.retro99.reader.domain.translate.TranslationStrategy
import com.retro99.sync.domain.ProgressKind
import kotlin.test.Test
import kotlin.test.assertEquals

class BookDetailOpenPromptTest {

    @Test
    fun `a linked offer is asked instead of the same-copy conflict`() {
        // When
        val prompt = bookDetailOpenPrompt(offer(), hasSameCopyConflict = true)

        // Then
        assertEquals(BookDetailOpenPrompt.LinkedResume, prompt)
    }

    @Test
    fun `without a linked offer the same-copy conflict is asked`() {
        // When
        val prompt = bookDetailOpenPrompt(null, hasSameCopyConflict = true)

        // Then
        assertEquals(BookDetailOpenPrompt.SameCopyConflict, prompt)
    }

    @Test
    fun `nothing to ask opens the reader`() {
        // When
        val prompt = bookDetailOpenPrompt(null, hasSameCopyConflict = false)

        // Then
        assertEquals(BookDetailOpenPrompt.None, prompt)
    }

    private fun offer(): LinkedResumeOffer {
        val target = copy(CopySource.Library, "lib")
        return LinkedResumeOffer(
            target = target,
            source = copy(CopySource.Storyteller, "st"),
            sourceKind = ProgressKind.EBOOK,
            observedAt = "2026-10-01T10:00:00Z",
            translated = TranslatedPosition(
                target = target.key,
                position = PositionDomainModel(
                    bookUuid = "lib",
                    serverId = "local",
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
                    totalProgression = 0.5,
                    position = null,
                ),
                kind = ProgressKind.EBOOK,
                confidence = TranslationConfidence.Approximate,
                strategy = TranslationStrategy.Proportional,
            ),
        )
    }

    private fun copy(source: CopySource, id: String) = LinkedCopy(
        key = CopyKey(source, id),
        serverId = source.prefix,
        uuid = id,
        title = "Book",
        home = BookHome.ThisDevice,
        hasEbook = true,
        hasAudiobook = false,
        hasReadaloud = false,
        isDownloaded = true,
    )
}
