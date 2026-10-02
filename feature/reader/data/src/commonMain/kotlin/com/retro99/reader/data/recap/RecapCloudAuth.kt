package com.retro99.reader.data.recap

import com.retro99.cloud.implementation.CloudConfiguration
import com.retro99.cloud.implementation.SupabaseClientProvider
import io.github.jan.supabase.auth.auth
import io.github.jan.supabase.auth.status.SessionStatus
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.mapNotNull
import kotlin.coroutines.cancellation.CancellationException

/** Where the recap function lives. */
data class RecapEndpoint(
    val supabaseUrl: String,
    val publishableKey: String,
) {
    val isConfigured: Boolean
        get() = supabaseUrl.isNotBlank() && publishableKey.isNotBlank()

    val url: String
        get() = supabaseUrl.trimEnd('/') + "/functions/v1/generate-recap"

    override fun toString(): String = "RecapEndpoint(configured=$isConfigured)"

    companion object {
        fun from(configuration: CloudConfiguration) =
            RecapEndpoint(configuration.supabaseUrl, configuration.publishableKey)
    }
}

/** The signed-in user's access token, as the recap engine needs it. */
interface RecapAuthTokens {
    fun isSignedIn(): Boolean

    fun observeSignedIn(): Flow<Boolean>

    suspend fun accessToken(): String?

    /** Refreshes the session once; null when that is not possible. */
    suspend fun refreshedAccessToken(): String?
}

/** Uses the active profile's Supabase client, resolved per call. */
class SupabaseRecapAuthTokens(
    private val clientProvider: SupabaseClientProvider,
) : RecapAuthTokens {

    override fun isSignedIn(): Boolean =
        clientProvider.isConfigured &&
            clientProvider.currentSessionState().status is SessionStatus.Authenticated

    override fun observeSignedIn(): Flow<Boolean> {
        if (!clientProvider.isConfigured) return flowOf(false)
        return clientProvider.observeActiveSessionState()
            .mapNotNull { state -> signedInOrUnknown(state.status) }
            .distinctUntilChanged()
    }

    override suspend fun accessToken(): String? = guarded {
        clientProvider.client.auth.currentAccessTokenOrNull()
    }

    override suspend fun refreshedAccessToken(): String? = guarded {
        val auth = clientProvider.client.auth
        auth.refreshCurrentSession()
        auth.currentAccessTokenOrNull()
    }

    internal companion object {
        /**
         * Null while the session is still loading: a signed-in user would
         * otherwise flash "sign in" on every cold start.
         */
        fun signedInOrUnknown(status: SessionStatus): Boolean? = when (status) {
            is SessionStatus.Initializing -> null
            is SessionStatus.Authenticated -> true
            else -> false
        }
    }

    // The client throws after a profile switch; treat it as signed out.
    private inline fun guarded(block: () -> String?): String? = try {
        block()
    } catch (e: CancellationException) {
        throw e
    } catch (_: Exception) {
        null
    }
}
