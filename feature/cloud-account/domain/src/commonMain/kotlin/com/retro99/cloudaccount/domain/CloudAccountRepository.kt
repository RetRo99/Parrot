package com.retro99.cloudaccount.domain

import com.retro99.cloudaccount.domain.model.CloudAccount
import com.retro99.cloudaccount.domain.model.CloudAuthState
import com.retro99.cloudaccount.domain.model.CloudRegistrationResult
import kotlinx.coroutines.flow.Flow

interface CloudAccountRepository {
    fun observeAuthState(): Flow<CloudAuthState>

    fun currentAuthState(): CloudAuthState

    suspend fun <T> withProfileSession(localProfileId: String, operation: suspend () -> T): T

    suspend fun register(
        localProfileId: String,
        email: String,
        password: String,
    ): CloudRegistrationResult

    suspend fun signIn(localProfileId: String, email: String, password: String): CloudAccount

    suspend fun signInWithGoogle(localProfileId: String): CloudAccount

    suspend fun restoreSession(localProfileId: String): CloudAuthState

    suspend fun signOut(localProfileId: String)
}
