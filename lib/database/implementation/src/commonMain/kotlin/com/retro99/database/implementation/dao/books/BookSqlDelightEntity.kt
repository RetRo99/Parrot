package com.retro99.database.implementation.dao.books

/**
 * SQLDelight entity for books table.
 */
data class BookSqlDelightEntity(
    val uuid: String,
    val serverId: String,
    val serverType: String?,
    val id: Long,
    val title: String,
    val subtitle: String?,
    val language: String?,
    val publicationDate: String?,
    val description: String?,
    val rating: Float?,
    val suffix: String?,
    val statusUuid: String?,
    val coverUrl: String?,
    val createdAt: String?,
    val updatedAt: String?,
    val isbn: String? = null,
    val asin: String? = null,
)
