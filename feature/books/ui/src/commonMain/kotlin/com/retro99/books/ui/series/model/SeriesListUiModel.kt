package com.retro99.books.ui.series.model

import kotlinx.serialization.Serializable

@Serializable
data class SeriesListUiModel(
    val uuid: String,
    val name: String,
    val featured: Int?,
    val coverUrl: String?,
    val bookCount: Int,
    val finishedCount: Int = 0,
    val author: String? = null,
    val progress: Double = 0.0,
)
