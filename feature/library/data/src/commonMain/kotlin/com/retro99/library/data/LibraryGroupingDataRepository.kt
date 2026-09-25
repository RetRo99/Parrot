package com.retro99.library.data

import com.github.michaelbull.result.getOrElse
import com.retro99.base.result.AppError
import com.retro99.base.result.AppResult
import com.retro99.cloudaccount.domain.CloudProfileLinkRepository
import com.retro99.database.api.library.LibraryAutomaticGroupMerge
import com.retro99.database.api.library.LibraryEvidenceDatabase
import com.retro99.database.api.library.LibraryGroupsDatabase
import com.retro99.database.api.library.LibraryManualGroupMerge
import com.retro99.database.api.library.LibraryManualGroupMemberSelection
import com.retro99.database.api.library.LibraryManualGroupSplit
import com.retro99.database.api.library.LibraryMediaPreferenceChange
import com.retro99.database.api.library.LibraryMediaPreferenceChangeResult
import com.retro99.database.api.library.LibrarySourceIdentityPromotionDatabase
import com.retro99.database.api.library.LibrarySourceIdentityPromotionStatus
import com.retro99.database.api.library.LibrarySourceSnapshotsDatabase
import com.retro99.database.api.sync.SyncOutboxEntry
import com.retro99.library.domain.grouping.AudiobookshelfPairingActionStatus
import com.retro99.library.domain.grouping.AudiobookshelfPairingResult
import com.retro99.library.domain.grouping.LibraryGroupMemberSelection
import com.retro99.library.domain.grouping.LibraryGroupPreferenceRepository
import com.retro99.library.domain.grouping.LibraryGroupingPlan
import com.retro99.library.domain.grouping.LibraryGroupingRepository
import com.retro99.library.domain.grouping.LibraryIdentityEvidence
import com.retro99.library.domain.grouping.LibraryIdentityResolver
import com.retro99.library.domain.grouping.LibraryManualGroupingRepository
import com.retro99.library.domain.grouping.LibraryMembershipAssignment
import com.retro99.library.domain.grouping.LibrarySourceIdentityPairingRepository
import com.retro99.library.domain.grouping.LibrarySourceSeparation
import com.retro99.library.domain.operation.RecoverLibraryDeviceReplicaRemovalsUseCase
import com.retro99.library.domain.projection.LibraryBookGroup
import com.retro99.library.domain.projection.LibraryGroupProjectionRepository
import com.retro99.library.domain.projection.LibraryGroupProjector
import com.retro99.server.api.AuthenticatedRepositoryProvider
import com.retro99.server.api.ServerAuthState
import com.retro99.server.api.ServerBook
import com.retro99.server.api.ServerBookListing
import com.retro99.server.api.ServerBookListingCompleteness
import com.retro99.server.api.ServerBooksRepository
import com.retro99.server.api.ServerRegistry
import com.retro99.server.api.ServerType
import com.retro99.server.api.library.LegacyLibraryBookId
import com.retro99.server.api.library.LocalContentIdentity
import com.retro99.server.api.library.LibraryAdapterId
import com.retro99.server.api.library.LibraryGroupId
import com.retro99.server.api.library.LibraryProfileId
import com.retro99.server.api.library.LibrarySourceAdapterRegistry
import com.retro99.server.api.library.LibrarySourceIdentityPromotionAdapter
import com.retro99.server.api.library.LibrarySourceIdentityPairing
import com.retro99.server.api.library.LibrarySourceIdentityPairingException
import com.retro99.server.api.library.LibrarySourceIdentityPairingFailure
import com.retro99.server.api.library.LibrarySourceIdentityPairingStatus
import com.retro99.server.api.library.NativeBookId
import com.retro99.server.api.library.ServerBookSourceAdapter
import com.retro99.server.api.library.SourceAccountIdentity
import com.retro99.server.api.library.SourceBookKey
import com.retro99.server.api.library.SourceBookRef
import com.retro99.server.api.library.SourceBookSnapshot
import com.retro99.server.api.library.SourceConnectionId
import com.retro99.server.api.library.SourcePresence
import com.retro99.server.api.library.SourceSnapshotStatus
import com.retro99.sync.domain.LibraryGroupDecisionCodec
import com.retro99.sync.domain.LibraryGroupDecisionPayload
import com.retro99.sync.domain.LibraryGroupMemberRef
import com.retro99.user.api.UserRegistry
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.merge
import org.koin.core.annotation.Provided
import org.koin.core.annotation.Single
import kotlin.time.Clock
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

private const val AUDIOBOOKSHELF_ADAPTER_ID = "audiobookshelf"
private const val AUDIOBOOKSHELF_BACKEND_PREFIX = "audiobookshelf-instance:"

@Single(
    binds = [
        LibraryGroupingRepository::class,
        LibraryManualGroupingRepository::class,
        LibraryGroupPreferenceRepository::class,
        LibraryGroupProjectionRepository::class,
        LibrarySourceIdentityPairingRepository::class,
    ],
)
class LibraryGroupingDataRepository(
    private val groupsDatabase: LibraryGroupsDatabase,
    private val evidenceDatabase: LibraryEvidenceDatabase,
    private val sourceIdentityPromotionDatabase: LibrarySourceIdentityPromotionDatabase,
    private val snapshotsDatabase: LibrarySourceSnapshotsDatabase,
    @Provided private val repositoryProvider: AuthenticatedRepositoryProvider,
    @Provided private val sourceAdapterRegistry: LibrarySourceAdapterRegistry,
    @Provided private val userRegistry: UserRegistry,
    @Provided private val recoverLibraryDeviceReplicaRemovalsUseCase:
        RecoverLibraryDeviceReplicaRemovalsUseCase,
    @Provided private val serverRegistry: ServerRegistry? = null,
    @Provided private val cloudProfileLinkRepository: CloudProfileLinkRepository? = null,
) : LibraryGroupingRepository,
    LibraryManualGroupingRepository,
    LibraryGroupPreferenceRepository,
    LibraryGroupProjectionRepository,
    LibrarySourceIdentityPairingRepository {
    @OptIn(ExperimentalCoroutinesApi::class)
    override fun observeAudiobookshelfPairingStatus(
        profileId: LibraryProfileId,
        serverId: String,
    ): Flow<LibrarySourceIdentityPairingStatus> = flow {
        val registry = serverRegistry
        val cloudLinks = cloudProfileLinkRepository
        if (registry == null || cloudLinks == null) {
            emit(LibrarySourceIdentityPairingStatus.ServerUnavailable)
            return@flow
        }
        val activeProfileIds = userRegistry.observeActiveProfile()
            .map { profile -> profile?.id ?: UserRegistry.DEFAULT_USER_ID }
            .distinctUntilChanged()
        emitAll(
            activeProfileIds.flatMapLatest { activeProfileId ->
                if (activeProfileId != profileId.value) {
                    return@flatMapLatest flow {
                        emit(LibrarySourceIdentityPairingStatus.ServerUnavailable)
                    }
                }
                combine(
                    registry.observeAllServers(),
                    cloudLinks.observeForLocalProfile(profileId.value),
                    registry.observeAuthState(serverId),
                ) { servers, cloudLink, authState ->
                    Triple(servers, cloudLink, authState)
                }.mapLatest { (servers, cloudLink, authState) ->
                    val server = servers.firstOrNull { candidate -> candidate.id == serverId }
                    val binding = server?.libraryIdentityBindings?.firstOrNull { candidate ->
                        candidate.adapterId.value == AUDIOBOOKSHELF_ADAPTER_ID
                    }
                    when {
                        server?.type != ServerType.Audiobookshelf ->
                            LibrarySourceIdentityPairingStatus.ServerUnavailable
                        binding == null -> LibrarySourceIdentityPairingStatus.Unpaired
                        cloudLink == null ->
                            LibrarySourceIdentityPairingStatus.CloudAccountRequired
                        cloudLink.cloudUserId != binding.cloudAccountId ->
                            LibrarySourceIdentityPairingStatus.CloudAccountMismatch
                        authState !is ServerAuthState.Authenticated ->
                            LibrarySourceIdentityPairingStatus.NotAuthenticated
                        registry.getCredentials(serverId)?.accountId !=
                            binding.sourceAccountId ->
                            LibrarySourceIdentityPairingStatus.SourceAccountMismatch
                        else -> LibrarySourceIdentityPairingStatus.Paired
                    }
                }.distinctUntilChanged()
            },
        )
    }

    override suspend fun createAudiobookshelfPairingCode(
        profileId: LibraryProfileId,
        serverId: String,
    ): AudiobookshelfPairingResult {
        val source = pairingSource(profileId, serverId)
            ?: return AudiobookshelfPairingResult(
                status = pairingFailureStatus(profileId, serverId),
            )
        val pairing = source.repository as? LibrarySourceIdentityPairing
            ?: return AudiobookshelfPairingResult(
                status = AudiobookshelfPairingActionStatus.ServerUnavailable,
            )
        return try {
            val code = pairing.createPairingCode()
            val identity = source.repository.libraryAccountIdentity()
                ?: return AudiobookshelfPairingResult(
                    status = AudiobookshelfPairingActionStatus.ServerUnavailable,
                    code = code,
                )
            AudiobookshelfPairingResult(
                status = AudiobookshelfPairingActionStatus.Completed,
                code = code,
                conflictingNativeBookIds = promotePairingMemberships(
                    profileId = profileId,
                    serverId = serverId,
                    source = source,
                    identity = identity,
                ),
            )
        } catch (exception: LibrarySourceIdentityPairingException) {
            AudiobookshelfPairingResult(exception.reason.toPairingActionStatus())
        }
    }

    override suspend fun bindAudiobookshelfPairingCode(
        profileId: LibraryProfileId,
        serverId: String,
        code: String,
    ): AudiobookshelfPairingResult {
        val source = pairingSource(profileId, serverId)
            ?: return AudiobookshelfPairingResult(
                status = pairingFailureStatus(profileId, serverId),
            )
        val pairing = source.repository as? LibrarySourceIdentityPairing
            ?: return AudiobookshelfPairingResult(
                status = AudiobookshelfPairingActionStatus.ServerUnavailable,
            )
        return try {
            pairing.importPairingCode(code)
            val identity = source.repository.libraryAccountIdentity()
                ?: return AudiobookshelfPairingResult(
                    status = AudiobookshelfPairingActionStatus.ServerUnavailable,
                )
            AudiobookshelfPairingResult(
                status = AudiobookshelfPairingActionStatus.Completed,
                conflictingNativeBookIds = promotePairingMemberships(
                    profileId = profileId,
                    serverId = serverId,
                    source = source,
                    identity = identity,
                ),
            )
        } catch (exception: LibrarySourceIdentityPairingException) {
            AudiobookshelfPairingResult(exception.reason.toPairingActionStatus())
        }
    }

    override suspend fun revokeAudiobookshelfPairing(
        profileId: LibraryProfileId,
        serverId: String,
    ): AudiobookshelfPairingResult {
        val registry = serverRegistry ?: return AudiobookshelfPairingResult(
            AudiobookshelfPairingActionStatus.ServerUnavailable,
        )
        if (!isActiveProfile(profileId)) {
            return AudiobookshelfPairingResult(
                AudiobookshelfPairingActionStatus.ServerUnavailable,
            )
        }
        val server = registry.getServer(serverId)
            ?: return AudiobookshelfPairingResult(
                AudiobookshelfPairingActionStatus.ServerUnavailable,
            )
        if (server.type != ServerType.Audiobookshelf) {
            return AudiobookshelfPairingResult(
                AudiobookshelfPairingActionStatus.ServerUnavailable,
            )
        }
        val binding = server.libraryIdentityBindings.firstOrNull { candidate ->
            candidate.adapterId.value == AUDIOBOOKSHELF_ADAPTER_ID
        }
        val blockingMembershipCount = binding?.let { pairingBinding ->
            groupsDatabase.getAllMemberships(profileId).count { membership ->
                val identity = membership.source.key.accountIdentity
                    as? SourceAccountIdentity.Portable
                membership.source.key.adapterId.value == AUDIOBOOKSHELF_ADAPTER_ID &&
                    identity?.backendId == pairingBinding.backendId &&
                    identity.accountId == pairingBinding.sourceAccountId
            }
        } ?: 0
        if (blockingMembershipCount > 0) {
            return AudiobookshelfPairingResult(
                status = AudiobookshelfPairingActionStatus.HasSharedMemberships,
                blockingMembershipCount = blockingMembershipCount,
            )
        }
        val remainingBindings = server.libraryIdentityBindings.filterNot { binding ->
            binding.adapterId.value == AUDIOBOOKSHELF_ADAPTER_ID
        }
        registry.updateServer(server.copy(libraryIdentityBindings = remainingBindings))
        return AudiobookshelfPairingResult(AudiobookshelfPairingActionStatus.Completed)
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    override fun observeGroups(profileId: LibraryProfileId): Flow<List<LibraryBookGroup>> = flow {
        recoverLibraryDeviceReplicaRemovalsUseCase(profileId)
        val initialGroups = loadGroupProjections(profileId)
        emit(initialGroups)
        emitAll(
            merge(
                observeSourceSnapshots(profileId),
                groupsDatabase.observeProjectionChanges(profileId),
                snapshotsDatabase.observeSnapshotChanges(profileId),
            ).map { loadGroupProjections(profileId) },
        )
    }
        .distinctUntilChanged()

    @OptIn(ExperimentalCoroutinesApi::class)
    override fun observeActiveGroups(): Flow<List<LibraryBookGroup>> =
        userRegistry.observeActiveProfile()
            .map { profile -> LibraryProfileId(profile?.id ?: UserRegistry.DEFAULT_USER_ID) }
            .distinctUntilChanged()
            .flatMapLatest { profileId -> observeGroups(profileId) }

    override suspend fun getGroup(
        profileId: LibraryProfileId,
        groupId: LibraryGroupId,
    ): LibraryBookGroup? {
        val resolvedId = groupsDatabase.resolveGroupId(profileId, groupId) ?: return null
        return loadGroupProjections(profileId).firstOrNull { group ->
            group.groupId == resolvedId
        }
    }

    override suspend fun reconcileAutomaticGroups(
        profileId: LibraryProfileId,
        appliedAt: String,
    ): LibraryGroupingPlan {
        val memberships = groupsDatabase.getAllMemberships(profileId).map { membership ->
            LibraryMembershipAssignment(
                source = membership.source.key,
                groupId = membership.groupId,
                origin = membership.origin,
                revision = membership.revision,
                decisionId = membership.decisionId,
            )
        }
        val separations = groupsDatabase.getManualSeparations(profileId).map { separation ->
            LibrarySourceSeparation(separation.first, separation.second)
        }.toSet()
        val evidence = evidenceDatabase.getActiveEvidence(profileId).map { record ->
            LibraryIdentityEvidence(record.evidenceId, record.evidence)
        }
        val plan = LibraryIdentityResolver.resolve(memberships, separations, evidence)
        plan.automaticJoins.forEach { join ->
            groupsDatabase.applyAutomaticMerge(
                LibraryAutomaticGroupMerge(
                    profileId = join.profileId,
                    survivorGroupId = join.survivorGroupId,
                    retiredGroupIds = join.retiredGroupIds,
                    sourceKeys = join.sourceKeys,
                    evidenceIds = join.evidenceIds,
                    appliedAt = appliedAt,
                ),
            )
        }
        return plan
    }

    @OptIn(ExperimentalUuidApi::class)
    override suspend fun mergeMembers(
        profileId: LibraryProfileId,
        members: List<LibraryGroupMemberSelection>,
        preferredMetadataSourceKey: SourceBookKey?,
    ): LibraryGroupId {
        val newGroupId = LibraryGroupId(Uuid.random().toString())
        val decisionId = Uuid.random().toString()
        val appliedAt = Clock.System.now().toString()
        val payload = LibraryGroupDecisionPayload(
            decisionId = decisionId,
            decisionType = LibraryGroupDecisionPayload.MERGE,
            targetGroupId = newGroupId.value,
            members = members.map { member -> LibraryGroupMemberRef.from(member.sourceKey) },
            preferredMetadataMember = preferredMetadataSourceKey?.let { sourceKey ->
                LibraryGroupMemberRef.from(sourceKey)
            },
            createdAt = appliedAt,
            localProfileId = profileId.value,
        )
        groupsDatabase.applyManualMerge(
            LibraryManualGroupMerge(
                profileId = profileId,
                newGroupId = newGroupId,
                members = members.map { member ->
                    LibraryManualGroupMemberSelection(
                        sourceKey = member.sourceKey,
                        expectedGroupId = member.expectedGroupId,
                    )
                },
                decisionId = decisionId,
                appliedAt = appliedAt,
                preferredMetadataSourceKey = preferredMetadataSourceKey,
                outboxEntry = payload.toOutboxEntry(),
            ),
        )
        return newGroupId
    }

    @OptIn(ExperimentalUuidApi::class)
    override suspend fun splitMembers(
        profileId: LibraryProfileId,
        sourceGroupId: LibraryGroupId,
        movedSourceKeys: List<SourceBookKey>,
    ): LibraryGroupId {
        val newGroupId = LibraryGroupId(Uuid.random().toString())
        val decisionId = Uuid.random().toString()
        val appliedAt = Clock.System.now().toString()
        val currentMembers = groupsDatabase.getMemberships(profileId, sourceGroupId)
            .map { membership -> membership.source.key }
        val movedMembers = movedSourceKeys.toSet()
        check(currentMembers.containsAll(movedMembers)) {
            "Split selection is stale or contains a nonmember"
        }
        val retainedSourceKeys = currentMembers.filterNot { sourceKey -> sourceKey in movedMembers }
        val payload = LibraryGroupDecisionPayload(
            decisionId = decisionId,
            decisionType = LibraryGroupDecisionPayload.SPLIT,
            targetGroupId = newGroupId.value,
            retainedGroupId = sourceGroupId.value,
            members = movedSourceKeys.map { sourceKey -> LibraryGroupMemberRef.from(sourceKey) },
            retainedMembers = retainedSourceKeys.map { sourceKey ->
                LibraryGroupMemberRef.from(sourceKey)
            },
            createdAt = appliedAt,
            localProfileId = profileId.value,
        )
        groupsDatabase.applyManualSplit(
            LibraryManualGroupSplit(
                profileId = profileId,
                sourceGroupId = sourceGroupId,
                newGroupId = newGroupId,
                movedSourceKeys = movedSourceKeys,
                decisionId = decisionId,
                appliedAt = appliedAt,
                retainedSourceKeys = retainedSourceKeys,
                outboxEntry = payload.toOutboxEntry(),
            ),
        )
        return newGroupId
    }

    @OptIn(ExperimentalUuidApi::class)
    override suspend fun setPreferredMediaSource(
        profileId: LibraryProfileId,
        groupId: LibraryGroupId,
        mediaType: String,
        sourceKey: SourceBookKey,
    ): Boolean {
        require(mediaType.isNotBlank())
        require(sourceKey.profileId == profileId) {
            "A media preference cannot cross profiles"
        }
        val normalizedMediaType = mediaType.lowercase()
        val activeGroupId = groupsDatabase.resolveGroupId(profileId, groupId)
            ?: error("The library group no longer exists")
        val membership = groupsDatabase.getMembership(sourceKey)
        check(membership?.groupId == activeGroupId) {
            "The selected source is no longer in this library group"
        }
        val snapshot = snapshotsDatabase.getSnapshot(sourceKey)
            ?: error("The selected source is no longer available")
        check(snapshot.status.presence != SourcePresence.Removed) {
            "A removed source cannot be preferred"
        }
        check(snapshot.resources.any { resource ->
            resource.mediaType.equals(normalizedMediaType, ignoreCase = true)
        }) { "The selected source does not contain this media type" }

        val group = groupsDatabase.getGroup(profileId, activeGroupId)
            ?: error("The library group no longer exists")
        if (group.preferredMediaSourceKeys[normalizedMediaType] == sourceKey) return false

        val decisionId = Uuid.random().toString()
        val appliedAt = Clock.System.now().toString()
        val memberReference = LibraryGroupMemberRef.from(sourceKey)
        val payload = LibraryGroupDecisionPayload(
            decisionId = decisionId,
            decisionType = LibraryGroupDecisionPayload.PREFERENCES,
            targetGroupId = activeGroupId.value,
            members = listOf(memberReference),
            preferredMediaMembers = mapOf(normalizedMediaType to memberReference),
            createdAt = appliedAt,
            localProfileId = profileId.value,
        )
        val result = groupsDatabase.applyMediaPreference(
            LibraryMediaPreferenceChange(
                profileId = profileId,
                activeGroupId = activeGroupId,
                mediaType = normalizedMediaType,
                memberSourceKey = sourceKey,
                decisionId = decisionId,
                appliedAt = appliedAt,
                outboxEntry = payload.toOutboxEntry(),
            ),
        )
        return when (result) {
            LibraryMediaPreferenceChangeResult.Applied -> true
            LibraryMediaPreferenceChangeResult.AlreadyPreferred -> false
            LibraryMediaPreferenceChangeResult.StaleMembership -> error(
                "The selected source is no longer in this library group",
            )
        }
    }

    private suspend fun loadGroupProjections(
        profileId: LibraryProfileId,
    ): List<LibraryBookGroup> {
        val membershipRecords = groupsDatabase.getAllMemberships(profileId)
        val memberships = membershipRecords.mapNotNull { membership ->
            val resolvedGroupId = groupsDatabase.resolveGroupId(profileId, membership.groupId)
                ?: return@mapNotNull null
            LibraryMembershipAssignment(
                source = membership.source.key,
                groupId = resolvedGroupId,
                origin = membership.origin,
                revision = membership.revision,
                decisionId = membership.decisionId,
            )
        }
        val groupIds = memberships.map { membership -> membership.groupId }
            .distinct()
        val groupPreferences = groupIds.mapNotNull { groupId ->
            groupsDatabase.getGroup(profileId, groupId)?.let { group ->
                groupId to group
            }
        }.toMap()
        val snapshots = snapshotsDatabase.getSnapshots(profileId)
        return LibraryGroupProjector.project(
            memberships = memberships,
            snapshots = snapshots,
            preferredMetadataSources = groupPreferences.mapNotNull { (groupId, group) ->
                group.preferredMetadataSourceKey?.let { sourceKey -> groupId to sourceKey }
            }.toMap(),
            preferredMediaSources = groupPreferences.mapValues { (_, group) ->
                group.preferredMediaSourceKeys
            },
        )
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    private fun observeSourceSnapshots(profileId: LibraryProfileId): Flow<Unit> =
        flow {
            backfillPersistedGroups(profileId)
            emitAll(observeConnectedSourceSnapshots(profileId))
        }

    @OptIn(ExperimentalCoroutinesApi::class)
    private fun observeConnectedSourceSnapshots(profileId: LibraryProfileId): Flow<Unit> =
        repositoryProvider.observeBooksRepositories().flatMapLatest { repositories ->
            val sources = repositories.mapNotNull { repository ->
                val adapterId = repository.libraryAdapterId ?: return@mapNotNull null
                val adapter = sourceAdapterRegistry.adapter(adapterId)
                    as? ServerBookSourceAdapter ?: return@mapNotNull null
                SourceRepository(repository, adapterId, adapter)
            }
            flow {
                val activeConnections = sources.map { source -> source.repository.serverId }.toSet()
                markDisconnectedSnapshotsUnknown(profileId, activeConnections)
                if (sources.isEmpty()) {
                    emit(Unit)
                } else {
                    val sourceFlows = sources.map { source ->
                        flow {
                            emitAll(source.repository.getLibraryListing())
                        }.catch { exception ->
                            emit(
                                com.github.michaelbull.result.Err(
                                    AppError.UnknownError(exception),
                                ),
                            )
                        }.map { result -> SourceBooksEmission(source, result) }
                    }
                    combine(sourceFlows) { emissions -> emissions.toList() }
                        .collect { emissions ->
                            val observedAt = Clock.System.now()
                            emissions.forEach { emission ->
                                val result = emission.result
                                if (result.isOk) {
                                    val listing = result.getOrElse {
                                        error("Successful library listing did not contain data")
                                    }
                                    val connectionId = SourceConnectionId(
                                        emission.source.repository.serverId,
                                    )
                                    val accountIdentity = emission.source.repository
                                        .libraryAccountIdentity()
                                        ?: SourceAccountIdentity.Unresolved(connectionId)
                                    val protectedNativeBookIds = saveSourceBooks(
                                        profileId = profileId,
                                        source = emission.source,
                                        accountIdentity = accountIdentity,
                                        listing = listing,
                                        observedAt = observedAt,
                                    )
                                    val explicitlyRemovedNativeBookIds =
                                        listing.removedNativeBookIds.map { nativeBookId ->
                                            nativeBookId.value
                                        }.toSet() - protectedNativeBookIds
                                    if (explicitlyRemovedNativeBookIds.isNotEmpty()) {
                                        reconcileExplicitlyRemovedSourceBooks(
                                            profileId = profileId,
                                            source = emission.source,
                                            accountIdentity = accountIdentity,
                                            removedNativeBookIds = explicitlyRemovedNativeBookIds,
                                            observedAt = observedAt,
                                        )
                                    }
                                    if (
                                        listing.completeness ==
                                            ServerBookListingCompleteness.Complete
                                    ) {
                                        reconcileOmittedSourceBooks(
                                            profileId = profileId,
                                            source = emission.source,
                                            accountIdentity = accountIdentity,
                                            listedNativeBookIds = protectedNativeBookIds,
                                            observedAt = observedAt,
                                        )
                                    }
                                } else {
                                    markConnectionSnapshotsUnknown(
                                        profileId = profileId,
                                        source = emission.source,
                                        observedAt = observedAt,
                                    )
                                }
                            }
                            reconcileAutomaticGroups(profileId, observedAt.toString())
                            emit(Unit)
                        }
                }
            }
        }

    private suspend fun backfillPersistedGroups(profileId: LibraryProfileId) {
        val migrationId = "unified-library-groups-v1"
        val startedAt = Clock.System.now().toString()
        while (true) {
            val result = snapshotsDatabase.backfillGroups(
                profileId = profileId,
                migrationId = migrationId,
                startedAt = startedAt,
                completedAt = Clock.System.now().toString(),
            )
            if (result.isComplete) return
            check(result.processedSnapshots > 0) {
                "Library group backfill did not advance its migration checkpoint"
            }
        }
    }

    private suspend fun pairingSource(
        profileId: LibraryProfileId,
        serverId: String,
    ): SourceRepository? {
        if (!isActiveProfile(profileId)) return null
        val server = serverRegistry?.getServer(serverId) ?: return null
        if (server.type != ServerType.Audiobookshelf) return null
        val repository = repositoryProvider.getBooksRepository(serverId) ?: return null
        val adapterId = repository.libraryAdapterId ?: return null
        val adapter = sourceAdapterRegistry.adapter(adapterId) as? ServerBookSourceAdapter
            ?: return null
        return SourceRepository(repository, adapterId, adapter)
    }

    private suspend fun isActiveProfile(profileId: LibraryProfileId): Boolean =
        userRegistry.getActiveProfileIdOrDefault() == profileId.value

    private suspend fun pairingFailureStatus(
        profileId: LibraryProfileId,
        serverId: String,
    ): AudiobookshelfPairingActionStatus {
        if (!isActiveProfile(profileId)) return AudiobookshelfPairingActionStatus.ServerUnavailable
        val registry = serverRegistry ?: return AudiobookshelfPairingActionStatus.ServerUnavailable
        val server = registry.getServer(serverId)
            ?: return AudiobookshelfPairingActionStatus.ServerUnavailable
        if (server.type != ServerType.Audiobookshelf) {
            return AudiobookshelfPairingActionStatus.ServerUnavailable
        }
        if (!registry.isAuthenticated(serverId)) {
            return AudiobookshelfPairingActionStatus.NotAuthenticated
        }
        if (cloudProfileLinkRepository?.getForLocalProfile(profileId.value) == null) {
            return AudiobookshelfPairingActionStatus.CloudAccountRequired
        }
        return AudiobookshelfPairingActionStatus.ServerUnavailable
    }

    private suspend fun promotePairingMemberships(
        profileId: LibraryProfileId,
        serverId: String,
        source: SourceRepository,
        identity: SourceAccountIdentity.Portable,
    ): Set<NativeBookId> = promoteUnresolvedMemberships(
        profileId = profileId,
        source = source,
        accountIdentity = identity,
        connectionId = SourceConnectionId(serverId),
    ).mapTo(mutableSetOf(), ::NativeBookId)

    private fun LibrarySourceIdentityPairingFailure.toPairingActionStatus() = when (this) {
        LibrarySourceIdentityPairingFailure.CloudAccountRequired ->
            AudiobookshelfPairingActionStatus.CloudAccountRequired
        LibrarySourceIdentityPairingFailure.CloudAccountMismatch ->
            AudiobookshelfPairingActionStatus.CloudAccountMismatch
        LibrarySourceIdentityPairingFailure.SourceAccountMismatch ->
            AudiobookshelfPairingActionStatus.SourceAccountMismatch
        LibrarySourceIdentityPairingFailure.SourceAccountIdUnavailable ->
            AudiobookshelfPairingActionStatus.SourceAccountIdUnavailable
        LibrarySourceIdentityPairingFailure.AlreadyPaired ->
            AudiobookshelfPairingActionStatus.AlreadyPaired
        LibrarySourceIdentityPairingFailure.InvalidCode ->
            AudiobookshelfPairingActionStatus.InvalidCode
        LibrarySourceIdentityPairingFailure.UnsupportedCodeVersion ->
            AudiobookshelfPairingActionStatus.UnsupportedCodeVersion
        LibrarySourceIdentityPairingFailure.NotAuthenticated ->
            AudiobookshelfPairingActionStatus.NotAuthenticated
        LibrarySourceIdentityPairingFailure.ServerUnavailable ->
            AudiobookshelfPairingActionStatus.ServerUnavailable
    }

    private suspend fun saveSourceBooks(
        profileId: LibraryProfileId,
        source: SourceRepository,
        accountIdentity: SourceAccountIdentity,
        listing: ServerBookListing,
        observedAt: kotlin.time.Instant,
    ): Set<String> {
        val connectionId = SourceConnectionId(source.repository.serverId)
        val conflictedNativeBookIds = promoteUnresolvedMemberships(
            profileId = profileId,
            source = source,
            accountIdentity = accountIdentity,
            connectionId = connectionId,
        )
        val listedNativeBookIds = conflictedNativeBookIds.toMutableSet()
        val snapshotsBySourceKey = linkedMapOf<SourceBookKey, SourceBookSnapshot>()
        listing.books.sortedBy { book -> book.uuid }.forEach { book ->
            if (book.title.isBlank() || book.uuid in conflictedNativeBookIds) return@forEach
            val sourceRef = SourceBookRef(
                key = SourceBookKey(
                    profileId = profileId,
                    adapterId = source.adapterId,
                    accountIdentity = accountIdentity,
                    nativeBookId = NativeBookId(book.uuid),
                ),
                connectionId = connectionId,
                legacyLibraryBookId = if (
                    source.adapterId.value == LocalContentIdentity.ADAPTER_ID
                ) {
                    val hash = LocalContentIdentity.canonicalHash(book.contentHash)
                    if (
                        book.contentHashAlgorithm == LocalContentIdentity.HASH_ALGORITHM &&
                        hash != null
                    ) {
                        LegacyLibraryBookId("${LocalContentIdentity.HASH_ALGORITHM}:$hash")
                    } else {
                        null
                    }
                } else {
                    book.libraryBookId?.let(::LegacyLibraryBookId)
                },
            )
            val unresolvedSourceKey = SourceBookKey(
                profileId = profileId,
                adapterId = source.adapterId,
                accountIdentity = SourceAccountIdentity.Unresolved(connectionId),
                nativeBookId = NativeBookId(book.uuid),
            )
            val snapshot = source.adapter.snapshot(
                source = sourceRef,
                book = book,
                status = SourceSnapshotStatus(
                    observedAt = observedAt,
                    presence = SourcePresence.Present,
                    isAuthoritative =
                        listing.completeness == ServerBookListingCompleteness.Complete,
                    revision = book.remoteRevision?.toString(),
                ),
            )
            if (snapshot.source.key.accountIdentity is SourceAccountIdentity.Portable) {
                sourceIdentityPromotionDatabase.promoteUnresolvedSourceIdentity(
                    unresolvedKey = unresolvedSourceKey,
                    portableSource = snapshot.source,
                )
            }
            val existingSnapshot = snapshotsBySourceKey[snapshot.source.key]
            snapshotsBySourceKey[snapshot.source.key] = if (existingSnapshot == null) {
                snapshot
            } else {
                existingSnapshot.copy(
                    resources = (existingSnapshot.resources + snapshot.resources)
                        .distinctBy { resource -> resource.reference },
                    identityEvidence = (
                        existingSnapshot.identityEvidence + snapshot.identityEvidence
                    )
                        .distinct(),
                )
            }
        }
        snapshotsBySourceKey.forEach { (sourceKey, snapshot) ->
            snapshotsDatabase.saveSnapshot(snapshot)
            listedNativeBookIds += sourceKey.nativeBookId.value
        }
        return listedNativeBookIds
    }

    private suspend fun promoteUnresolvedMemberships(
        profileId: LibraryProfileId,
        source: SourceRepository,
        accountIdentity: SourceAccountIdentity,
        connectionId: SourceConnectionId,
    ): Set<String> {
        val portableAccountIdentity = accountIdentity as? SourceAccountIdentity.Portable
            ?: return emptySet()
        val promotionAdapter = sourceAdapterRegistry.adapter(source.adapterId)
            as? LibrarySourceIdentityPromotionAdapter ?: return emptySet()
        val unresolvedMemberships = groupsDatabase.getAllMemberships(profileId)
            .map { membership -> membership.source }
            .filter { sourceRef ->
                val unresolvedIdentity = sourceRef.key.accountIdentity
                    as? SourceAccountIdentity.Unresolved
                sourceRef.key.profileId == profileId &&
                    sourceRef.key.adapterId == source.adapterId &&
                    unresolvedIdentity?.connectionId == connectionId &&
                    sourceRef.connectionId == connectionId
            }
        return unresolvedMemberships.mapNotNull { unresolvedSource ->
            val portableSource = promotionAdapter.promoteUnresolvedSourceIdentity(
                unresolvedSource = unresolvedSource,
                portableAccountIdentity = portableAccountIdentity,
            ) ?: return@mapNotNull null
            check(
                portableSource.key.profileId == profileId &&
                    portableSource.key.adapterId == source.adapterId &&
                    portableSource.key.accountIdentity == portableAccountIdentity &&
                    portableSource.connectionId == connectionId,
            ) {
                "Identity promotion adapter returned a source outside the requested scope"
            }
            val result = sourceIdentityPromotionDatabase.promoteUnresolvedSourceIdentity(
                unresolvedKey = unresolvedSource.key,
                portableSource = portableSource,
            )
            unresolvedSource.key.nativeBookId.value.takeIf { nativeBookId ->
                result == LibrarySourceIdentityPromotionStatus.Conflict
            }
        }.toSet()
    }

    private suspend fun reconcileOmittedSourceBooks(
        profileId: LibraryProfileId,
        source: SourceRepository,
        accountIdentity: SourceAccountIdentity,
        listedNativeBookIds: Set<String>,
        observedAt: kotlin.time.Instant,
    ) {
        val connectionId = SourceConnectionId(source.repository.serverId)
        snapshotsDatabase.getSnapshots(profileId)
            .filter { snapshot ->
                snapshot.source.key.adapterId == source.adapterId &&
                    if (source.adapterId.value == LocalContentIdentity.ADAPTER_ID) {
                        snapshot.source.connectionId == connectionId
                    } else {
                        snapshot.source.key.accountIdentity == accountIdentity
                    } &&
                    snapshot.source.key.nativeBookId.value !in listedNativeBookIds &&
                    snapshot.status.presence != SourcePresence.Removed
            }
            .forEach { snapshot ->
                snapshotsDatabase.saveSnapshot(
                    snapshot.copy(
                        status = SourceSnapshotStatus(
                            observedAt = observedAt,
                            presence = SourcePresence.Removed,
                            isAuthoritative = true,
                            revision = snapshot.status.revision,
                        ),
                    ),
                )
            }
    }

    private suspend fun reconcileExplicitlyRemovedSourceBooks(
        profileId: LibraryProfileId,
        source: SourceRepository,
        accountIdentity: SourceAccountIdentity,
        removedNativeBookIds: Set<String>,
        observedAt: kotlin.time.Instant,
    ) {
        val connectionId = SourceConnectionId(source.repository.serverId)
        snapshotsDatabase.getSnapshots(profileId)
            .filter { snapshot ->
                snapshot.source.key.adapterId == source.adapterId &&
                    if (source.adapterId.value == LocalContentIdentity.ADAPTER_ID) {
                        snapshot.source.connectionId == connectionId
                    } else {
                        snapshot.source.key.accountIdentity == accountIdentity
                    } &&
                    snapshot.source.key.nativeBookId.value in removedNativeBookIds &&
                    snapshot.status.presence != SourcePresence.Removed
            }
            .forEach { snapshot ->
                snapshotsDatabase.saveSnapshot(
                    snapshot.copy(
                        status = SourceSnapshotStatus(
                            observedAt = observedAt,
                            presence = SourcePresence.Removed,
                            isAuthoritative = true,
                            revision = snapshot.status.revision,
                        ),
                    ),
                )
            }
    }

    private suspend fun markDisconnectedSnapshotsUnknown(
        profileId: LibraryProfileId,
        activeConnections: Set<String>,
    ) {
        val observedAt = Clock.System.now()
        snapshotsDatabase.getSnapshots(profileId)
            .filter { snapshot ->
                val source = snapshot.source
                val connectionId = source.connectionId?.value ?: return@filter false
                connectionId !in activeConnections &&
                    snapshot.status.presence != SourcePresence.Removed
            }
            .forEach { snapshot ->
                snapshotsDatabase.saveSnapshot(
                    snapshot.copy(
                        status = SourceSnapshotStatus(
                            observedAt = observedAt,
                            presence = SourcePresence.Unknown,
                            isAuthoritative = false,
                            revision = snapshot.status.revision,
                        ),
                    ),
                )
            }
    }

    private suspend fun markConnectionSnapshotsUnknown(
        profileId: LibraryProfileId,
        source: SourceRepository,
        observedAt: kotlin.time.Instant,
    ) {
        snapshotsDatabase.getSnapshots(profileId)
            .filter { snapshot ->
                snapshot.source.key.adapterId == source.adapterId &&
                    snapshot.source.connectionId?.value == source.repository.serverId &&
                    snapshot.status.presence != SourcePresence.Removed
            }
            .forEach { snapshot ->
                snapshotsDatabase.saveSnapshot(
                    snapshot.copy(
                        status = SourceSnapshotStatus(
                            observedAt = observedAt,
                            presence = SourcePresence.Unknown,
                            isAuthoritative = false,
                            revision = snapshot.status.revision,
                        ),
                    ),
                )
            }
    }

    private data class SourceRepository(
        val repository: ServerBooksRepository,
        val adapterId: LibraryAdapterId,
        val adapter: ServerBookSourceAdapter,
    )

    private data class SourceBooksEmission(
        val source: SourceRepository,
        val result: AppResult<ServerBookListing>,
    )

    private fun LibraryGroupDecisionPayload.toOutboxEntry(): SyncOutboxEntry {
        return SyncOutboxEntry.new(
            entityType = SyncOutboxEntry.ENTITY_TYPE_LIBRARY_GROUP_DECISION,
            entityId = decisionId,
            operation = SyncOutboxEntry.OPERATION_UPSERT,
            payload = LibraryGroupDecisionCodec.encodeLocal(this),
        ).copy(
            mutationId = decisionId,
            createdAt = createdAt,
        )
    }
}
