package com.retro99.server.implementation.library

import com.retro99.server.api.library.LibraryAdapterId
import com.retro99.server.api.library.LibraryProfileId
import com.retro99.server.api.library.LibrarySourceAdapter
import com.retro99.server.api.library.LibrarySourceRecord
import com.retro99.server.api.library.NativeBookId
import com.retro99.server.api.library.SourceAccountIdentity
import com.retro99.server.api.library.SourceBookKey
import com.retro99.server.api.library.SourceBookMetadata
import com.retro99.server.api.library.SourceBookRef
import com.retro99.server.api.library.SourceBookSnapshot
import com.retro99.server.api.library.SourcePresence
import com.retro99.server.api.library.SourceSnapshotStatus
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.time.Instant

class DefaultLibrarySourceAdapterRegistryTest {
    @Test
    fun arbitraryAdapterNormalizesWithoutServerTypeRegistration() {
        val adapter = FakeAdapter(LibraryAdapterId("integration-not-known-to-the-app"))
        val registry = DefaultLibrarySourceAdapterRegistry(listOf(adapter))

        val snapshot = registry.normalize(adapter.adapterId, FakeRecord("native-42"))

        assertEquals(adapter.adapterId, snapshot.source.key.adapterId)
        assertEquals(NativeBookId("native-42"), snapshot.source.key.nativeBookId)
        assertEquals(emptyList(), snapshot.identityEvidence)
    }

    @Test
    fun duplicateAdapterIdsAreRejected() {
        val id = LibraryAdapterId("duplicate")

        assertFailsWith<IllegalArgumentException> {
            DefaultLibrarySourceAdapterRegistry(listOf(FakeAdapter(id), FakeAdapter(id)))
        }
    }

    @Test
    fun missingAdapterDoesNotFallbackToAConcreteServerType() {
        val registry = DefaultLibrarySourceAdapterRegistry(emptyList())

        assertFailsWith<IllegalArgumentException> {
            registry.normalize(LibraryAdapterId("missing"), FakeRecord("book"))
        }
    }
}

private data class FakeRecord(val nativeId: String) : LibrarySourceRecord

private class FakeAdapter(
    override val adapterId: LibraryAdapterId,
) : LibrarySourceAdapter {
    override fun normalize(record: LibrarySourceRecord): SourceBookSnapshot {
        val fake = record as FakeRecord
        return SourceBookSnapshot(
            source = SourceBookRef(
                key = SourceBookKey(
                    profileId = LibraryProfileId("profile"),
                    adapterId = adapterId,
                    accountIdentity = SourceAccountIdentity.Portable("fake-backend", "fake-account"),
                    nativeBookId = NativeBookId(fake.nativeId),
                ),
                connectionId = null,
            ),
            metadata = SourceBookMetadata(title = "Unknown integration book"),
            resources = emptyList(),
            identityEvidence = emptyList(),
            status = SourceSnapshotStatus(
                observedAt = Instant.parse("2026-09-24T00:00:00Z"),
                presence = SourcePresence.Present,
                isAuthoritative = true,
            ),
        )
    }
}
