package com.retro99.server.api

import com.retro99.base.result.AppResult
import kotlinx.coroutines.flow.Flow

interface ServerCatalogueRepository {
    val serverId: String
    suspend fun getRoot(): AppResult<CatalogueDocument>
    suspend fun getDocument(target: CatalogueTarget): AppResult<CatalogueDocument>
    suspend fun discoverSearch(document: CatalogueDocument): AppResult<CatalogueSearch?>
    suspend fun search(search: CatalogueSearch, query: CatalogueQuery): AppResult<CatalogueDocument>
}

interface ServerCatalogueRepositoryFactory : ServerSpecificFactory<ServerCatalogueRepository>
interface CatalogueRepositoryFactory {
    fun create(serverConfig: ServerConfig): ServerCatalogueRepository
}
interface CatalogueRepositoryProvider {
    fun observeRepositories(): Flow<List<ServerCatalogueRepository>>
    suspend fun getRepositories(): List<ServerCatalogueRepository>
    suspend fun getRepository(sourceId: String): ServerCatalogueRepository?
}

/** Opaque implementation-owned references. They must not be put in navigation state or logs. */
interface CatalogueTarget
interface CatalogueSearch

/** Checks the requested page using ephemeral details, without storing them or populating caches. */
interface CatalogueAccountVerifier {
    suspend fun checkAccount(target: CatalogueTarget?, account: OpdsAccountDetails): AppResult<CatalogueDocument>
    suspend fun checkSearchAccount(document: CatalogueDocument, query: CatalogueQuery, account: OpdsAccountDetails): AppResult<CatalogueDocument>
}
data class CatalogueQuery(val text: String, val fields: Map<String, String> = emptyMap()) {
    override fun toString() = "CatalogueQuery(redacted)"
}
