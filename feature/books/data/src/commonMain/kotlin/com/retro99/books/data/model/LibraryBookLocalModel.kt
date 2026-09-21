package com.retro99.books.data.model

import com.retro99.books.domain.model.BookDomainModel
import com.retro99.database.api.library.LibraryBookEntity
import com.retro99.database.api.library.LocalBookFileEntity

internal data class LibraryBookLocalModel(
    override val libraryBookId: String,
    override val contentHash: String?,
    override val contentHashAlgorithm: String?,
    override val title: String,
    override val author: String?,
    override val format: String,
    override val remoteRevision: Long? = null,
    override val deletedAt: String? = null,
) : LibraryBookEntity

internal data class LocalBookFileLocalModel(
    override val libraryBookId: String,
    override val importedBookUuid: String,
) : LocalBookFileEntity

internal fun BookDomainModel.LocalBook.toLibraryBookLocalModel(): LibraryBookLocalModel? {
    val hash = contentHash ?: return null
    return LibraryBookLocalModel(
        libraryBookId = hash,
        contentHash = hash,
        contentHashAlgorithm = contentHashAlgorithm,
        title = title,
        author = author,
        format = bookType.value,
    )
}
