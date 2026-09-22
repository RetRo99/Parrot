package com.retro99.server.parrotcloud

import com.retro99.base.AppInitializer
import com.retro99.base.server.PARROT_CLOUD_SERVER_ID
import com.retro99.base.server.ServerType
import com.retro99.cloud.implementation.CloudConfiguration
import com.retro99.cloudaccount.domain.CloudProfileLinkRepository
import com.retro99.server.api.ServerRegistry
import com.retro99.user.api.UserRegistry
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.runBlocking
import org.koin.core.annotation.Provided
import org.koin.core.annotation.Single

@Single(binds = [AppInitializer::class])
class ParrotCloudServerRegistrar(
    @Provided private val serverRegistry: ServerRegistry,
    @Provided private val profileLinkRepository: CloudProfileLinkRepository,
    @Provided private val configuration: CloudConfiguration,
    @Provided private val userRegistry: UserRegistry,
) : AppInitializer {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    @OptIn(ExperimentalCoroutinesApi::class)
    override fun initialize() {
        runBlocking { ensureServerRegistered() }
        combine(
            observeActiveProfileLink(),
            serverRegistry.observeAllServers(),
        ) { link, servers ->
            link != null && servers.none { server -> server.id == PARROT_CLOUD_SERVER_ID }
        }
            .onEach { shouldRegister ->
                if (shouldRegister) ensureServerRegistered()
            }
            .launchIn(scope)
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    private fun observeActiveProfileLink() = userRegistry.observeActiveProfile()
        .flatMapLatest { profile ->
            profile?.let { profileLinkRepository.observeForLocalProfile(it.id) } ?: flowOf(null)
        }

    private suspend fun ensureServerRegistered() {
        val profile = userRegistry.getActiveProfile() ?: return
        if (profileLinkRepository.getForLocalProfile(profile.id) == null) return

        val current = serverRegistry.getServer(PARROT_CLOUD_SERVER_ID)
        when {
            current == null -> serverRegistry.addServerWithId(
                id = PARROT_CLOUD_SERVER_ID,
                name = "Parrot Cloud",
                type = ServerType.ParrotCloud,
                baseUrl = configuration.supabaseUrl,
            )

            current.name != "Parrot Cloud" ||
                current.type != ServerType.ParrotCloud ||
                current.baseUrl != configuration.supabaseUrl -> {
                serverRegistry.updateServer(
                    current.copy(
                        name = "Parrot Cloud",
                        type = ServerType.ParrotCloud,
                        baseUrl = configuration.supabaseUrl,
                    ),
                )
            }
        }
    }
}
