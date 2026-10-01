package com.retro99.books.domain.model

import com.retro99.server.api.MediaResource
import com.retro99.server.api.RemoteFileAvailability
import com.retro99.server.api.ServerType
import kotlinx.serialization.Serializable

/** Where a book lives, as shown on its card. */
// @Serializable because BookUiModel (which carries it) is @Serializable.
@Serializable
enum class BookHome { ThisDevice, ParrotCloud, Storyteller, Audiobookshelf }

private val PARROT_HOME_STATES = setOf(
    RemoteFileAvailability.Available,
    RemoteFileAvailability.UploadPending,
    RemoteFileAvailability.Uploading,
)

fun List<MediaResource>.hasDeviceCopy(): Boolean =
    any { resource -> resource.localPath != null }

fun List<MediaResource>.isInParrotCloud(): Boolean =
    any { resource -> resource.remoteAvailability in PARROT_HOME_STATES }

val BookDomainModel.home: BookHome
    get() = when (this) {
        is BookDomainModel.LibraryBook ->
            if (mediaResources.isInParrotCloud()) BookHome.ParrotCloud else BookHome.ThisDevice
        is BookDomainModel.StorytellerBook ->
            if (serverType == ServerType.Audiobookshelf) {
                BookHome.Audiobookshelf
            } else {
                BookHome.Storyteller
            }
    }
