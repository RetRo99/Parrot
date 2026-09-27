package com.retro99.login.data

import com.github.michaelbull.result.fold
import com.retro99.base.server.ServerType
import com.retro99.server.api.ServerConfig
import com.retro99.server.api.ServerCredentials
import kotlinx.coroutines.test.runTest
import kotlin.coroutines.cancellation.CancellationException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class LoginPersistenceTest {

    @Test
    fun failedCredentialWriteRollsBackRegistrationAndReturnsRecoverableError() = runTest {
        val removedIds = mutableListOf<String>()
        val failure = IllegalStateException("private preference detail")

        val result = persistLoginCredentials(
            credentials = credentials(),
            addServer = { serverConfig() },
            saveCredentials = { throw failure },
            removeServer = { removedIds += it },
        )

        assertEquals(listOf("server-id"), removedIds)
        val error = result.fold(
            success = { error("Expected persistence failure") },
            failure = { it },
        )
        val databaseError = assertIs<com.retro99.base.result.AppError.DatabaseError>(error)
        assertEquals("server_registry", databaseError.table)
        assertEquals("Login could not be saved on this device. Please retry.", databaseError.message)
    }

    @Test
    fun failedRollbackIsIdentifiedAsDegradedRecovery() = runTest {
        val result = persistLoginCredentials(
            credentials = credentials(),
            addServer = { serverConfig() },
            saveCredentials = { error("write failed") },
            removeServer = { error("rollback failed") },
        )

        val error = result.fold(
            success = { error("Expected persistence failure") },
            failure = { it },
        )
        val databaseError = assertIs<com.retro99.base.result.AppError.DatabaseError>(error)
        assertEquals("server_registry_rollback", databaseError.table)
    }

    @Test
    fun cancellationRollsBackAndIsRethrown() = runTest {
        val cancellation = CancellationException("cancelled")
        val removedIds = mutableListOf<String>()
        val caught = runCatching {
            persistLoginCredentials(
                credentials = credentials(),
                addServer = { serverConfig() },
                saveCredentials = { throw cancellation },
                removeServer = { removedIds += it },
            )
        }.exceptionOrNull()

        assertEquals(cancellation, caught)
        assertEquals(listOf("server-id"), removedIds)
    }

    @Test
    fun successfulPersistenceStoresCredentialsForCreatedServer() = runTest {
        var stored: ServerCredentials? = null
        val result = persistLoginCredentials(
            credentials = credentials(),
            addServer = { serverConfig() },
            saveCredentials = { stored = it },
            removeServer = { error("unexpected rollback") },
        )

        result.fold(
            success = { assertEquals(Unit, it) },
            failure = { error("Unexpected persistence failure: $it") },
        )
        assertEquals("server-id", stored?.serverId)
    }

    @Test
    fun existingServerLoginSavesCredentialsWithoutAddingOrRemovingServer() = runTest {
        var stored: ServerCredentials? = null
        var addCalls = 0
        val removedIds = mutableListOf<String>()

        val result = persistLoginCredentials(
            credentials = credentials(),
            addServer = {
                addCalls += 1
                serverConfig()
            },
            saveCredentials = { stored = it },
            removeServer = { removedIds += it },
            existingServerId = "existing-server-id",
        )

        result.fold(
            success = { assertEquals(Unit, it) },
            failure = { error("Unexpected persistence failure: $it") },
        )
        assertEquals("existing-server-id", stored?.serverId)
        assertEquals(0, addCalls)
        assertEquals(emptyList(), removedIds)
    }

    private fun credentials() = ServerCredentials(
        serverId = "",
        username = "demo",
        accessToken = "token",
        refreshToken = null,
        expiresAt = null,
    )

    private fun serverConfig() = ServerConfig(
        id = "server-id",
        name = "Storyteller",
        type = ServerType.Storyteller,
        baseUrl = "https://example.invalid",
        addedAt = 1L,
    )
}
