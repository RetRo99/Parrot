package com.retro99.books.domain.model

data class SeriesDomainModel(
    val uuid: String,
    val name: String,
    val featured: Int?,
    val position: Double?,
    val createdAt: String?,
    val updatedAt: String?,
    val sources: List<SeriesSource> = emptyList(),
)
