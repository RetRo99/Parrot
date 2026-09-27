package com.retro99.login.data

import com.github.michaelbull.result.Err
import com.github.michaelbull.result.Ok
import com.retro99.base.result.AppError
import com.retro99.base.result.CompletableResult
import com.retro99.server.api.ServerConfig
import com.retro99.server.api.ServerCredentials
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlin.coroutines.cancellation.CancellationException

/** Persists a server only as authenticated after its credentials have been stored. */
internal suspend fun persistLoginCredentials(
    credentials: ServerCredentials,
    addServer: suspend () -> ServerConfig,
    saveCredentials: suspend (ServerCredentials) -> Unit,
    removeServer: suspend (String) -> Unit,
    existingServerId: String? = null,
): CompletableResult {
    var registeredServer: ServerConfig? = null
    return try {
        val serverId = existingServerId ?: addServer().also { registeredServer = it }.id
        saveCredentials(credentials.copy(serverId = serverId))
        Ok(Unit)
    } catch (cancellation: CancellationException) {
        registeredServer?.let { server ->
            withContext(NonCancellable) {
                runCatching { removeServer(server.id) }
            }
        }
        throw cancellation
    } catch (failure: Throwable) {
        val rollbackFailed = registeredServer?.let { server ->
            try {
                removeServer(server.id)
                false
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (_: Throwable) {
                true
            }
        } ?: false
        Err(
            AppError.DatabaseError(
                throwable = LoginPersistenceException(failure),
                table = if (rollbackFailed) "server_registry_rollback" else "server_registry",
            ),
        )
    }
}

private class LoginPersistenceException(cause: Throwable) :
    Exception("Login could not be saved on this device. Please retry.", cause)
