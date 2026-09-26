package com.retro99.database.implementation.dao.books

import com.retro99.database.api.books.MediaFileEntity

/**
 * SQLDelight entity for media_files table.
 */
data class MediaFileSqlDelightEntity(
    override val uuid: String,
    override val bookUuid: String,
    override val type: String,
    override val filepath: String?,
    override val missing: Int?,
    override val size: Long?,
    override val createdAt: String?,
    override val updatedAt: String?,
) : MediaFileEntity

