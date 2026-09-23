package com.retro99.books.data.model

import com.retro99.base.server.LOCAL_SERVER_ID
import com.retro99.base.server.ServerType
import com.retro99.books.domain.model.BookDomainModel
import com.retro99.books.domain.model.BookType
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
    override val contentHash: String?,
    override val contentHashAlgorithm: String?,
    override val importedAt: String,
    override val lastOpenedAt: String?,
    override val bookType: String,
    override val publicationDate: String?,
    override val origin: String = "import",
    override val cloudBookFileId: String? = null,
) : ImportedBookEntity

fun ImportedBookEntity.toDomainModel() = BookDomainModel.LocalBook(
    uuid = uuid,
    serverId = LOCAL_SERVER_ID,
    serverType = ServerType.Local,
    title = title,
    author = author,
    description = description,
    coverUrl = coverPath?.let { "file://$it" },
    filePath = filePath,
    fileSize = fileSize,
    contentHash = contentHash,
    contentHashAlgorithm = contentHashAlgorithm,
    importedAt = importedAt,
    lastOpenedAt = lastOpenedAt,
    bookType = BookType.fromValue(bookType),
    publicationDate = publicationDate,
    origin = origin,
    cloudBookFileId = cloudBookFileId,
)

fun BookDomainModel.LocalBook.toLocalModel() = ImportedBookLocalModel(
    uuid = uuid,
    title = title,
    author = author,
    description = description,
    coverPath = coverUrl?.removePrefix("file://"),
    filePath = filePath,
    fileSize = fileSize,
    contentHash = contentHash,
    contentHashAlgorithm = contentHashAlgorithm,
    importedAt = importedAt,
    lastOpenedAt = lastOpenedAt,
    bookType = bookType.value,
    publicationDate = publicationDate,
    origin = origin,
    cloudBookFileId = cloudBookFileId,
)
