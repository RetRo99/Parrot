package com.retro99.books.domain.model

data class MediaFileDomainModel(
    val uuid: String,
    val filepath: String?,
    val missing: Int?,
    val size: Long? = null,
    val createdAt: String?,
    val updatedAt: String?,
)

