package com.retro99.server.api

import kotlinx.coroutines.flow.Flow

data class CatalogueSourceStatus(val config: ServerConfig, val status: CatalogueAccessStatus)

interface CatalogueAccessProvider {
    fun observeSources(): Flow<List<CatalogueSourceStatus>>
}
