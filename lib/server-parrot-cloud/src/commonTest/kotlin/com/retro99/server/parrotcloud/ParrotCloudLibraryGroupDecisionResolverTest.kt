package com.retro99.server.parrotcloud

import com.retro99.database.api.library.LibrarySourceIdentityPromotionDatabase
import com.retro99.database.api.library.LibrarySourceIdentityPromotionResult
import com.retro99.database.api.library.LibrarySourceIdentityPromotionStatus
import com.retro99.database.api.sync.SyncOutboxEntry
import com.retro99.server.api.library.LibraryAdapterId
import com.retro99.server.api.library.LibraryProfileId
import com.retro99.server.api.library.LibrarySourceIdentitySyncAuthorization
import com.retro99.server.api.library.LocalContentIdentity
import com.retro99.server.api.library.NativeBookId
import com.retro99.server.api.library.SourceAccountIdentity
import com.retro99.server.api.library.SourceBookKey
import com.retro99.server.api.library.SourceBookRef
import com.retro99.server.api.library.SourceConnectionId
import com.retro99.sync.domain.LibraryGroupDecisionCodec
import com.retro99.sync.domain.LibraryGroupDecisionPayload
import com.retro99.sync.domain.LibraryGroupMemberRef
import com.retro99.sync.domain.LibraryGroupSyncReadiness
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class ParrotCloudLibraryGroupDecisionResolverTest {
    private val profileId = LibraryProfileId("profile-a")
    private val connectionId = SourceConnectionId(PARROT_CLOUD_SERVER_ID)
    private val authorizeAllSources = LibrarySourceIdentitySyncAuthorization {
            _, _, _, _ -> true
    }

    @Test
    fun mixedLocalAndAudiobookshelfDecisionOnlyRebindsPromotedCloudMember() = runTest {
        // Given
        val cloudSource = unresolvedSource("parrot-cloud", connectionId.value, "cloud-book")
        val localSource = unresolvedSource("local", "local-installation", "local-book")
        val audiobookshelfSource = unresolvedSource(
            "audiobookshelf",
            "abs-connection",
            "abs-book",
        )
        val portableCloudSource = portableCloudSource("cloud-user-a", "cloud-book")
        val promotionDatabase = FakePromotionDatabase().apply {
            addAlias(cloudSource.key, portableCloudSource.key)
        }
        val originalPayload = decisionPayload(
            listOf(cloudSource, localSource, audiobookshelfSource),
        )
        val storedOutboxPayload = LibraryGroupDecisionCodec.encodeLocal(originalPayload)

        // When
        val wireAttemptPayload = resolvePromotedSourceMembersForWire(
            localPayload = storedOutboxPayload,
            expectedLocalProfileId = profileId.value,
            linkedConnectionId = connectionId,
            cloudUserId = "cloud-user-a",
            promotionDatabase = promotionDatabase,
        )
        val wireAttempt = LibraryGroupDecisionCodec.decodeLocal(wireAttemptPayload)

        // Then
        assertEquals(LibraryGroupMemberRef.from(portableCloudSource.key), wireAttempt.members[0])
        assertEquals(LibraryGroupMemberRef.from(localSource.key), wireAttempt.members[1])
        assertEquals(
            LibraryGroupMemberRef.from(audiobookshelfSource.key),
            wireAttempt.members[2],
        )
        assertEquals(
            LibraryGroupSyncReadiness.Deferred(2),
            LibraryGroupDecisionCodec.encodeWireOrDefer(wireAttemptPayload),
        )
        assertEquals(storedOutboxPayload, LibraryGroupDecisionCodec.encodeLocal(originalPayload))
    }

    @Test
    fun pendingCloudDecisionBecomesSendableAfterItsProfileIsLinked() = runTest {
        // Given
        val cloudSource = unresolvedSource("parrot-cloud", connectionId.value, "cloud-book")
        val portableSource = portableCloudSource("cloud-user-a", "cloud-book")
        val promotionDatabase = FakePromotionDatabase()
        val audiobookshelfSource = portableAudiobookshelfSource("abs-account", "abs-book")
        val originalPayload = LibraryGroupDecisionCodec.encodeLocal(
            decisionPayload(listOf(cloudSource, audiobookshelfSource)),
        )

        // When: the local decision was created before the account link existed.
        val whileUnlinked = resolvePromotedSourceMembersForWire(
            localPayload = originalPayload,
            expectedLocalProfileId = profileId.value,
            linkedConnectionId = connectionId,
            cloudUserId = "cloud-user-a",
            promotionDatabase = promotionDatabase,
        )

        // Then: it remains safely deferred until the alias is persisted.
        assertEquals(
            LibraryGroupSyncReadiness.Deferred(1),
            LibraryGroupDecisionCodec.encodeWireOrDefer(whileUnlinked),
        )
        assertEquals(originalPayload, whileUnlinked)

        // When: the same local profile links to the verified Cloud account.
        promotionDatabase.addAlias(cloudSource.key, portableSource.key)
        val afterLink = resolvePromotedSourceMembersForWire(
            localPayload = originalPayload,
            expectedLocalProfileId = profileId.value,
            linkedConnectionId = connectionId,
            cloudUserId = "cloud-user-a",
            promotionDatabase = promotionDatabase,
        )

        // Then: the in-memory wire attempt is portable; stored outbox bytes stay unchanged.
        val readiness = LibraryGroupDecisionCodec.encodeWireOrDefer(afterLink)
        assertIs<LibraryGroupSyncReadiness.Ready>(readiness)
        assertEquals(originalPayload, LibraryGroupDecisionCodec.encodeLocal(
            decisionPayload(listOf(cloudSource, audiobookshelfSource)),
        ))
        assertEquals(
            LibraryGroupMemberRef.from(portableSource.key),
            LibraryGroupDecisionCodec.decodeLocal(afterLink).members.first(),
        )
    }

    @Test
    fun linkingCloudAccountPromotesSourceThenMakesStoredGroupDecisionSendable() = runTest {
        val cloudSource = unresolvedSource("parrot-cloud", connectionId.value, "cloud-book")
        val portableCloud = portableCloudSource("cloud-user-a", "cloud-book")
        val unresolvedLocal = unresolvedSource(
            adapterId = LocalContentIdentity.ADAPTER_ID,
            connectionId = "local-installation",
            nativeBookId = "imported-book-uuid",
        )
        val portableLocal = portableLocalContentSource(VALID_HASH)
        val audiobookshelf = portableAudiobookshelfSource("abs-account", "abs-book")
        val storedPayload = LibraryGroupDecisionCodec.encodeLocal(
            decisionPayload(listOf(unresolvedLocal, cloudSource, audiobookshelf)),
        )
        val entry = outboxEntry(
            entityType = SyncOutboxEntry.ENTITY_TYPE_LIBRARY_GROUP_DECISION,
            payload = storedPayload,
        )
        val promotionDatabase = FakePromotionDatabase().apply {
            addAlias(unresolvedLocal.key, portableLocal.key)
            scheduleCloudPromotion(cloudSource.key, portableCloud)
        }

        val whileUnlinked = prepareParrotCloudLibraryMutationsForWire(
            entries = listOf(entry),
            expectedLocalProfileId = profileId.value,
            linkedConnectionId = connectionId,
            cloudUserId = "cloud-user-a",
            promotionDatabase = promotionDatabase,
            identitySyncAuthorization = authorizeAllSources,
        )
        assertEquals(emptyList(), whileUnlinked)

        val promotions = promotionDatabase.promoteUnresolvedParrotCloudSources(
            profileId = profileId,
            connectionId = connectionId,
            cloudUserId = "cloud-user-a",
        )
        assertEquals(1, promotions.size)
        assertEquals(
            LibrarySourceIdentityPromotionStatus.Promoted,
            promotions.single().status,
        )

        val prepared = prepareParrotCloudLibraryMutationsForWire(
            entries = listOf(entry),
            expectedLocalProfileId = profileId.value,
            linkedConnectionId = connectionId,
            cloudUserId = "cloud-user-a",
            promotionDatabase = promotionDatabase,
            identitySyncAuthorization = authorizeAllSources,
        )

        assertEquals(1, prepared.size)
        val wireReadiness = LibraryGroupDecisionCodec.encodeWireOrDefer(
            prepared.single().payload,
        )
        val wirePayload = assertIs<LibraryGroupSyncReadiness.Ready>(wireReadiness).payload
        assertTrue(portableCloud.key.nativeBookId.value in wirePayload)
        assertTrue(portableLocal.key.nativeBookId.value in wirePayload)
        assertTrue("abs-book" in wirePayload)
        assertFalse("imported-book-uuid" in wirePayload)
        assertFalse("local_profile_id" in wirePayload)
        assertFalse("connection_id" in wirePayload)
        assertEquals(storedPayload, entry.payload)
    }

    @Test
    fun wrongCloudAccountCannotRebindAPendingDecision() = runTest {
        // Given
        val cloudSource = unresolvedSource("parrot-cloud", connectionId.value, "cloud-book")
        val portableSource = portableCloudSource("cloud-user-a", "cloud-book")
        val promotionDatabase = FakePromotionDatabase().apply {
            addAlias(cloudSource.key, portableSource.key)
        }
        val audiobookshelfSource = portableAudiobookshelfSource("abs-account", "abs-book")
        val originalPayload = LibraryGroupDecisionCodec.encodeLocal(
            decisionPayload(listOf(cloudSource, audiobookshelfSource)),
        )

        // When: another authenticated account tries to use the alias.
        val attemptedPayload = resolvePromotedSourceMembersForWire(
            localPayload = originalPayload,
            expectedLocalProfileId = profileId.value,
            linkedConnectionId = connectionId,
            cloudUserId = "cloud-user-b",
            promotionDatabase = promotionDatabase,
        )

        // Then
        assertEquals(
            LibraryGroupSyncReadiness.Deferred(1),
            LibraryGroupDecisionCodec.encodeWireOrDefer(attemptedPayload),
        )
        assertEquals(originalPayload, attemptedPayload)
    }

    @Test
    fun everyDecisionSourceFieldUsesThePromotedCloudIdentity() = runTest {
        // Given
        val sources = listOf(
            unresolvedSource("parrot-cloud", connectionId.value, "member-book"),
            unresolvedSource("parrot-cloud", connectionId.value, "retained-book"),
            unresolvedSource("parrot-cloud", connectionId.value, "retained-list-book"),
            unresolvedSource("parrot-cloud", connectionId.value, "metadata-book"),
            unresolvedSource("parrot-cloud", connectionId.value, "media-book"),
        )
        val promotedSources = sources.map { source ->
            portableCloudSource("cloud-user-a", source.key.nativeBookId.value)
        }
        val promotionDatabase = FakePromotionDatabase().apply {
            sources.zip(promotedSources).forEach { (source, promoted) ->
                addAlias(source.key, promoted.key)
            }
        }
        val originalPayload = LibraryGroupDecisionCodec.encodeLocal(
            decisionPayload(sources.take(2)).copy(
                decisionType = LibraryGroupDecisionPayload.SPLIT,
                retainedGroupId = "group-retained",
                retainedMembers = listOf(LibraryGroupMemberRef.from(sources[2].key)),
                preferredMetadataMember = LibraryGroupMemberRef.from(sources[3].key),
                preferredMediaMembers = mapOf(
                    "audio" to LibraryGroupMemberRef.from(sources[4].key),
                ),
            ),
        )

        // When
        val translated = resolvePromotedSourceMembersForWire(
            localPayload = originalPayload,
            expectedLocalProfileId = profileId.value,
            linkedConnectionId = connectionId,
            cloudUserId = "cloud-user-a",
            promotionDatabase = promotionDatabase,
        )
        val translatedPayload = LibraryGroupDecisionCodec.decodeLocal(translated)

        // Then
        assertEquals(
            promotedSources.take(2).map { source -> LibraryGroupMemberRef.from(source.key) },
            translatedPayload.members,
        )
        assertEquals(
            listOf(LibraryGroupMemberRef.from(promotedSources[2].key)),
            translatedPayload.retainedMembers,
        )
        assertEquals(
            LibraryGroupMemberRef.from(promotedSources[3].key),
            translatedPayload.preferredMetadataMember,
        )
        assertEquals(
            mapOf("audio" to LibraryGroupMemberRef.from(promotedSources[4].key)),
            translatedPayload.preferredMediaMembers,
        )
        assertIs<LibraryGroupSyncReadiness.Ready>(
            LibraryGroupDecisionCodec.encodeWireOrDefer(translated),
        )
    }

    @Test
    fun wirePreparationCopiesResolvedPayloadAndLeavesStoredOutboxEntryUntouched() = runTest {
        // Given
        val cloudSource = unresolvedSource("parrot-cloud", connectionId.value, "cloud-book")
        val portableSource = portableCloudSource("cloud-user-a", "cloud-book")
        val localSource = portableAudiobookshelfSource("abs-account", "abs-book")
        val payload = LibraryGroupDecisionCodec.encodeLocal(
            decisionPayload(listOf(cloudSource, localSource)),
        )
        val groupEntry = outboxEntry(
            entityType = SyncOutboxEntry.ENTITY_TYPE_LIBRARY_GROUP_DECISION,
            payload = payload,
        )
        val unrelatedEntry = outboxEntry(
            entityType = SyncOutboxEntry.ENTITY_TYPE_READER_SETTINGS,
            payload = "{}",
        )
        val promotionDatabase = FakePromotionDatabase().apply {
            addAlias(cloudSource.key, portableSource.key)
        }

        // When
        val prepared = prepareParrotCloudLibraryMutationsForWire(
            entries = listOf(groupEntry, unrelatedEntry),
            expectedLocalProfileId = profileId.value,
            linkedConnectionId = connectionId,
            cloudUserId = "cloud-user-a",
            promotionDatabase = promotionDatabase,
            identitySyncAuthorization = authorizeAllSources,
        )

        // Then
        assertEquals(
            listOf(groupEntry.mutationId, unrelatedEntry.mutationId),
            prepared.map { entry -> entry.mutationId },
        )
        assertEquals(payload, groupEntry.payload)
        assertEquals(
            LibraryGroupMemberRef.from(portableSource.key),
            LibraryGroupDecisionCodec.decodeLocal(prepared.first().payload).members.first(),
        )
        assertEquals(unrelatedEntry, prepared.last())
    }

    @Test
    fun explicitMergeDeduplicatesPromotedAliasesInWireCopyOnly() = runTest {
        val unresolved = unresolvedSource("audiobookshelf", "abs-connection", "abs-book")
        val portable = portableAudiobookshelfSource("abs-account", "abs-book")
        val cloud = portableCloudSource("cloud-user-a", "cloud-book")
        val originalPayload = LibraryGroupDecisionCodec.encodeLocal(
            decisionPayload(listOf(unresolved, portable, cloud)),
        )
        val entry = outboxEntry(
            entityType = SyncOutboxEntry.ENTITY_TYPE_LIBRARY_GROUP_DECISION,
            payload = originalPayload,
        )
        val promotionDatabase = FakePromotionDatabase().apply {
            addAlias(unresolved.key, portable.key)
        }

        val prepared = prepareParrotCloudLibraryMutationsForWire(
            entries = listOf(entry),
            expectedLocalProfileId = profileId.value,
            linkedConnectionId = connectionId,
            cloudUserId = "cloud-user-a",
            promotionDatabase = promotionDatabase,
            identitySyncAuthorization = authorizeAllSources,
        )
        val resolvedPayload = prepared.single().payload

        assertEquals(
            listOf(portable, cloud).map { source -> LibraryGroupMemberRef.from(source.key) },
            LibraryGroupDecisionCodec.decodeLocal(resolvedPayload).members,
        )
        assertEquals(originalPayload, entry.payload)
        assertIs<LibraryGroupSyncReadiness.Ready>(
            LibraryGroupDecisionCodec.encodeWireOrDefer(resolvedPayload),
        )
    }

    @Test
    fun outboundMutationIsDeferredWhenAudiobookshelfBindingIsUnauthorized() = runTest {
        val cloudSource = portableCloudSource("cloud-user-a", "cloud-book")
        val audiobookshelf = portableAudiobookshelfSource("abs-account", "abs-book")
        val payload = LibraryGroupDecisionCodec.encodeLocal(
            decisionPayload(listOf(cloudSource, audiobookshelf)),
        )
        val entry = outboxEntry(
            entityType = SyncOutboxEntry.ENTITY_TYPE_LIBRARY_GROUP_DECISION,
            payload = payload,
        )
        val denyAudiobookshelf = LibrarySourceIdentitySyncAuthorization {
                _, _, adapterId, _ -> adapterId.value != "audiobookshelf"
        }

        val prepared = prepareParrotCloudLibraryMutationsForWire(
            entries = listOf(entry),
            expectedLocalProfileId = profileId.value,
            linkedConnectionId = connectionId,
            cloudUserId = "cloud-user-a",
            promotionDatabase = FakePromotionDatabase(),
            identitySyncAuthorization = denyAudiobookshelf,
        )

        assertEquals(emptyList(), prepared)
        assertEquals(payload, entry.payload)
    }

    @Test
    fun verifiedLocalAliasAndPortableAudiobookshelfDecisionIsSendableWithoutLocalFields() =
        runTest {
            // Given
            val unresolvedLocal = unresolvedSource(
                adapterId = LocalContentIdentity.ADAPTER_ID,
                connectionId = "local-installation",
                nativeBookId = "imported-book-uuid",
            )
            val portableLocal = portableLocalContentSource(VALID_HASH)
            val audiobookshelf = portableAudiobookshelfSource("abs-account", "abs-book")
            val storedPayload = LibraryGroupDecisionCodec.encodeLocal(
                decisionPayload(listOf(unresolvedLocal, audiobookshelf)),
            )
            val entry = outboxEntry(
                entityType = SyncOutboxEntry.ENTITY_TYPE_LIBRARY_GROUP_DECISION,
                payload = storedPayload,
            )
            val promotionDatabase = FakePromotionDatabase().apply {
                addAlias(unresolvedLocal.key, portableLocal.key)
            }

            // When
            val prepared = prepareParrotCloudLibraryMutationsForWire(
                entries = listOf(entry),
                expectedLocalProfileId = profileId.value,
                linkedConnectionId = connectionId,
                cloudUserId = "cloud-user-a",
                promotionDatabase = promotionDatabase,
                identitySyncAuthorization = authorizeAllSources,
            )

            // Then
            assertEquals(1, prepared.size)
            val readiness = LibraryGroupDecisionCodec.encodeWireOrDefer(prepared.single().payload)
            val wirePayload = assertIs<LibraryGroupSyncReadiness.Ready>(readiness).payload
            assertTrue("${LocalContentIdentity.HASH_ALGORITHM}:$VALID_HASH" in wirePayload)
            assertTrue("abs-book" in wirePayload)
            assertFalse("imported-book-uuid" in wirePayload)
            assertFalse("local-installation" in wirePayload)
            assertFalse("connection_id" in wirePayload)
            assertFalse("local_profile_id" in wirePayload)
            assertEquals(storedPayload, entry.payload)
        }

    @Test
    fun wirePreparationDefersUnlinkedAndCrossProfileGroupDecisions() = runTest {
        // Given
        val cloudSource = unresolvedSource("parrot-cloud", connectionId.value, "cloud-book")
        val localSource = portableAudiobookshelfSource("abs-account", "abs-book")
        val unlinkedEntry = outboxEntry(
            entityType = SyncOutboxEntry.ENTITY_TYPE_LIBRARY_GROUP_DECISION,
            payload = LibraryGroupDecisionCodec.encodeLocal(
                decisionPayload(listOf(cloudSource, localSource)),
            ),
        )
        val crossProfileMember = LibraryGroupMemberRef(
            profileId = "profile-b",
            adapterId = "parrot-cloud",
            identityKind = LibraryGroupMemberRef.PORTABLE,
            backendId = "parrot-cloud",
            accountId = "cloud-user-a",
            nativeBookId = "remote-book",
        )
        val crossProfileEntry = outboxEntry(
            entityType = SyncOutboxEntry.ENTITY_TYPE_LIBRARY_GROUP_DECISION,
            payload = LibraryGroupDecisionCodec.encodeLocal(
                decisionPayload(listOf(cloudSource, localSource)).copy(
                    members = listOf(
                        crossProfileMember,
                        LibraryGroupMemberRef.from(localSource.key),
                    ),
                ),
            ),
        )
        val wrongProfileEntry = outboxEntry(
            entityType = SyncOutboxEntry.ENTITY_TYPE_LIBRARY_GROUP_DECISION,
            payload = LibraryGroupDecisionCodec.encodeLocal(
                decisionPayload(
                    listOf(
                        localSource,
                        portableAudiobookshelfSource("abs-account", "second-abs-book"),
                    ),
                ).copy(localProfileId = "profile-b"),
            ),
        )

        // When
        val prepared = prepareParrotCloudLibraryMutationsForWire(
            entries = listOf(unlinkedEntry, crossProfileEntry, wrongProfileEntry),
            expectedLocalProfileId = profileId.value,
            linkedConnectionId = connectionId,
            cloudUserId = "cloud-user-a",
            promotionDatabase = FakePromotionDatabase(),
            identitySyncAuthorization = authorizeAllSources,
        )

        // Then
        assertEquals(emptyList(), prepared)
    }

    private fun decisionPayload(sources: List<SourceBookRef>) = LibraryGroupDecisionPayload(
        decisionId = "decision-a",
        decisionType = LibraryGroupDecisionPayload.MERGE,
        targetGroupId = "group-a",
        members = sources.map { source -> LibraryGroupMemberRef.from(source.key) },
        createdAt = "2026-09-25T00:00:00Z",
        localProfileId = profileId.value,
    )

    private fun unresolvedSource(
        adapterId: String,
        connectionId: String,
        nativeBookId: String,
    ) = SourceBookRef(
        key = SourceBookKey(
            profileId = profileId,
            adapterId = LibraryAdapterId(adapterId),
            accountIdentity = SourceAccountIdentity.Unresolved(
                SourceConnectionId(connectionId),
            ),
            nativeBookId = NativeBookId(nativeBookId),
        ),
        connectionId = SourceConnectionId(connectionId),
    )

    private fun portableCloudSource(
        cloudUserId: String,
        nativeBookId: String,
    ) = SourceBookRef(
        key = SourceBookKey(
            profileId = profileId,
            adapterId = LibraryAdapterId("parrot-cloud"),
            accountIdentity = SourceAccountIdentity.Portable("parrot-cloud", cloudUserId),
            nativeBookId = NativeBookId(nativeBookId),
        ),
        connectionId = connectionId,
    )

    private fun portableLocalContentSource(hash: String) = SourceBookRef(
        key = SourceBookKey(
            profileId = profileId,
            adapterId = LibraryAdapterId(LocalContentIdentity.ADAPTER_ID),
            accountIdentity = SourceAccountIdentity.Portable(
                LocalContentIdentity.BACKEND_ID,
                LocalContentIdentity.ACCOUNT_ID,
            ),
            nativeBookId = LocalContentIdentity.nativeBookId(hash),
        ),
        connectionId = SourceConnectionId("local-installation"),
    )

    private fun portableAudiobookshelfSource(
        accountId: String,
        nativeBookId: String,
    ) = SourceBookRef(
        key = SourceBookKey(
            profileId = profileId,
            adapterId = LibraryAdapterId("audiobookshelf"),
            accountIdentity = SourceAccountIdentity.Portable("audiobookshelf", accountId),
            nativeBookId = NativeBookId(nativeBookId),
        ),
        connectionId = SourceConnectionId("abs-connection"),
    )

    private fun outboxEntry(
        entityType: String,
        payload: String,
    ) = SyncOutboxEntry(
        mutationId = "mutation-$entityType",
        cloudUserId = "cloud-user-a",
        entityType = entityType,
        entityId = "decision-a",
        operation = SyncOutboxEntry.OPERATION_UPSERT,
        payload = payload,
        baseRevision = null,
        createdAt = "2026-09-25T00:00:00Z",
        attemptCount = 0,
        nextAttemptAt = null,
        lastError = null,
    )

    private class FakePromotionDatabase : LibrarySourceIdentityPromotionDatabase {
        private val aliases = mutableMapOf<SourceBookKey, SourceBookKey>()
        private val cloudPromotionPlans = mutableListOf<Pair<SourceBookKey, SourceBookRef>>()

        fun addAlias(from: SourceBookKey, to: SourceBookKey) {
            aliases[from] = to
        }

        fun scheduleCloudPromotion(unresolvedKey: SourceBookKey, portableSource: SourceBookRef) {
            cloudPromotionPlans += unresolvedKey to portableSource
        }

        override suspend fun promoteUnresolvedSourceIdentity(
            unresolvedKey: SourceBookKey,
            portableSource: SourceBookRef,
        ): LibrarySourceIdentityPromotionStatus {
            val unresolved = unresolvedKey.accountIdentity as? SourceAccountIdentity.Unresolved
                ?: return LibrarySourceIdentityPromotionStatus.Conflict
            if (
                portableSource.key.profileId != unresolvedKey.profileId ||
                portableSource.key.adapterId != unresolvedKey.adapterId ||
                portableSource.connectionId != unresolved.connectionId ||
                portableSource.key.accountIdentity !is SourceAccountIdentity.Portable
            ) {
                return LibrarySourceIdentityPromotionStatus.Conflict
            }
            val existing = aliases[unresolvedKey]
            if (existing != null) {
                return if (existing == portableSource.key) {
                    LibrarySourceIdentityPromotionStatus.AlreadyPromoted
                } else {
                    LibrarySourceIdentityPromotionStatus.Conflict
                }
            }
            aliases[unresolvedKey] = portableSource.key
            return LibrarySourceIdentityPromotionStatus.Promoted
        }

        override suspend fun resolveSourceIdentityAlias(key: SourceBookKey): SourceBookKey? =
            aliases[key]

        override suspend fun resolvePromotedParrotCloudSource(
            unresolvedKey: SourceBookKey,
            linkedConnectionId: SourceConnectionId,
            cloudUserId: String,
        ): SourceBookKey? {
            val target = aliases[unresolvedKey] ?: return null
            if (
                (unresolvedKey.accountIdentity as? SourceAccountIdentity.Unresolved)
                    ?.connectionId != linkedConnectionId
            ) {
                return null
            }
            val portable = target.accountIdentity as? SourceAccountIdentity.Portable ?: return null
            return target.takeIf { source ->
                source.profileId == unresolvedKey.profileId &&
                    source.adapterId.value == "parrot-cloud" &&
                    portable.backendId == "parrot-cloud" &&
                    portable.accountId == cloudUserId
            }
        }

        override suspend fun promoteUnresolvedParrotCloudSources(
            profileId: LibraryProfileId,
            connectionId: SourceConnectionId,
            cloudUserId: String,
        ): List<LibrarySourceIdentityPromotionResult> = cloudPromotionPlans
            .filter { (unresolvedKey, _) ->
                unresolvedKey.profileId == profileId &&
                    (unresolvedKey.accountIdentity as? SourceAccountIdentity.Unresolved)
                        ?.connectionId == connectionId
            }
            .mapNotNull { (unresolvedKey, portableSource) ->
                val portable = portableSource.key.accountIdentity
                    as? SourceAccountIdentity.Portable
                    ?: return@mapNotNull null
                if (
                    portable.backendId != "parrot-cloud" ||
                    portable.accountId != cloudUserId ||
                    portableSource.key.profileId != profileId ||
                    portableSource.key.adapterId.value != "parrot-cloud"
                ) {
                    return@mapNotNull null
                }
                LibrarySourceIdentityPromotionResult(
                    nativeBookId = unresolvedKey.nativeBookId.value,
                    status = promoteUnresolvedSourceIdentity(unresolvedKey, portableSource),
                )
            }
    }

    private companion object {
        const val PARROT_CLOUD_SERVER_ID = "parrot-cloud"
        const val VALID_HASH = "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef"
    }
}
