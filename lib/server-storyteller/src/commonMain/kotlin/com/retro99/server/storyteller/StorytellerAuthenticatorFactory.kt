package com.retro99.server.storyteller

import com.retro99.server.api.ServerAuthenticator
import com.retro99.server.api.ServerAuthenticatorFactory
import com.retro99.server.api.ServerType
import org.koin.core.annotation.Provided
import org.koin.core.annotation.Single

/**
 * Factory for creating server-specific authenticators.
 * Supports Storyteller and Local servers.
 */
@Single(binds = [ServerAuthenticatorFactory::class])
class StorytellerAuthenticatorFactory(
    @Provided private val storytellerAuthenticator: StorytellerAuthenticator,
    @Provided private val authenticators: List<ServerAuthenticator>,
) : ServerAuthenticatorFactory {

    private val authenticatorMap: Map<ServerType, ServerAuthenticator> by lazy {
        authenticators.associateBy { it.serverType }
    }

    override fun create(serverType: ServerType): ServerAuthenticator {
        // First check the map for dynamically registered authenticators
        authenticatorMap[serverType]?.let { return it }

        // Fallback for known types
        return when (serverType) {
            ServerType.Storyteller -> storytellerAuthenticator
            ServerType.Audiobookshelf -> authenticatorMap[serverType]
                ?: throw IllegalArgumentException("Audiobookshelf authenticator not registered")
            ServerType.Local -> throw IllegalArgumentException("Local server type does not require authentication")
            ServerType.Opds -> throw IllegalArgumentException("OPDS does not use bearer authentication")
            ServerType.ParrotCloud -> throw IllegalArgumentException(
                "Parrot Cloud authentication is managed by the Cloud account provider",
            )
        }
    }
}
