package com.retro99.reader.domain.fakes

import com.retro99.books.domain.model.links.LinkedCopy
import com.retro99.reader.domain.linked.LinkedCopies
import com.retro99.reader.domain.linked.LinkedCopiesSource
import com.retro99.reader.domain.linked.LinkedResumeDismissals
import com.retro99.reader.domain.translate.CopyContentCache
import com.retro99.reader.domain.translate.CopyFile
import com.retro99.reader.domain.translate.CopyFileLocator
import com.retro99.reader.domain.translate.TranslationCache
import com.retro99.reader.domain.usecase.TranslatePositionUseCase
import com.retro99.base.result.AppResult
import com.retro99.epub.api.EpubChapterText
import com.retro99.epub.api.EpubTextReader
import com.retro99.epub.api.ReadaloudTiming
import com.retro99.epub.api.ReadaloudTimingReader
import com.github.michaelbull.result.Err
import com.github.michaelbull.result.Ok
import com.retro99.base.result.AppError
import com.retro99.database.api.links.LinkedCopyWriteEntity
import com.retro99.database.api.links.LinkedCopyWritesDatabase

class FakeLinkedCopiesSource(private val copies: List<LinkedCopy>) : LinkedCopiesSource {
    override suspend fun linkedCopies(serverId: String, bookUuid: String): LinkedCopies? {
        val self = copies.firstOrNull { copy -> copy.uuid == bookUuid } ?: return null
        return LinkedCopies(self, copies - self)
    }
}

class FakeDismissals : LinkedResumeDismissals {
    val entries = mutableListOf<String>()

    override suspend fun isDismissed(entry: String): Boolean = entry in entries

    override suspend fun dismiss(entry: String) {
        entries += entry
    }
}

/** Files and their chapters, by copy uuid. Copies without an entry have no file here. */
class FakeCopyFiles(
    val chapters: MutableMap<String, List<EpubChapterText>> = mutableMapOf(),
    val timings: MutableMap<String, ReadaloudTiming> = mutableMapOf(),
) : CopyFileLocator, EpubTextReader, ReadaloudTimingReader {

    override suspend fun locate(copy: LinkedCopy): CopyFile? =
        if (copy.uuid in chapters) {
            CopyFile("/${copy.uuid}.epub", isReadaloud = copy.uuid in timings, contentHash = null)
        } else {
            null
        }

    override suspend fun readChapters(filePath: String): AppResult<List<EpubChapterText>> =
        chapters[uuidOf(filePath)]?.let { found -> Ok(found) }
            ?: Err(AppError.NotFoundError(filePath))

    override suspend fun readTiming(filePath: String): AppResult<ReadaloudTiming> =
        timings[uuidOf(filePath)]?.let { found -> Ok(found) }
            ?: Err(AppError.NotFoundError(filePath))

    private fun uuidOf(path: String) = path.removePrefix("/").removeSuffix(".epub")

    fun translateUseCase(positions: FakePositionDatabase) = TranslatePositionUseCase(
        fileLocator = this,
        contentCache = CopyContentCache(this, this),
        translationCache = TranslationCache(),
        positionDatabase = positions,
    )
}

class FakeLinkedCopyWrites : LinkedCopyWritesDatabase {
    val writes = mutableMapOf<String, LinkedCopyWriteEntity>()

    fun write(
        targetKey: String,
        bookUuid: String,
        marker: String? = null,
        totalProgression: Double? = null,
        writtenAt: String = kotlin.time.Clock.System.now().toString(),
    ) {
        writes[targetKey] = LinkedCopyWriteEntity(
            targetKey = targetKey,
            targetBookUuid = bookUuid,
            sourceKey = null,
            sourceObservedAt = null,
            writtenAt = writtenAt,
            marker = marker,
            locatorHref = null,
            progression = null,
            totalProgression = totalProgression,
            audioMs = null,
        )
    }

    override suspend fun replace(write: LinkedCopyWriteEntity, deleteWrittenBefore: String) {
        writes.values.removeAll { existing -> existing.writtenAt < deleteWrittenBefore }
        writes[write.targetKey] = write
    }

    override suspend fun getWrite(targetKey: String, notBefore: String): LinkedCopyWriteEntity? =
        writes[targetKey]?.takeIf { write -> write.writtenAt >= notBefore }

    override suspend fun getWriteForBook(
        bookUuid: String,
        notBefore: String,
    ): LinkedCopyWriteEntity? = writes.values
        .filter { write -> write.targetBookUuid == bookUuid && write.writtenAt >= notBefore }
        .maxByOrNull { write -> write.writtenAt }

    override suspend fun setMarker(targetKey: String, marker: String) {
        writes[targetKey]?.let { write -> writes[targetKey] = write.copy(marker = marker) }
    }
}
