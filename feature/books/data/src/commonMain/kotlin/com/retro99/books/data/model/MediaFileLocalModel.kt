package com.retro99.books.data.model

import com.retro99.books.domain.model.MediaFileDomainModel
import com.retro99.database.api.books.MediaFileEntity

data class MediaFileLocalModel(
    override val uuid: String,
    override val bookUuid: String,
    override val type: String,
    override val filepath: String?,
    override val missing: Int?,
    override val size: Long?,
    override val createdAt: String?,
    override val updatedAt: String?,
) : MediaFileEntity

fun MediaFileLocalModel.toDomain(): MediaFileDomainModel {
    return MediaFileDomainModel(
        uuid = uuid,
        filepath = filepath,
        missing = missing,
        size = size,
        createdAt = createdAt,
        updatedAt = updatedAt,
    )
}

fun MediaFileDomainModel.toLocal(bookUuid: String, type: String): MediaFileLocalModel {
    return MediaFileLocalModel(
        uuid = uuid,
        bookUuid = bookUuid,
        type = type,
        filepath = filepath,
        missing = missing,
        size = size,
        createdAt = createdAt,
        updatedAt = updatedAt,
    )
}

