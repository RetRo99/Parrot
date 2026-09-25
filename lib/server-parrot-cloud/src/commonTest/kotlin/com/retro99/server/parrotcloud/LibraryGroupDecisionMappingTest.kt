package com.retro99.server.parrotcloud

import com.retro99.server.api.library.LibraryAdapterId
import com.retro99.server.api.library.LibraryProfileId
import com.retro99.server.api.library.NativeBookId
import com.retro99.server.api.library.SourceAccountIdentity
import com.retro99.server.api.library.SourceBookKey
import com.retro99.sync.domain.LibraryGroupDecisionCodec
import com.retro99.sync.domain.LibraryGroupDecisionPayload
import com.retro99.sync.domain.LibraryGroupMemberRef
import com.retro99.sync.domain.LibraryGroupSyncReadiness
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class LibraryGroupDecisionMappingTest {
    @Test
    fun feedDecisionMapsPortableMediaPreferencesToSourceKeys() {
        // Given
        val localProfileId = LibraryProfileId("origin-profile")
        val decision = LibraryGroupDecisionPayload(
            decisionId = "decision-media-preferences",
            decisionType = LibraryGroupDecisionPayload.PREFERENCES,
            targetGroupId = "group-target",
            members = listOf(
                portableMember("ebook-source"),
                portableMember("audio-source"),
            ),
            preferredMediaMembers = mapOf(
                "ebook" to portableMember("ebook-source"),
                "audiobook" to portableMember("audio-source"),
            ),
            createdAt = "2026-09-25T10:00:00Z",
            localProfileId = localProfileId.value,
        )
        val wireResult = LibraryGroupDecisionCodec.encodeWireOrDefer(
            LibraryGroupDecisionCodec.encodeLocal(decision),
        )
        val wire = LibraryGroupDecisionCodec.decodeWire(
            assertIs<LibraryGroupSyncReadiness.Ready>(wireResult).payload,
        )
        val receivingProfileId = LibraryProfileId("receiving-profile")
        val local = LibraryGroupDecisionCodec.toLocal(receivingProfileId, wire)

        // When
        val synchronizedDecision = local.toSynchronizedDecision(
            profileId = receivingProfileId,
            revision = 9L,
            encodedPayload = "canonical-wire-payload",
        )

        // Then
        assertEquals(
            mapOf(
                "ebook" to portableSourceKey(receivingProfileId, "ebook-source"),
                "audiobook" to portableSourceKey(receivingProfileId, "audio-source"),
            ),
            synchronizedDecision.preferredMediaSourceKeys,
        )
        assertEquals("canonical-wire-payload", synchronizedDecision.payload)
    }

    private fun portableMember(nativeBookId: String) = LibraryGroupMemberRef(
        profileId = "origin-profile",
        adapterId = "audiobookshelf",
        identityKind = LibraryGroupMemberRef.PORTABLE,
        backendId = "abs-backend",
        accountId = "abs-account",
        nativeBookId = nativeBookId,
    )

    private fun portableSourceKey(
        profileId: LibraryProfileId,
        nativeBookId: String,
    ) = SourceBookKey(
        profileId = profileId,
        adapterId = LibraryAdapterId("audiobookshelf"),
        accountIdentity = SourceAccountIdentity.Portable(
            backendId = "abs-backend",
            accountId = "abs-account",
        ),
        nativeBookId = NativeBookId(nativeBookId),
    )
}
