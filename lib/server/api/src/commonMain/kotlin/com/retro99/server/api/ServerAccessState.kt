package com.retro99.server.api

import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.Serializable

/** Catalogue access is deliberately not a library login/session. */
@Serializable
sealed interface ServerAccessState {
    @Serializable data object Public : ServerAccessState
    @Serializable data class SignedIn(val name: String) : ServerAccessState
    @Serializable data object SignInNeeded : ServerAccessState
    @Serializable data object SignInUnsupported : ServerAccessState
    @Serializable data object TurnedOff : ServerAccessState
}

@Serializable
enum class CatalogueErrorKind {
    SignInNeeded, SignInUnsupported, Unreachable, Timeout, Tls, Forbidden,
    NotFound, RateLimited, ServerError, InvalidDocument, SecurityPolicy, TooLarge,
}

@Serializable
data class CatalogueLastCheck(
    val lastSuccessAt: Long? = null,
    val lastError: CatalogueErrorKind? = null,
    val lastErrorAt: Long? = null,
)

@Serializable
data class CatalogueAccessStatus(
    val access: ServerAccessState = ServerAccessState.Public,
    val lastCheck: CatalogueLastCheck = CatalogueLastCheck(),
    val rootAnswered401: Boolean = false,
)

/** Explicit profile keys also fence late responses after profile switches. No polling. */
interface CatalogueAccessStore {
    fun get(profileId: String, sourceId: String): CatalogueAccessStatus
    fun observe(profileId: String): Flow<Map<String, CatalogueAccessStatus>>
    suspend fun recordSuccess(profileId: String, sourceId: String, username: String?, at: Long, isRoot: Boolean = false)
    suspend fun recordFailure(profileId: String, sourceId: String, kind: CatalogueErrorKind, at: Long, rootAnswered401: Boolean)
    suspend fun remove(profileId: String, sourceId: String)
}
