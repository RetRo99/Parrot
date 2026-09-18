package com.retro99.database.implementation.dao.reader

import com.retro99.database.api.reader.ReaderSettingsDatabase
import com.retro99.database.api.reader.ReaderSettingsEntity
import com.retro99.database.api.reader.ReaderSettingsMutation
import kotlinx.coroutines.flow.Flow

internal class ReaderSettingsDatabaseImpl(
    private val sqlDelightDao: ReaderSettingsSqlDelightDao,
) : ReaderSettingsDatabase {

    override suspend fun getAll(): List<ReaderSettingsEntity> {
        return sqlDelightDao.getAll()
    }

    override fun observeAll(): Flow<List<ReaderSettingsEntity>> {
        return sqlDelightDao.observeAll()
    }

    override suspend fun upsertSettings(settings: List<ReaderSettingsEntity>) {
        sqlDelightDao.upsertSettings(settings)
    }

    override suspend fun upsertSettingsWithMutations(mutations: List<ReaderSettingsMutation>) {
        sqlDelightDao.upsertSettingsWithMutations(mutations)
    }
}
