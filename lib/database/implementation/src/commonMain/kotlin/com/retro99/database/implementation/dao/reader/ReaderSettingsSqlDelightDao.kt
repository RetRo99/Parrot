package com.retro99.database.implementation.dao.reader

import app.cash.sqldelight.coroutines.asFlow
import app.cash.sqldelight.coroutines.mapToList
import com.retro99.database.api.reader.ReaderSettingsEntity
import com.retro99.database.api.reader.ReaderSettingsMutation
import com.retro99.database.implementation.DatabaseManager
import com.retro99.database.implementation.Reader_settings
import com.retro99.database.implementation.dao.sync.enqueue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext

internal class ReaderSettingsSqlDelightDao(
    private val databaseManager: DatabaseManager,
) {
    private val database get() = databaseManager.getDatabase()
    private val queries get() = database.readerSettingsQueries
    private val syncOutboxQueries get() = database.syncOutboxQueries

    suspend fun getAll(): List<ReaderSettingsEntity> {
        return withContext(Dispatchers.IO) {
            queries.getAllReaderSettings().executeAsList().map { settings -> settings.toEntity() }
        }
    }

    fun observeAll(): Flow<List<ReaderSettingsEntity>> {
        return queries.getAllReaderSettings()
            .asFlow()
            .mapToList(Dispatchers.IO)
            .map { rows -> rows.map { settings -> settings.toEntity() } }
    }

    suspend fun upsertSettings(settings: List<ReaderSettingsEntity>) {
        withContext(Dispatchers.IO) {
            database.transaction {
                settings.forEach { entry -> upsertRow(entry) }
            }
        }
    }

    suspend fun upsertSettingsWithMutations(mutations: List<ReaderSettingsMutation>) {
        withContext(Dispatchers.IO) {
            database.transaction {
                mutations.forEach { mutation ->
                    upsertRow(mutation.settings)
                    mutation.outboxEntry?.let(syncOutboxQueries::enqueue)
                }
            }
        }
    }

    private fun upsertRow(settings: ReaderSettingsEntity) {
        queries.upsertReaderSettings(
            setting_key = settings.key,
            setting_value = settings.value,
            remote_revision = settings.remoteRevision,
            deleted_at = settings.deletedAt,
        )
    }

    private fun Reader_settings.toEntity(): ReaderSettingsEntity {
        return ReaderSettingsEntity(
            key = setting_key,
            value = setting_value,
            remoteRevision = remote_revision,
            deletedAt = deleted_at,
        )
    }
}
