package com.retro99.database.api.library

import kotlinx.coroutines.flow.Flow

interface DeviceFilesDatabase {
    fun observeAllDeviceFiles(): Flow<List<DeviceFileEntity>>

    suspend fun getDeviceFiles(libraryBookId: String): List<DeviceFileEntity>

    suspend fun getDeviceFile(libraryBookId: String, mediaType: String): DeviceFileEntity?

    suspend fun findByContentHash(algorithm: String, hash: String): DeviceFileEntity?

    suspend fun upsertDeviceFile(file: DeviceFileEntity)

    suspend fun deleteDeviceFile(libraryBookId: String, mediaType: String)

    suspend fun setOriginForBook(libraryBookId: String, origin: String)
}
