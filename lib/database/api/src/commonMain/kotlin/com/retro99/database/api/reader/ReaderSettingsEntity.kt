package com.retro99.database.api.reader

data class ReaderSettingsEntity(
    val key: String,
    val value: String,
    val remoteRevision: Long?,
    val deletedAt: String?,
)
