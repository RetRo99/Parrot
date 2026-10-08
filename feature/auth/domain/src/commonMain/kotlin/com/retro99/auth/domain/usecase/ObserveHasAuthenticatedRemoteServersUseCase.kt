package com.retro99.auth.domain.usecase

import com.retro99.base.server.ServerType
import com.retro99.server.api.ServerRegistry
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import org.koin.core.annotation.Factory
import org.koin.core.annotation.Provided

/**
 * Use case for observing whether there are any authenticated remote servers.
 * Local server is excluded since it doesn't require authentication.
 */
@Factory
class ObserveHasAuthenticatedRemoteServersUseCase(
    @Provided private val serverRegistry: ServerRegistry,
) {
    /**
     * Returns a Flow that emits true if there are any authenticated remote servers,
     * false otherwise. Local server is excluded from this check.
     */
    operator fun invoke(): Flow<Boolean> {
        return serverRegistry.observeAuthenticatedServers()
            .map { servers ->
                 servers.any { it.type != ServerType.Local && it.type != ServerType.Opds }
            }
    }
}
