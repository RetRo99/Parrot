package com.retro99.database.api.importedbooks

/**
 * Entity representing a locally imported book (EPUB file).
 */
interface ImportedBookEntity {
    val uuid: String
    val title: String
    val author: String?
    val description: String?
    val coverPath: String?
    val filePath: String
    val fileSize: Long
    val contentHash: String?
    val contentHashAlgorithm: String?
    val importedAt: String
    val lastOpenedAt: String?
    val bookType: String
    val publicationDate: String?
}
