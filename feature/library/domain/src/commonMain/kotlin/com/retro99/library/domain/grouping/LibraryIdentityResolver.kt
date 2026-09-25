package com.retro99.library.domain.grouping

import com.retro99.server.api.library.CertifiedIdentityKind
import com.retro99.server.api.library.FingerprintScope
import com.retro99.server.api.library.FingerprintVerification
import com.retro99.server.api.library.LibraryGroupId
import com.retro99.server.api.library.LibraryMembershipOrigin
import com.retro99.server.api.library.LibraryProfileId
import com.retro99.server.api.library.SourceAccountIdentity
import com.retro99.server.api.library.SourceBookKey
import com.retro99.server.api.library.SourceIdentityEvidence

data class LibraryMembershipAssignment(
    val source: SourceBookKey,
    val groupId: LibraryGroupId,
    val origin: LibraryMembershipOrigin = LibraryMembershipOrigin.Backfill,
    val revision: Long = 0L,
    val decisionId: String? = null,
)

data class LibraryIdentityEvidence(
    val evidenceId: String,
    val evidence: SourceIdentityEvidence,
) {
    init {
        require(evidenceId.isNotBlank())
    }
}

data class LibrarySourceSeparation(
    val first: SourceBookKey,
    val second: SourceBookKey,
) {
    init {
        require(first.profileId == second.profileId)
        require(first != second)
    }

    fun separates(firstSource: SourceBookKey, secondSource: SourceBookKey): Boolean =
        (first == firstSource && second == secondSource) ||
            (first == secondSource && second == firstSource)
}

data class LibraryGroupJoin(
    val profileId: LibraryProfileId,
    val survivorGroupId: LibraryGroupId,
    val retiredGroupIds: Set<LibraryGroupId>,
    val sourceKeys: Set<SourceBookKey>,
    val evidenceIds: Set<String>,
)

data class BlockedLibraryGroupJoin(
    val firstSource: SourceBookKey,
    val secondSource: SourceBookKey,
    val evidenceIds: Set<String>,
    val separation: LibrarySourceSeparation,
)

data class LibraryGroupingPlan(
    val automaticJoins: List<LibraryGroupJoin>,
    val blockedJoins: List<BlockedLibraryGroupJoin>,
)

/** Pure deterministic resolver. Persistence and source-specific semantics stay outside. */
object LibraryIdentityResolver {
    fun resolve(
        memberships: List<LibraryMembershipAssignment>,
        separations: Set<LibrarySourceSeparation>,
        evidence: List<LibraryIdentityEvidence>,
    ): LibraryGroupingPlan {
        val groupForSource = memberships.associate { membership ->
            membership.source to ScopedGroupId(membership.source.profileId, membership.groupId)
        }
        require(groupForSource.size == memberships.size) {
            "A source membership can have only one current group"
        }

        val candidates = buildCandidates(evidence)
            .filter { candidate ->
                groupForSource[candidate.firstSource] != null &&
                    groupForSource[candidate.secondSource] != null &&
                    candidate.firstSource.profileId == candidate.secondSource.profileId
            }
            .sortedWith(
                compareBy<Candidate> { it.firstSortKey }
                    .thenBy { it.secondSortKey }
                    .thenBy { it.evidenceIds.sorted().joinToString("|") },
            )

        val groupMembers = memberships.groupBy { membership ->
            ScopedGroupId(membership.source.profileId, membership.groupId)
        }
            .mapValues { (_, group) -> group.map { member -> member.source }.toMutableSet() }
            .toMutableMap()
        val rootForGroup = memberships.associate { membership ->
            val groupId = ScopedGroupId(membership.source.profileId, membership.groupId)
            groupId to groupId
        }.toMutableMap()
        val originalGroupsForRoot = memberships.map { membership ->
            ScopedGroupId(membership.source.profileId, membership.groupId)
        }
            .distinct()
            .associateWith { groupId -> mutableSetOf(groupId) }
            .toMutableMap()
        val evidenceForRoot = memberships.map { membership ->
            ScopedGroupId(membership.source.profileId, membership.groupId)
        }
            .distinct()
            .associateWith { mutableSetOf<String>() }
            .toMutableMap()
        val blocked = mutableListOf<BlockedLibraryGroupJoin>()

        candidates.forEach { candidate ->
            val firstGroup = groupForSource.getValue(candidate.firstSource)
            val secondGroup = groupForSource.getValue(candidate.secondSource)
            val firstRoot = rootForGroup.getValue(firstGroup)
            val secondRoot = rootForGroup.getValue(secondGroup)
            if (firstRoot == secondRoot) return@forEach

            val firstMembers = groupMembers.getValue(firstRoot)
            val secondMembers = groupMembers.getValue(secondRoot)
            val conflict = separations.asSequence()
                .filter { separation ->
                    firstMembers.any { member -> member == separation.first || member == separation.second } &&
                        secondMembers.any { member -> member == separation.first || member == separation.second }
                }
                .firstOrNull { separation ->
                    val firstIsInFirst = separation.first in firstMembers
                    val secondIsInFirst = separation.second in firstMembers
                    firstIsInFirst != secondIsInFirst
                }
            if (conflict != null) {
                blocked += BlockedLibraryGroupJoin(
                    firstSource = candidate.firstSource,
                    secondSource = candidate.secondSource,
                    evidenceIds = candidate.evidenceIds,
                    separation = conflict,
                )
                return@forEach
            }

            val survivor = minOf(
                firstRoot,
                secondRoot,
                compareBy<ScopedGroupId> { id -> id.profileId.value }
                    .thenBy { id -> id.groupId.value },
            )
            val retired = if (survivor == firstRoot) secondRoot else firstRoot
            groupMembers.getValue(survivor).addAll(groupMembers.getValue(retired))
            groupMembers.remove(retired)
            originalGroupsForRoot.getValue(survivor).addAll(
                originalGroupsForRoot.getValue(retired),
            )
            originalGroupsForRoot.remove(retired)
            evidenceForRoot.getValue(survivor).addAll(evidenceForRoot.getValue(retired))
            evidenceForRoot.getValue(survivor).addAll(candidate.evidenceIds)
            evidenceForRoot.remove(retired)
            rootForGroup.keys.toList().forEach { groupId ->
                if (rootForGroup[groupId] == retired) rootForGroup[groupId] = survivor
            }
        }

        val joins = groupMembers.keys.mapNotNull { root ->
            val originalGroups = originalGroupsForRoot.getValue(root)
            if (originalGroups.size < 2) return@mapNotNull null
            LibraryGroupJoin(
                profileId = root.profileId,
                survivorGroupId = root.groupId,
                retiredGroupIds = originalGroups.map { group -> group.groupId }
                    .filterNot { groupId -> groupId == root.groupId }
                    .toSet(),
                sourceKeys = groupMembers.getValue(root).toSet(),
                evidenceIds = evidenceForRoot.getValue(root).toSet(),
            )
        }.sortedWith(
            compareBy<LibraryGroupJoin> { join -> join.profileId.value }
                .thenBy { join -> join.survivorGroupId.value },
        )
        return LibraryGroupingPlan(joins, blocked)
    }

    private fun buildCandidates(evidence: List<LibraryIdentityEvidence>): List<Candidate> {
        val result = mutableListOf<Candidate>()
        val matchingEvidence = mutableMapOf<IdentityToken, MutableMap<SourceBookKey, MutableSet<String>>>()
        evidence.forEach { item ->
            when (val identityEvidence = item.evidence) {
                is SourceIdentityEvidence.BookFingerprint -> {
                    if (
                        identityEvidence.algorithm != null &&
                        identityEvidence.scope != FingerprintScope.Unknown &&
                        identityEvidence.verification == FingerprintVerification.Verified
                    ) {
                        val token = IdentityToken.Fingerprint(
                            profileId = identityEvidence.book.profileId.value,
                            algorithm = checkNotNull(identityEvidence.algorithm),
                            hash = identityEvidence.hash,
                            scope = identityEvidence.scope,
                        )
                        addEvidenceSource(
                            matchingEvidence,
                            token,
                            identityEvidence.book,
                            item.evidenceId,
                        )
                    }
                }
                is SourceIdentityEvidence.FileFingerprint -> {
                    if (
                        identityEvidence.algorithm != null &&
                        identityEvidence.scope != FingerprintScope.Unknown &&
                        identityEvidence.verification == FingerprintVerification.Verified
                    ) {
                        val token = IdentityToken.Fingerprint(
                            profileId = identityEvidence.resource.book.profileId.value,
                            algorithm = checkNotNull(identityEvidence.algorithm),
                            hash = identityEvidence.hash,
                            scope = identityEvidence.scope,
                        )
                        addEvidenceSource(
                            matchingEvidence,
                            token,
                            identityEvidence.resource.book,
                            item.evidenceId,
                        )
                    }
                }
                is SourceIdentityEvidence.AdapterCertified -> {
                    val token = IdentityToken.Certified(
                        profileId = identityEvidence.resource.book.profileId.value,
                        namespace = identityEvidence.namespace,
                        identity = identityEvidence.identity,
                        kind = identityEvidence.kind,
                    )
                    addEvidenceSource(
                        matchingEvidence,
                        token,
                        identityEvidence.resource.book,
                        item.evidenceId,
                    )
                }
                is SourceIdentityEvidence.CompletedTransfer -> {
                    if (identityEvidence.source.book != identityEvidence.destination.book) {
                        result += Candidate.between(
                            identityEvidence.source.book,
                            identityEvidence.destination.book,
                            setOf(item.evidenceId),
                        )
                    }
                }
            }
        }
        matchingEvidence.values.forEach { sourceEvidence ->
            val sources = sourceEvidence.keys.sortedBy { source -> source.stableSortKey() }
            for (firstIndex in sources.indices) {
                for (secondIndex in firstIndex + 1 until sources.size) {
                    result += Candidate.between(
                        sources[firstIndex],
                        sources[secondIndex],
                        sourceEvidence.getValue(sources[firstIndex]) +
                            sourceEvidence.getValue(sources[secondIndex]),
                    )
                }
            }
        }
        return result.distinctBy { candidate ->
            listOf(
                candidate.firstSortKey,
                candidate.secondSortKey,
                candidate.evidenceIds.sorted().joinToString("|"),
            )
        }
    }

    private fun addEvidenceSource(
        evidence: MutableMap<IdentityToken, MutableMap<SourceBookKey, MutableSet<String>>>,
        token: IdentityToken,
        source: SourceBookKey,
        evidenceId: String,
    ) {
        evidence.getOrPut(token) { mutableMapOf() }
            .getOrPut(source) { mutableSetOf() }
            .add(evidenceId)
    }

    private sealed interface IdentityToken {
        data class Fingerprint(
            val profileId: String,
            val algorithm: String,
            val hash: String,
            val scope: FingerprintScope,
        ) : IdentityToken

        data class Certified(
            val profileId: String,
            val namespace: String,
            val identity: String,
            val kind: CertifiedIdentityKind,
        ) : IdentityToken
    }

    private data class ScopedGroupId(
        val profileId: LibraryProfileId,
        val groupId: LibraryGroupId,
    )

    private data class Candidate(
        val firstSource: SourceBookKey,
        val secondSource: SourceBookKey,
        val evidenceIds: Set<String>,
    ) {
        val firstSortKey: String = firstSource.stableSortKey()
        val secondSortKey: String = secondSource.stableSortKey()

        companion object {
            fun between(
                first: SourceBookKey,
                second: SourceBookKey,
                evidenceIds: Set<String>,
            ): Candidate = if (first.stableSortKey() <= second.stableSortKey()) {
                Candidate(first, second, evidenceIds)
            } else {
                Candidate(second, first, evidenceIds)
            }
        }
    }
}

private fun SourceBookKey.stableSortKey(): String {
    val accountFields = when (val account = accountIdentity) {
        is SourceAccountIdentity.Portable -> listOf("portable", account.backendId, account.accountId)
        is SourceAccountIdentity.Unresolved -> listOf("unresolved", account.connectionId.value)
    }
    return (listOf(profileId.value, adapterId.value) + accountFields + nativeBookId.value)
        .joinToString(separator = "|") { value -> "${value.length}:$value" }
}
