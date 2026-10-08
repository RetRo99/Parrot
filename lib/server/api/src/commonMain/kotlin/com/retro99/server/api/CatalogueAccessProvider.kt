package com.retro99.server.api

import kotlinx.coroutines.flow.Flow

interface CatalogueAccessProvider {
    fun observeAll(): Flow<Map<String, CatalogueAccessStatus>>
}
