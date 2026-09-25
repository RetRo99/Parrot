package com.retro99.login.domain

import com.retro99.base.result.CompletableResult
import com.retro99.base.server.ServerType

interface LoginRepository {

    suspend fun login(
        serverType: ServerType,
        serverUrl: String,
        username: String,
        password: String,
        existingServerId: String? = null,
    ): CompletableResult

    suspend fun loginWithOAuth(
        serverType: ServerType,
        serverUrl: String,
    ): CompletableResult
}
