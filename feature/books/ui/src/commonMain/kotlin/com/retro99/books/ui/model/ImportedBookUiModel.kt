package com.retro99.books.ui.model

import com.retro99.books.domain.model.BookDomainModel

data class ImportedBookUiModel(
    val uuid: String,
    val title: String,
    val author: String?,
    val description: String?,
    val coverPath: String?,
    val filePath: String,
    val fileSize: Long,
    val importedAt: String,
    val lastOpenedAt: String?,
)

fun BookDomainModel.LocalBook.toUiModel() = ImportedBookUiModel(
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

