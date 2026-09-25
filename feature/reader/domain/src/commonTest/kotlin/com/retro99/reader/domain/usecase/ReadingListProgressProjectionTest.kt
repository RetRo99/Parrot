package com.retro99.reader.domain.usecase

import com.retro99.books.domain.model.BookMemberProgressDomainModel
import com.retro99.books.domain.model.BookProgressInfoDomainModel
import com.retro99.books.domain.model.UnifiedBookProgressSource
import com.retro99.server.api.ServerPosition
import com.retro99.server.api.library.LibraryAdapterId
import com.retro99.server.api.library.LibraryProfileId
import com.retro99.server.api.library.NativeBookId
import com.retro99.server.api.library.SourceAccountIdentity
import com.retro99.server.api.library.SourceBookKey
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ReadingListProgressProjectionTest {
    @Test
    fun preferredMemberWinsForItsMediaEvenWhenAnotherMemberHasMoreProgress() {
        // Given
        val preferredEbookKey = sourceKey("preferred-ebook")
        val preferredEbook = memberProgress(
            sourceKey = preferredEbookKey,
            bookUuid = "preferred-ebook",
            mediaType = EBOOK,
            progression = 0.21,
            updatedAt = "2026-02-02T10:00:00Z",
        )
        val higherProgressEbook = memberProgress(
            sourceKey = sourceKey("higher-progress-ebook"),
            bookUuid = "higher-progress-ebook",
            mediaType = EBOOK,
            progression = 0.97,
            updatedAt = "2026-03-01T10:00:00Z",
        )
        val preferredAudiobook = memberProgress(
            sourceKey = sourceKey("preferred-audiobook"),
            bookUuid = "preferred-audiobook",
            mediaType = AUDIOBOOK,
            progression = 0.82,
            updatedAt = "2026-01-01T10:00:00Z",
            isAudiobookCached = true,
        )

        // When
        val result = projectReadingListProgress(
            fallbackBookUuid = "display-book",
            preferredMediaSourceKeys = mapOf(
                EBOOK to preferredEbookKey,
                AUDIOBOOK to requireNotNull(preferredAudiobook.sourceKey),
            ),
            memberProgress = listOf(
                preferredEbook,
                higherProgressEbook,
                preferredAudiobook,
            ),
        )

        // Then
        val projected = requireNotNull(result)
        assertEquals("preferred-ebook", projected.bookUuid)
        assertEquals(0.21, projected.displayProgression)
        assertTrue(projected.isAudiobookCached)
    }

    @Test
    fun fallbackUsesMostRecentlySavedPositionInsteadOfMaximumProgress() {
        // Given
        val nearlyCompleteOlderPosition = memberProgress(
            sourceKey = sourceKey("older-nearly-complete"),
            bookUuid = "older-nearly-complete",
            mediaType = EBOOK,
            progression = 0.99,
            updatedAt = "2026-01-01T10:00:00Z",
        )
        val recentPosition = memberProgress(
            sourceKey = sourceKey("recent-position"),
            bookUuid = "recent-position",
            mediaType = EBOOK,
            progression = 0.12,
            updatedAt = "2026-02-01T10:00:00Z",
        )

        // When
        val result = projectReadingListProgress(
            fallbackBookUuid = "display-book",
            preferredMediaSourceKeys = emptyMap(),
            memberProgress = listOf(nearlyCompleteOlderPosition, recentPosition),
        )

        // Then
        val projected = requireNotNull(result)
        assertEquals("recent-position", projected.bookUuid)
        assertEquals(0.12, projected.displayProgression)
    }

    @Test
    fun ebookAndAudiobookProgressRemainSeparateWhileCacheStateIsGroupWide() {
        // Given
        val ebookSourceKey = sourceKey("ebook-source")
        val audiobookSourceKey = sourceKey("audiobook-source")
        val ebookProgress = memberProgress(
            sourceKey = ebookSourceKey,
            bookUuid = "ebook-source",
            mediaType = EBOOK,
            progression = 0.35,
            updatedAt = "2026-02-01T10:00:00Z",
        )
        val audiobookProgress = memberProgress(
            sourceKey = audiobookSourceKey,
            bookUuid = "audiobook-source",
            mediaType = AUDIOBOOK,
            progression = 0.91,
            updatedAt = "2026-01-01T10:00:00Z",
            isAudiobookCached = true,
        )

        // When
        val result = projectReadingListProgress(
            fallbackBookUuid = "display-book",
            preferredMediaSourceKeys = mapOf(
                EBOOK to ebookSourceKey,
                AUDIOBOOK to audiobookSourceKey,
            ),
            memberProgress = listOf(ebookProgress, audiobookProgress),
        )

        // Then
        val projected = requireNotNull(result)
        assertEquals("ebook-source", projected.bookUuid)
        assertEquals(0.35, projected.displayProgression)
        assertTrue(projected.isAudiobookCached)
        assertTrue(!projected.isEbookCached)
        assertEquals(0.35, ebookProgress.progressInfoByMediaType[EBOOK]?.displayProgression)
        assertEquals(
            0.91,
            audiobookProgress.progressInfoByMediaType[AUDIOBOOK]?.displayProgression,
        )
    }

    @Test
    fun audioPositionDoesNotBecomeEbookProgressForAMixedMediaSource() {
        // Given
        val audioPosition = position(audioTimestampMs = 90_000L, totalDurationMs = 300_000L)
        val mixedMediaTypes = setOf(EBOOK, AUDIOBOOK)

        // Then
        assertTrue(!audioPosition.supportsMediaType(EBOOK, mixedMediaTypes))
        assertTrue(audioPosition.supportsMediaType(AUDIOBOOK, mixedMediaTypes))
    }

    @Test
    fun readaloudPositionWithTextAndAudioStaysOutOfEbookAndAudiobookChoices() {
        // Given
        val readaloudPosition = position(
            locatorHref = "chapter-1.xhtml",
            audioTimestampMs = 90_000L,
            totalDurationMs = 300_000L,
        )
        val mediaTypes = setOf(EBOOK, AUDIOBOOK, READALOUD)

        // Then
        assertTrue(!readaloudPosition.supportsMediaType(EBOOK, mediaTypes))
        assertTrue(!readaloudPosition.supportsMediaType(AUDIOBOOK, mediaTypes))
        assertTrue(readaloudPosition.supportsMediaType(READALOUD, mediaTypes))
    }

    @Test
    fun cloudReplicaReusesOnlyTheLatestPositionForItsExactSharedLibraryBookId() {
        // Given
        val sharedLibraryBookId = "sha-256-v1:shared-content"
        val localSource = progressSource(
            adapterId = "local",
            serverId = "local-connection",
            bookUuid = "local-copy",
            libraryBookId = sharedLibraryBookId,
        )
        val cloudSource = progressSource(
            adapterId = "parrot-cloud",
            serverId = "cloud-connection",
            bookUuid = "cloud-copy",
            libraryBookId = sharedLibraryBookId,
        )
        val positions = listOf(
            position(
                bookUuid = localSource.bookUuid,
                serverId = "local-connection",
                libraryBookId = sharedLibraryBookId,
                remoteRevision = 3L,
                totalProgression = 0.2,
                updatedAt = "2026-04-01T10:00:00Z",
            ),
            position(
                bookUuid = "old-cloud-copy",
                serverId = "cloud-connection",
                libraryBookId = sharedLibraryBookId,
                remoteRevision = 4L,
                totalProgression = 0.6,
                updatedAt = "2026-03-01T10:00:00Z",
            ),
            position(
                bookUuid = "newer-but-older-revision",
                serverId = "other-connection",
                libraryBookId = sharedLibraryBookId,
                remoteRevision = 2L,
                totalProgression = 0.95,
                updatedAt = "2026-05-01T10:00:00Z",
            ),
            position(
                bookUuid = "unrelated-copy",
                serverId = "other-connection",
                libraryBookId = "sha-256-v1:unrelated-content",
                remoteRevision = 99L,
                totalProgression = 0.99,
                updatedAt = "2026-06-01T10:00:00Z",
            ),
        )
        val positionsByLibraryBookId = latestPositionsByLibraryBookId(positions)

        // When
        val cloudPosition = resolveMemberLocalPosition(
            source = cloudSource,
            positions = positions,
            positionsByLibraryBookId = positionsByLibraryBookId,
            ambiguousBookUuids = emptySet(),
            ambiguousSourceScopes = emptySet(),
            mediaType = EBOOK,
        )

        // Then
        assertEquals(0.6, cloudPosition?.totalProgression)
        assertEquals(4L, cloudPosition?.remoteRevision)
        assertEquals(
            0.2,
            resolveMemberLocalPosition(
                source = localSource,
                positions = positions,
                positionsByLibraryBookId = positionsByLibraryBookId,
                ambiguousBookUuids = emptySet(),
                ambiguousSourceScopes = emptySet(),
                mediaType = EBOOK,
            )?.totalProgression,
        )
    }

    @Test
    fun collidingBookUuidsResolvePositionsByServerOrExactLibraryIdentity() {
        // Given
        val localLibraryBookId = "sha-256-v1:local-content"
        val cloudLibraryBookId = "sha-256-v1:cloud-content"
        val localSource = progressSource(
            adapterId = "local",
            serverId = "local-connection",
            bookUuid = "colliding-uuid",
            libraryBookId = localLibraryBookId,
        )
        val cloudSource = progressSource(
            adapterId = "parrot-cloud",
            serverId = "cloud-connection",
            bookUuid = "colliding-uuid",
            libraryBookId = cloudLibraryBookId,
        )
        val ambiguousBookUuids = setOf("colliding-uuid")
        val serverScopedPositions = listOf(
            position(
                bookUuid = "colliding-uuid",
                serverId = "local-connection",
                libraryBookId = localLibraryBookId,
                totalProgression = 0.25,
            ),
            position(
                bookUuid = "colliding-uuid",
                serverId = "cloud-connection",
                libraryBookId = cloudLibraryBookId,
                totalProgression = 0.75,
            ),
        )
        val unscopedPositions = serverScopedPositions.map { position ->
            position.copy(serverId = "")
        }

        // When
        val serverScopedLocal = resolveMemberLocalPosition(
            source = localSource,
            positions = serverScopedPositions,
            positionsByLibraryBookId = latestPositionsByLibraryBookId(serverScopedPositions),
            ambiguousBookUuids = ambiguousBookUuids,
            ambiguousSourceScopes = emptySet(),
            mediaType = EBOOK,
        )
        val serverScopedCloud = resolveMemberLocalPosition(
            source = cloudSource,
            positions = serverScopedPositions,
            positionsByLibraryBookId = latestPositionsByLibraryBookId(serverScopedPositions),
            ambiguousBookUuids = ambiguousBookUuids,
            ambiguousSourceScopes = emptySet(),
            mediaType = EBOOK,
        )
        val unscopedLocal = resolveMemberLocalPosition(
            source = localSource,
            positions = unscopedPositions,
            positionsByLibraryBookId = latestPositionsByLibraryBookId(unscopedPositions),
            ambiguousBookUuids = ambiguousBookUuids,
            ambiguousSourceScopes = emptySet(),
            mediaType = EBOOK,
        )
        val unscopedCloud = resolveMemberLocalPosition(
            source = cloudSource,
            positions = unscopedPositions,
            positionsByLibraryBookId = latestPositionsByLibraryBookId(unscopedPositions),
            ambiguousBookUuids = ambiguousBookUuids,
            ambiguousSourceScopes = emptySet(),
            mediaType = EBOOK,
        )

        // Then
        assertEquals(0.25, serverScopedLocal?.totalProgression)
        assertEquals(0.75, serverScopedCloud?.totalProgression)
        assertEquals(0.25, unscopedLocal?.totalProgression)
        assertEquals(0.75, unscopedCloud?.totalProgression)
    }

    @Test
    fun collidingSourceKeysWithSameServerAndUuidRequireMatchingLibraryIdentity() {
        // Given
        val localLibraryBookId = "sha-256-v1:local-copy"
        val cloudLibraryBookId = "sha-256-v1:cloud-copy"
        val localSource = progressSource(
            adapterId = "local",
            serverId = "shared-connection-id",
            bookUuid = "same-uuid",
            libraryBookId = localLibraryBookId,
        )
        val cloudSource = progressSource(
            adapterId = "parrot-cloud",
            serverId = "shared-connection-id",
            bookUuid = "same-uuid",
            libraryBookId = cloudLibraryBookId,
        )
        val positions = listOf(
            position(
                bookUuid = "same-uuid",
                serverId = "shared-connection-id",
                libraryBookId = localLibraryBookId,
                totalProgression = 0.2,
            ),
            position(
                bookUuid = "same-uuid",
                serverId = "shared-connection-id",
                libraryBookId = cloudLibraryBookId,
                totalProgression = 0.8,
            ),
        )
        val positionsByLibraryBookId = latestPositionsByLibraryBookId(positions)
        val ambiguousBookUuids = setOf("same-uuid")
        val ambiguousSourceScopes = setOf("same-uuid" to "shared-connection-id")

        // When
        val localPosition = resolveMemberLocalPosition(
            source = localSource,
            positions = positions,
            positionsByLibraryBookId = positionsByLibraryBookId,
            ambiguousBookUuids = ambiguousBookUuids,
            ambiguousSourceScopes = ambiguousSourceScopes,
            mediaType = EBOOK,
        )
        val cloudPosition = resolveMemberLocalPosition(
            source = cloudSource,
            positions = positions,
            positionsByLibraryBookId = positionsByLibraryBookId,
            ambiguousBookUuids = ambiguousBookUuids,
            ambiguousSourceScopes = ambiguousSourceScopes,
            mediaType = EBOOK,
        )

        // Then
        assertEquals(0.2, localPosition?.totalProgression)
        assertEquals(0.8, cloudPosition?.totalProgression)
    }

    private fun memberProgress(
        sourceKey: SourceBookKey,
        bookUuid: String,
        mediaType: String,
        progression: Double,
        updatedAt: String,
        isEbookCached: Boolean = false,
        isAudiobookCached: Boolean = false,
        isReadaloudCached: Boolean = false,
    ) = BookMemberProgressDomainModel(
        sourceKey = sourceKey,
        serverId = "server-${sourceKey.nativeBookId.value}",
        bookUuid = bookUuid,
        mediaTypes = setOf(mediaType),
        progressInfoByMediaType = mapOf(
            mediaType to BookProgressInfoDomainModel(
                bookUuid = bookUuid,
                localProgression = progression,
                remoteProgression = null,
                isEbookCached = isEbookCached,
                isAudiobookCached = isAudiobookCached,
                isReadaloudCached = isReadaloudCached,
            ),
        ),
        savedPositionUpdatedAtByMediaType = mapOf(mediaType to updatedAt),
    )

    private fun progressSource(
        adapterId: String,
        serverId: String,
        bookUuid: String,
        libraryBookId: String,
    ) = UnifiedBookProgressSource(
        sourceKey = sourceKey(bookUuid, adapterId),
        serverId = serverId,
        bookUuid = bookUuid,
        libraryBookId = libraryBookId,
        mediaTypes = setOf(EBOOK),
    )

    private fun sourceKey(
        nativeBookId: String,
        adapterId: String = "storyteller",
    ) = SourceBookKey(
        profileId = LibraryProfileId("profile-1"),
        adapterId = LibraryAdapterId(adapterId),
        accountIdentity = SourceAccountIdentity.Portable(
            backendId = adapterId,
            accountId = "account-1",
        ),
        nativeBookId = NativeBookId(nativeBookId),
    )

    private fun position(
        bookUuid: String = "book-uuid",
        serverId: String = "server-id",
        libraryBookId: String? = null,
        locatorHref: String? = null,
        audioTimestampMs: Long? = null,
        totalDurationMs: Long? = null,
        remoteRevision: Long? = null,
        totalProgression: Double? = 0.25,
        updatedAt: String = "2026-01-01T00:00:00Z",
    ) = ServerPosition(
        bookUuid = bookUuid,
        serverId = serverId,
        libraryBookId = libraryBookId,
        timestamp = 1L,
        createdAt = "2026-01-01T00:00:00Z",
        updatedAt = updatedAt,
        locatorHref = locatorHref,
        locatorType = locatorHref?.let { "application/epub+zip" },
        locatorTitle = null,
        locatorTarget = null,
        audioTimestampMs = audioTimestampMs,
        chapterIndex = null,
        progression = 0.25,
        totalChapters = null,
        totalDurationMs = totalDurationMs,
        totalProgression = totalProgression,
        position = null,
        remoteRevision = remoteRevision,
    )

    private companion object {
        const val EBOOK = "ebook"
        const val AUDIOBOOK = "audiobook"
        const val READALOUD = "readaloud"
    }
}
