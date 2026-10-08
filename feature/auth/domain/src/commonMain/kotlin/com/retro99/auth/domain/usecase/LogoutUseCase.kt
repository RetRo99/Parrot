package com.retro99.auth.domain.usecase

import com.github.michaelbull.result.Ok
import com.retro99.base.result.CompletableResult
import com.retro99.base.server.ServerType
import com.retro99.database.api.DatabaseCleaner
import com.retro99.server.api.ServerRegistry
import org.koin.core.annotation.Factory
import org.koin.core.annotation.Provided

@Factory
class LogoutUseCase(
    @Provided private val serverRegistry: ServerRegistry,
    @Provided private val databaseCleaner: DatabaseCleaner,
) {
    /**
     * Logout from a specific server.
     * @param serverId The server to logout from.
     */
    suspend operator fun invoke(serverId: String): CompletableResult {
        serverRegistry.clearCredentials(serverId)
        // TODO: Clear only data for this server when supported
        // databaseCleaner.clearDataForServer(serverId)
        return Ok(Unit)
    }

    /**
     * Logout from all remote servers (excludes local server). A catalogue stays registered;
     * one that had account details loses them, its unfinished downloads and its saved pages.
     */
    suspend fun logoutAll(): CompletableResult {
        // Get all servers and clear credentials only for non-local servers
        val servers = serverRegistry.getAllServers()
        servers
            .filter { it.type != ServerType.Local }
            .forEach { server ->
                serverRegistry.clearCredentials(server.id)
            }

        // The same for every profile, with or without a catalogue. The cleaner does not know
        // where a library book came from: a catalogue download and a picked file lose the
        // same things. It leaves the catalogue tables and the import journal alone.
        databaseCleaner.clearAllData()
        return Ok(Unit)
    }
}
