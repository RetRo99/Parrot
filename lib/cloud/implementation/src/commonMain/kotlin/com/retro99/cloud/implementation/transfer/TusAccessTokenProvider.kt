package com.retro99.cloud.implementation.transfer

import com.retro99.cloud.implementation.SupabaseClientProvider
import io.github.jan.supabase.auth.auth
import org.koin.core.annotation.Provided
import org.koin.core.annotation.Single

fun interface TusAccessTokenProvider {
    fun currentAccessToken(): String?
}

@Single(binds = [TusAccessTokenProvider::class])
class SupabaseTusAccessTokenProvider(
    @Provided private val clientProvider: SupabaseClientProvider,
) : TusAccessTokenProvider {
    override fun currentAccessToken(): String? =
        clientProvider.client.auth.currentAccessTokenOrNull()
}
