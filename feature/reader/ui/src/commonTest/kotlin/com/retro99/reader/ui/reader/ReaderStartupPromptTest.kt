package com.retro99.reader.ui.reader

import com.retro99.books.domain.model.BookHome
import com.retro99.books.domain.model.links.CopyKey
import com.retro99.books.domain.model.links.CopySource
import com.retro99.books.domain.model.links.LinkedCopy
import com.retro99.reader.domain.linked.LinkedResumeOffer
import com.retro99.reader.domain.model.ConflictDecision
import com.retro99.reader.domain.model.PositionDomainModel
import com.retro99.reader.domain.model.ReadingProgressResult
import com.retro99.reader.domain.model.conflictDecision
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
        candidates = com.retro99.reader.domain.model.ReadingProgressResult.Conflict(
            offer().translated.position.copy(totalProgression = 0.1),
            offer().translated.position.copy(totalProgression = 0.2),
        ),
    )
    private var lookups = 0

    @Test
    fun `startup prompt matrix shows at most one prompt and skips lookup only after an answer`() = runTest {
        for (answered in listOf(false, true)) {
            for (hasConflict in listOf(false, true)) {
                for (hasLinkedOffer in listOf(false, true)) {
                    var calls = 0
                    val candidate = conflict.takeIf { hasConflict }
                    val linked = offer().takeIf { hasLinkedOffer }
                    val prompt = readerStartupPrompt(answered, candidate) { calls++; linked }
                    val context = "answered=$answered, conflict=$hasConflict, linked=$hasLinkedOffer"

                    assertEquals(if (answered) 0 else 1, calls, context)
                    assertEquals(if (answered) null else linked, prompt.linkedResumeOffer, context)
                    assertEquals(
                        if (answered || hasLinkedOffer) null else candidate,
                        prompt.positionConflict,
                        context,
                    )
                }
            }
        }
    }

    @Test
    fun `an answered opening never calls a failing linked lookup`() = runTest {
        val prompt = readerStartupPrompt(true, conflict) {
            error("An already answered opening must not fetch linked progress")
        }

        assertEquals(ReaderStartupPrompt(), prompt)
    }

    @Test
    fun `suppressing the handoff does not suppress a later independent opening`() = runTest {
        assertEquals(ReaderStartupPrompt(), readerStartupPrompt(true, conflict) { lookups++; null })

        val laterPrompt = readerStartupPrompt(false, conflict) { lookups++; null }

        assertEquals(conflict, laterPrompt.positionConflict)
        assertEquals(1, lookups)
    }

    @Test
    fun `after book detail asked the reader shows no second prompt`() = runTest {
        // When
        val prompt = readerStartupPrompt(
            linkedResumeResolved = true,
            conflict = conflict,
            findLinkedResume = { lookups++; offer() },
        )

        // Then
        assertEquals(ReaderStartupPrompt(), prompt)
        assertEquals(0, lookups)
    }

    @Test
    fun `a same-copy choice in book detail suppresses a stale conflict on reader open`() = runTest {
        val prompt = readerStartupPrompt(
            linkedResumeResolved = true,
            conflict = conflict,
            findLinkedResume = { lookups++; null },
        )

        assertNull(prompt.positionConflict)
        assertNull(prompt.linkedResumeOffer)
        assertEquals(0, lookups)
    }

    @Test
    fun `opening from continue reading shows the linked prompt in the reader`() = runTest {
        // When
        val prompt = readerStartupPrompt(
            linkedResumeResolved = false,
            conflict = conflict,
            findLinkedResume = { offer() },
        )

        // Then
        assertEquals(offer(), prompt.linkedResumeOffer)
        assertNull(prompt.positionConflict)
    }

    @Test
    fun `without a linked offer an ambiguous conflict is shown`() = runTest {
        // When
        val prompt = readerStartupPrompt(
            linkedResumeResolved = false,
            conflict = conflict,
            findLinkedResume = { null },
        )

        // Then
        assertEquals(conflict, prompt.positionConflict)
        assertNull(prompt.linkedResumeOffer)
        assertNull(prompt.positionSettleBar)
    }

    @Test
    fun `a settled keep becomes the quiet bar without a dialog`() = runTest {
        // When
        val prompt = readerStartupPrompt(
            linkedResumeResolved = false,
            conflict = settledConflict(
                localProgression = 0.86,
                remoteProgression = 0.78,
                localObserved = "2026-10-01T11:00:00Z",
                remoteObserved = "2026-10-01T08:00:00Z",
            ),
            remoteName = "Storyteller",
            findLinkedResume = { null },
        )

        // Then
        assertNull(prompt.positionConflict)
        assertEquals("Storyteller", prompt.positionSettleBar?.remoteName)
        assertEquals(false, prompt.positionSettleBar?.movedToOther)
        assertEquals(true, prompt.settleDecision is ConflictDecision.KeepThis)
    }

    @Test
    fun `a settled move becomes the mirrored quiet bar`() = runTest {
        // When
        val prompt = readerStartupPrompt(
            linkedResumeResolved = false,
            conflict = settledConflict(
                localProgression = 0.78,
                remoteProgression = 0.86,
                localObserved = "2026-10-01T08:00:00Z",
                remoteObserved = "2026-10-01T11:00:00Z",
            ),
            remoteName = "Storyteller",
            thisDeviceName = "This phone",
            findLinkedResume = { null },
        )

        // Then
        assertNull(prompt.positionConflict)
        assertEquals(true, prompt.positionSettleBar?.movedToOther)
        assertEquals(true, prompt.settleDecision is ConflictDecision.MoveToOther)
    }

    @Test
    fun `a difference under one percent says nothing at all`() = runTest {
        // When
        val prompt = readerStartupPrompt(
            linkedResumeResolved = false,
            conflict = settledConflict(
                localProgression = 0.80,
                remoteProgression = 0.805,
                localObserved = "2026-10-01T08:00:00Z",
                remoteObserved = "2026-10-01T11:00:00Z",
            ),
            findLinkedResume = { null },
        )

        // Then
        assertNull(prompt.positionConflict)
        assertNull(prompt.positionSettleBar)
    }

    @Test
    fun `a missing timestamp asks which position to keep`() = runTest {
        // When
        val prompt = readerStartupPrompt(
            linkedResumeResolved = false,
            conflict = settledConflict(
                localProgression = 0.78,
                remoteProgression = 0.86,
                localObserved = null,
                remoteObserved = "2026-10-01T11:00:00Z",
            ),
            findLinkedResume = { null },
        )

        // Then
        assertEquals(true, prompt.settleDecision is ConflictDecision.Ask)
        assertEquals(true, prompt.positionConflict != null)
        assertNull(prompt.positionSettleBar)
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

    /** A conflict whose candidates differ in percent and reading time (spec §1). */
    private fun settledConflict(
        localProgression: Double,
        remoteProgression: Double,
        localObserved: String?,
        remoteObserved: String?,
    ): PositionConflictUiModel {
        val local = domainPosition(localProgression, localObserved)
        val remote = domainPosition(remoteProgression, remoteObserved)
        return PositionConflictUiModel(
            localPosition = position(localProgression),
            remotePosition = position(remoteProgression),
            candidates = ReadingProgressResult.Conflict(
                localPosition = local,
                remotePosition = remote,
                decision = conflictDecision(local, remote),
            ),
        )
    }

    private fun domainPosition(
        progression: Double,
        observedAt: String?,
    ) = PositionDomainModel(
        bookUuid = "book",
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
        progression = progression,
        totalChapters = null,
        totalDurationMs = null,
        totalProgression = progression,
        position = null,
        observedAt = observedAt,
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
