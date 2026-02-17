package com.retro99.books.domain.model

/**
 * Domain model representing a locally imported book (EPUB file).
 */
data class ImportedBookDomainModel(
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

