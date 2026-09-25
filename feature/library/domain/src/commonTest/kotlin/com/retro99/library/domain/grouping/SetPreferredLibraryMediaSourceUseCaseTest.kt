package com.retro99.library.domain.grouping

import com.retro99.server.api.library.LibraryAdapterId
import com.retro99.server.api.library.LibraryGroupId
import com.retro99.server.api.library.LibraryProfileId
import com.retro99.server.api.library.NativeBookId
import com.retro99.server.api.library.SourceAccountIdentity
import com.retro99.server.api.library.SourceBookKey
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking

class SetPreferredLibraryMediaSourceUseCaseTest {
    @Test
    fun normalizesMediaTypeAndReturnsWhetherPreferenceChanged() = runBlocking {
        // Given
        var receivedMediaType = ""
        val useCase = SetPreferredLibraryMediaSourceUseCase(
            repository = object : LibraryGroupPreferenceRepository {
                override suspend fun setPreferredMediaSource(
                    profileId: LibraryProfileId,
                    groupId: LibraryGroupId,
                    mediaType: String,
                    sourceKey: SourceBookKey,
                ): Boolean {
                    receivedMediaType = mediaType
                    return true
                }
            },
        )
        val profileId = LibraryProfileId("profile-a")
        val sourceKey = sourceKey(profileId)

        // When
        val changed = useCase(
            profileId = profileId,
            groupId = LibraryGroupId("group-a"),
            mediaType = "EBOOK",
            sourceKey = sourceKey,
        )

        // Then
        assertEquals("ebook", receivedMediaType)
        assertTrue(changed)
    }

    @Test
    fun rejectsCrossProfilePreference() = runBlocking {
        // Given
        val useCase = SetPreferredLibraryMediaSourceUseCase(
            repository = object : LibraryGroupPreferenceRepository {
                override suspend fun setPreferredMediaSource(
                    profileId: LibraryProfileId,
                    groupId: LibraryGroupId,
                    mediaType: String,
                    sourceKey: SourceBookKey,
                ): Boolean = error("Repository should not receive a cross-profile source")
            },
        )

        // When / Then
        assertFailsWith<IllegalArgumentException> {
            useCase(
                profileId = LibraryProfileId("profile-a"),
                groupId = LibraryGroupId("group-a"),
                mediaType = "ebook",
                sourceKey = sourceKey(LibraryProfileId("profile-b")),
            )
        }
        Unit
    }

    @Test
    fun rejectsBlankMediaType() = runBlocking {
        // Given
        val useCase = SetPreferredLibraryMediaSourceUseCase(
            repository = object : LibraryGroupPreferenceRepository {
                override suspend fun setPreferredMediaSource(
                    profileId: LibraryProfileId,
                    groupId: LibraryGroupId,
                    mediaType: String,
                    sourceKey: SourceBookKey,
                ): Boolean = error("Repository should not receive a blank media type")
            },
        )

        // When / Then
        assertFailsWith<IllegalArgumentException> {
            useCase(
                profileId = LibraryProfileId("profile-a"),
                groupId = LibraryGroupId("group-a"),
                mediaType = "  ",
                sourceKey = sourceKey(LibraryProfileId("profile-a")),
            )
        }
        Unit
    }

    private fun sourceKey(profileId: LibraryProfileId) = SourceBookKey(
        profileId = profileId,
        adapterId = LibraryAdapterId("test"),
        accountIdentity = SourceAccountIdentity.Portable("backend", "account"),
        nativeBookId = NativeBookId("book-a"),
    )
}
