package com.retro99.reader.domain.translate

import com.retro99.books.domain.model.BookType
import com.retro99.books.domain.model.links.CopySource
import com.retro99.books.domain.model.links.LinkedCopy
import com.retro99.database.api.library.DeviceFilesDatabase
import com.retro99.reader.domain.ReaderSettingsRepository
import com.retro99.sync.domain.ProgressKind
import org.koin.core.annotation.Factory
import org.koin.core.annotation.Provided

/** A copy's EPUB on this device. */
data class CopyFile(
    val path: String,
    val isReadaloud: Boolean,
    val contentHash: String?,
)

/** Finds a linked copy's file on this device, if it has one. */
interface CopyFileLocator {
    suspend fun locate(copy: LinkedCopy): CopyFile?
}

/**
 * Library copies through `device_files`; Storyteller and Audiobookshelf copies through the
 * reader's download cache. A read-aloud is preferred, because it also carries SMIL timing.
 */
@Factory(binds = [CopyFileLocator::class])
class DeviceCopyFileLocator(
    @Provided private val deviceFilesDatabase: DeviceFilesDatabase,
    @Provided private val readerSettingsRepository: ReaderSettingsRepository,
) : CopyFileLocator {

    override suspend fun locate(copy: LinkedCopy): CopyFile? = when (copy.key.source) {
        CopySource.Library -> {
            val readaloud = deviceFilesDatabase.getDeviceFile(copy.uuid, BookType.READALOUD.value)
            val file = readaloud
                ?: deviceFilesDatabase.getDeviceFile(copy.uuid, BookType.EBOOK.value)
            file?.let { entity ->
                CopyFile(entity.filePath, isReadaloud = readaloud != null, entity.contentHash)
            }
        }
        CopySource.Storyteller, CopySource.Audiobookshelf -> {
            val readaloud = readerSettingsRepository
                .getCachedMediaPath(copy.uuid, BookType.READALOUD)
            val path = readaloud
                ?: readerSettingsRepository.getCachedMediaPath(copy.uuid, BookType.EBOOK)
            path?.let { filePath ->
                CopyFile(filePath, isReadaloud = readaloud != null, contentHash = null)
            }
        }
    }
}

/**
 * How a copy's position is kept: an Audiobookshelf audiobook keeps audio time; ebooks and
 * read-alouds keep a text locator.
 */
val LinkedCopy.progressKind: ProgressKind
    get() = if (key.source == CopySource.Audiobookshelf && hasAudiobook) {
        ProgressKind.AUDIO
    } else {
        ProgressKind.EBOOK
    }
