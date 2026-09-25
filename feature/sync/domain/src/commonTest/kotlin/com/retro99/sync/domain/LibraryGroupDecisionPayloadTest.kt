package com.retro99.sync.domain

import com.retro99.server.api.library.LibraryProfileId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs

class LibraryGroupDecisionPayloadTest {
    @Test
    fun portableDecisionRoundTripsWithoutLocalIdentityFields() {
        // Given
        val payload = mergePayload()
        val localJson = LibraryGroupDecisionCodec.encodeLocal(payload)

        // When
        val readiness = LibraryGroupDecisionCodec.encodeWireOrDefer(localJson)

        // Then
        val ready = assertIs<LibraryGroupSyncReadiness.Ready>(readiness)
        assertFalse(ready.payload.contains("local_profile_id"))
        assertFalse(ready.payload.contains("profile_id"))
        assertFalse(ready.payload.contains("connection_id"))
        assertFalse(ready.payload.contains("url"))

        val wire = LibraryGroupDecisionCodec.decodeWire(ready.payload)
        assertEquals(payload.decisionId, wire.decisionId)
        assertEquals(payload.targetGroupId, wire.targetGroupId)
        assertEquals("backend-a", wire.members.first().backendId)
        assertEquals("account-a", wire.members.first().accountId)

        val localOnOtherInstall = LibraryGroupDecisionCodec.toLocal(
            profileId = LibraryProfileId("profile-on-other-install"),
            payload = wire,
        )
        assertEquals("profile-on-other-install", localOnOtherInstall.localProfileId)
        assertEquals(
            "profile-on-other-install",
            localOnOtherInstall.members.first().profileId,
        )
    }

    @Test
    fun unresolvedMembershipDefersDecisionWithoutProducingWirePayload() {
        // Given
        val payload = mergePayload().copy(
            members = listOf(
                portableMember("native-a"),
                LibraryGroupMemberRef(
                    profileId = "profile-a",
                    adapterId = "storyteller",
                    identityKind = LibraryGroupMemberRef.UNRESOLVED,
                    connectionId = "installation-local-connection",
                    nativeBookId = "native-b",
                ),
            ),
        )

        // When
        val readiness = LibraryGroupDecisionCodec.encodeWireOrDefer(
            LibraryGroupDecisionCodec.encodeLocal(payload),
        )

        // Then
        assertEquals(LibraryGroupSyncReadiness.Deferred(1), readiness)
    }

    @Test
    fun unsupportedPayloadVersionIsNotSent() {
        // Given
        val payload = mergePayload().copy(version = 2)

        // When
        val readiness = LibraryGroupDecisionCodec.encodeWireOrDefer(
            LibraryGroupDecisionCodec.encodeLocal(payload),
        )

        // Then
        assertEquals(LibraryGroupSyncReadiness.UnsupportedVersion(2), readiness)
    }

    private fun mergePayload() = LibraryGroupDecisionPayload(
        decisionId = "decision-a",
        decisionType = LibraryGroupDecisionPayload.MERGE,
        targetGroupId = "group-a",
        members = listOf(portableMember("native-a"), portableMember("native-b")),
        createdAt = "2026-09-24T10:00:00Z",
        localProfileId = "profile-a",
    )

    private fun portableMember(nativeBookId: String) = LibraryGroupMemberRef(
        profileId = "profile-a",
        adapterId = "adapter-a",
        identityKind = LibraryGroupMemberRef.PORTABLE,
        backendId = "backend-a",
        accountId = "account-a",
        nativeBookId = nativeBookId,
    )
}
