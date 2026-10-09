package com.retro99.server.implementation

import com.retro99.base.server.ServerType
import com.retro99.server.api.ServerConfig
import com.retro99.server.api.ServerNetworkClient
import com.retro99.server.api.ServerNetworkClientFactory
import com.retro99.server.api.ServerNetworkClientProvider
import com.retro99.server.api.ServerRegistry
import org.koin.core.annotation.Provided
import org.koin.core.annotation.Single

@Single(binds = [ServerNetworkClientProvider::class])
class CompositeNetworkClientFactory(
    @Provided private val factories: List<ServerNetworkClientFactory>,
    @Provided private val serverRegistry: ServerRegistry,
) : ServerNetworkClientProvider {

    private val factoryMap: Map<ServerType, ServerNetworkClientFactory> by lazy {
        factories.associateBy { it.serverType }
    }

    override fun create(serverConfig: ServerConfig): ServerNetworkClient {
        val factory = factoryMap[serverConfig.type]
            ?: throw IllegalArgumentException(
                "No network client factory registered for server type: ${serverConfig.type}",
            )
        return factory.create(serverConfig)
    }

    override suspend fun createForServerId(serverId: String): ServerNetworkClient? {
        val serverConfig = serverRegistry.getServer(serverId) ?: return null
        if (serverConfig.type == ServerType.Opds) return null
        return create(serverConfig)
    }
}
