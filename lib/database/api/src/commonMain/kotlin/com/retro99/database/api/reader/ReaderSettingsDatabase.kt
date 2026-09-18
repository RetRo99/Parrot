package com.retro99.database.api.reader

import kotlinx.coroutines.flow.Flow

interface ReaderSettingsDatabase {

    suspend fun getAll(): List<ReaderSettingsEntity>

    fun observeAll(): Flow<List<ReaderSettingsEntity>>

    suspend fun upsertSettings(settings: List<ReaderSettingsEntity>)

    suspend fun upsertSettingsWithMutations(mutations: List<ReaderSettingsMutation>)
}
