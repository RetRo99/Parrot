package com.retro99.database.api

interface ProfileDatabaseSession {
    suspend fun <T> withProfile(
        localProfileId: String,
        operation: suspend () -> T,
    ): T
}
