package com.retro99.server.api

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

data class CatalogueSourceStatus(val config: ServerConfig, val status: CatalogueAccessStatus)

interface CatalogueAccessProvider {
    fun observeSources(): Flow<List<CatalogueSourceStatus>>
    fun observeAll(): Flow<Map<String, CatalogueAccessStatus>> = observeSources().map { sources -> sources.associate { it.config.id to it.status } }
}
