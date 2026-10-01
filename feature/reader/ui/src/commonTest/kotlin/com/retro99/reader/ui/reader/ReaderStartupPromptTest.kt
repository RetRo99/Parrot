package com.retro99.reader.ui.reader

import com.retro99.books.domain.model.BookHome
import com.retro99.books.domain.model.links.CopyKey
import com.retro99.books.domain.model.links.CopySource
import com.retro99.books.domain.model.links.LinkedCopy
import com.retro99.reader.domain.linked.LinkedResumeOffer
import com.retro99.reader.domain.model.PositionDomainModel
import com.retro99.reader.domain.translate.TranslatedPosition
import com.retro99.reader.domain.translate.TranslationConfidence
import com.retro99.reader.domain.translate.TranslationStrategy
import com.retro99.reader.ui.model.PositionConflictUiModel
import com.retro99.reader.ui.model.PositionUiModel
import com.retro99.sync.domain.ProgressKind
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ReaderStartupPromptTest {

    private val conflict = PositionConflictUiModel(
        localPosition = position(0.1),
        remotePosition = position(0.2),
    )
    private var lookups = 0

    @Test
    fun `after book detail asked, the reader shows no second prompt`() = runTest {
        // When
        val prompt = readerStartupPrompt(
            linkedResumeResolved = true,
            positionConflict = conflict,
            findLinkedResume = { lookups++; offer() },
        )

        // Then
        assertEquals(ReaderStartupPrompt(), prompt)
        assertEquals(0, lookups)
    }

    @Test
    fun `opening from continue reading shows the linked prompt in the reader`() = runTest {
        // When
        val prompt = readerStartupPrompt(
            linkedResumeResolved = false,
            positionConflict = conflict,
            findLinkedResume = { offer() },
        )

        // Then
        assertEquals(offer(), prompt.linkedResumeOffer)
        assertNull(prompt.positionConflict)
    }

    @Test
    fun `without a linked offer the same-copy conflict is shown`() = runTest {
        // When
        val prompt = readerStartupPrompt(
            linkedResumeResolved = false,
            positionConflict = conflict,
            findLinkedResume = { null },
        )

        // Then
        assertEquals(conflict, prompt.positionConflict)
        assertNull(prompt.linkedResumeOffer)
    }

    private fun position(progression: Double) = PositionUiModel(
        createdAt = null,
        href = "c.xhtml",
        type = "",
        title = null,
        progression = progression,
        position = null,
        totalProgression = progression,
        chapterIndex = null,
        totalChapters = null,
    )

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
                    locatorHref = "c.xhtml",
                    locatorType = null,
                    locatorTitle = null,
                    locatorTarget = null,
                    audioTimestampMs = null,
                    chapterIndex = null,
                    progression = 0.5,
                    totalChapters = null,
                    totalDurationMs = null,
                    totalProgression = 0.5,
                    position = null,
                ),
                kind = ProgressKind.EBOOK,
                confidence = TranslationConfidence.High,
                strategy = TranslationStrategy.TextAnchor,
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
