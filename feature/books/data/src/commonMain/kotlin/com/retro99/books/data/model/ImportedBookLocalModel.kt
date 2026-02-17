package com.retro99.books.data.model

import com.retro99.books.domain.model.BookDomainModel
import com.retro99.database.api.importedbooks.ImportedBookEntity

/**
 * Local model for imported books.
 */
data class ImportedBookLocalModel(
    override val uuid: String,
    override val title: String,
    override val author: String?,
    override val description: String?,
    override val coverPath: String?,
    override val filePath: String,
    override val fileSize: Long,
    override val importedAt: String,
    override val lastOpenedAt: String?,
) : ImportedBookEntity

fun ImportedBookEntity.toDomainModel() = BookDomainModel.LocalBook(
    uuid = uuid,
    title = title,
    author = author,
    description = description,
    coverUrl = coverPath?.let { "file://$it" },
    filePath = filePath,
    fileSize = fileSize,
    importedAt = importedAt,
    lastOpenedAt = lastOpenedAt,
)

fun BookDomainModel.LocalBook.toLocalModel() = ImportedBookLocalModel(
    uuid = uuid,
    title = title,
    author = author,
    description = description,
    coverPath = coverUrl?.removePrefix("file://"),
    filePath = filePath,
    fileSize = fileSize,
    importedAt = importedAt,
    lastOpenedAt = lastOpenedAt,
)

