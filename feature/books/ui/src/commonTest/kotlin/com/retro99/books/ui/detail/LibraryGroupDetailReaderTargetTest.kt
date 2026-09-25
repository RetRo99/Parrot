package com.retro99.books.ui.detail

import com.retro99.books.domain.DeviceStorageAvailability
import com.retro99.books.domain.model.BookType
import com.retro99.library.domain.projection.LibraryReaderTarget
import com.retro99.server.api.library.DeviceStorageRef
import com.retro99.server.api.library.LibraryAdapterId
import com.retro99.server.api.library.LibraryProfileId
import com.retro99.server.api.library.NativeBookId
import com.retro99.server.api.library.ProgressOwnerRef
import com.retro99.server.api.library.SourceAccountIdentity
import com.retro99.server.api.library.SourceBookKey
import com.retro99.server.api.library.SourceBookRef
import com.retro99.server.api.library.SourceConnectionId
import com.retro99.server.api.library.SourceResourceRef
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlinx.coroutines.test.runTest

class LibraryGroupDetailReaderTargetTest {
    @Test
    fun staleDevicePathsBlockEverySupportedReaderTarget() = runTest {
        // Given
        val deviceStorageAvailability = DeviceStorageAvailability { _ -> false }
        val targets = BookType.entries.map { bookType -> readerTarget(bookType) }

        // When
        val launchableTargets = targets.map { target ->
            target.withAvailableDeviceStorage(deviceStorageAvailability)
        }

        // Then
        assertEquals(targets.size, launchableTargets.size)
        launchableTargets.forEach { target -> assertNull(target) }
    }

    @Test
    fun readerTargetCanLaunchWhenItsDevicePathExists() = runTest {
        // Given
        val target = readerTarget(BookType.EBOOK)
        val deviceStorageAvailability = DeviceStorageAvailability { storage ->
            storage == target.storage
        }

        // When
        val launchableTarget = target.withAvailableDeviceStorage(deviceStorageAvailability)

        // Then
        assertEquals(target, launchableTarget)
    }

    private fun readerTarget(bookType: BookType): LibraryReaderTarget {
        val connectionId = SourceConnectionId("local-installation")
        val adapterId = LibraryAdapterId("local")
        val sourceKey = SourceBookKey(
            profileId = LibraryProfileId("profile"),
            adapterId = adapterId,
            accountIdentity = SourceAccountIdentity.Unresolved(connectionId),
            nativeBookId = NativeBookId("book-${bookType.value}"),
        )
        val source = SourceBookRef(sourceKey, connectionId)
        val nativeResourceId = "resource-${bookType.value}"
        val progressNativeId = "progress-${bookType.value}"
        return LibraryReaderTarget(
            connectionId = connectionId.value,
            nativeBookId = sourceKey.nativeBookId.value,
            resource = SourceResourceRef(sourceKey, nativeResourceId),
            mediaType = bookType.value,
            title = "Book",
            storage = DeviceStorageRef("/books/${bookType.value}"),
            progressOwner = ProgressOwnerRef(
                adapterId = adapterId,
                source = source,
                nativeProgressId = progressNativeId,
            ),
        )
    }
}
