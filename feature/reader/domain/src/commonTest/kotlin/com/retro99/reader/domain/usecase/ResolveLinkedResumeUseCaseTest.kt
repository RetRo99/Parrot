package com.retro99.reader.domain.usecase

import com.retro99.books.domain.model.links.CopySource
import com.retro99.reader.domain.fakes.FakeDismissals
import com.retro99.reader.domain.fakes.FakeReaderRepository
import com.retro99.reader.domain.fakes.FakeRepositoryProvider
import com.retro99.reader.domain.fakes.StoredPosition
import com.retro99.reader.domain.linked.LinkedResumeOffer
import com.retro99.reader.domain.model.toPositionDomainModel
import com.retro99.reader.domain.translate.TranslatedPosition
import com.retro99.reader.domain.translate.TranslationConfidence
import com.retro99.reader.domain.translate.TranslationStrategy
import com.retro99.reader.domain.translate.linkedCopy
import com.retro99.server.api.InstallationDeviceIdentity
import com.retro99.server.api.PositionOrigin
import com.retro99.server.api.SourceDeviceIdentity
import com.retro99.sync.domain.ProgressKind
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ResolveLinkedResumeUseCaseTest {
    private val repository = FakeReaderRepository("local")
    private val dismissals = FakeDismissals()
    private val resolver = ResolveLinkedResumeUseCase(
        SaveReadingProgressUseCase(
            FakeRepositoryProvider(listOf(repository)),
            InstallationDeviceIdentity { SourceDeviceIdentity("device", "Phone") },
        ), dismissals,
    )
    private val target = linkedCopy(CopySource.Library, "target", serverId = "local")
    private val source = linkedCopy(CopySource.Storyteller, "source", serverId = "storyteller")
    private val offer = LinkedResumeOffer(
        target, source, ProgressKind.EBOOK, "2026-10-01T10:00:00Z",
        TranslatedPosition(
            target.key, StoredPosition("target", totalProgression = 0.7).toPositionDomainModel("local"),
            ProgressKind.EBOOK, TranslationConfidence.Approximate, TranslationStrategy.Proportional,
        ),
    )

    @Test
    fun `Continue returns the persistence failure and does not record a dismissal`() = runTest {
        repository.saveFails = true

        assertTrue(resolver.continueFrom(offer).isErr)
        assertTrue(repository.syncedSaves.isEmpty())
        assertTrue(dismissals.entries.isEmpty())
    }

    @Test
    fun `Continue saves the target as fresh user reading without modifying the source`() = runTest {
        assertTrue(resolver.continueFrom(offer).isOk)

        val saved = repository.syncedSaves.single()
        assertEquals("target", saved.bookUuid)
        assertEquals(0.7, saved.totalProgression)
        assertEquals(PositionOrigin.User, saved.origin)
        assertTrue(saved.observedAt != null)
        assertTrue("source" !in repository.local)
        assertTrue(dismissals.entries.isEmpty())
    }

    @Test
    fun `Stay records the exact source reading without saving a position`() = runTest {
        resolver.stayHere(offer)

        assertEquals(listOf(offer.dismissalEntry), dismissals.entries)
        assertTrue(repository.syncedSaves.isEmpty())
    }
}
