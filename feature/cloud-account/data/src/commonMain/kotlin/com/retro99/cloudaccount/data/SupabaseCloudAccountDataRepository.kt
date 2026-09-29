package com.retro99.cloudaccount.data

import com.retro99.cloud.implementation.CloudOAuthCallbackRegistry
import com.retro99.cloud.implementation.CloudOAuthException
import com.retro99.cloud.implementation.CloudOAuthUrlLauncher
import com.retro99.cloud.implementation.SupabaseClientProvider
import com.retro99.cloudaccount.domain.CloudAccountException
import com.retro99.cloudaccount.domain.OAuthFailureReason
import com.retro99.cloudaccount.domain.CloudAccountRepository
import com.retro99.cloudaccount.domain.CloudProfileLinkRepository
import com.retro99.cloudaccount.domain.model.CloudAccount
import com.retro99.cloudaccount.domain.model.CloudAuthState
import com.retro99.cloudaccount.domain.model.CloudRegistrationResult
import io.github.jan.supabase.auth.SignOutScope
import io.github.jan.supabase.auth.auth
import io.github.jan.supabase.auth.providers.Google
import io.github.jan.supabase.auth.providers.builtin.Email
import io.github.jan.supabase.auth.user.UserSession
import io.github.jan.supabase.functions.functions
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import org.koin.core.annotation.Provided
import org.koin.core.annotation.Single

@Single(binds = [CloudAccountRepository::class])
class SupabaseCloudAccountDataRepository(
    @Provided private val clientProvider: SupabaseClientProvider,
    @Provided private val oauthUrlLauncher: CloudOAuthUrlLauncher,
    @Provided private val profileLinkRepository: CloudProfileLinkRepository,
) : CloudAccountRepository {
    override fun observeAuthState(): Flow<CloudAuthState> {
        if (!clientProvider.isConfigured) return flowOf(CloudAuthState.SignedOut)
        return clientProvider.observeActiveSessionState()
            .map { status -> status.toCloudAuthState() }
    }

    override fun currentAuthState(): CloudAuthState {
        if (!clientProvider.isConfigured) return CloudAuthState.SignedOut
        return clientProvider.currentSessionState().toCloudAuthState()
    }

    override suspend fun <T> withProfileSession(
        localProfileId: String,
        operation: suspend () -> T,
    ): T = clientProvider.withProfileSession(localProfileId, operation)

    override suspend fun register(
        localProfileId: String,
        email: String,
        password: String,
    ): CloudRegistrationResult {
        requireConfigured()
        val auth = clientProvider.client.auth
        check(auth.currentSessionOrNull() == null) {
            "Cannot register a cloud account while already signed in"
        }
        val response = auth.signUpWith(Email) {
            this.email = email
            this.password = password
        }
        val account = response?.toCloudAccount() ?: auth.currentUserOrNull()?.toCloudAccount()
        return if (auth.currentSessionOrNull() != null && account != null) {
            validateAccountForProfile(localProfileId, account, previousSession = null)
            CloudRegistrationResult.SignedIn(account)
        } else {
            CloudRegistrationResult.AwaitingEmailVerification(email)
        }
    }

    override suspend fun signIn(
        localProfileId: String,
        email: String,
        password: String,
    ): CloudAccount {
        requireConfigured()
        val auth = clientProvider.client.auth
        val previousSession = auth.currentSessionOrNull()
        auth.signInWith(Email) {
            this.email = email
            this.password = password
        }
        val account = auth.currentUserOrNull()?.toCloudAccount()
            ?: error("Cloud sign-in did not return an account")
        validateAccountForProfile(localProfileId, account, previousSession)
        return account
    }

    override suspend fun signInWithGoogle(localProfileId: String): CloudAccount {
        requireConfigured()
        val auth = clientProvider.client.auth
        val previousSession = auth.currentSessionOrNull()
        val oauthUrl = auth.getOAuthUrl(
            provider = Google,
            redirectUrl = clientProvider.redirectUrl,
        )
        val code = try {
            CloudOAuthCallbackRegistry.awaitCode {
                oauthUrlLauncher.open(oauthUrl)
            }
        } catch (exception: CloudOAuthException) {
            throw when (exception.reason) {
                CloudOAuthException.Reason.Cancelled -> CloudAccountException.OAuthCancelled()
                CloudOAuthException.Reason.TimedOut ->
                    CloudAccountException.OAuthFailure(OAuthFailureReason.TimedOut)
                CloudOAuthException.Reason.ProviderFailure ->
                    CloudAccountException.OAuthFailure(OAuthFailureReason.ProviderFailure)
            }
        }
        auth.exchangeCodeForSession(code)
        val account = auth.currentUserOrNull()?.toCloudAccount()
            ?: error("Google sign-in did not return an account")
        validateAccountForProfile(localProfileId, account, previousSession)
        return account
    }

    override suspend fun restoreSession(localProfileId: String): CloudAuthState {
        if (!clientProvider.isConfigured) return CloudAuthState.SignedOut
        return clientProvider.restoreSession(localProfileId).toCloudAuthState()
    }

    override suspend fun signOut(localProfileId: String) {
        if (!clientProvider.isConfigured) return
        val auth = clientProvider.client.auth
        try {
            clientProvider.invalidateCurrentProfileCredentials(localProfileId)
            auth.signOut(SignOutScope.LOCAL)
        } finally {
            withContext(NonCancellable) {
                clientProvider.replaceCurrentProfileSession(localProfileId, null)
            }
        }
    }

    override suspend fun deleteAccount(localProfileId: String) {
        requireConfigured()
        val client = clientProvider.client
        check(client.auth.currentSessionOrNull() != null) {
            "A signed-in cloud account is required for deletion"
        }

        client.functions("delete-cloud-account")

        withContext(NonCancellable) {
            try {
                clientProvider.invalidateCurrentProfileCredentials(localProfileId)
            } finally {
                clientProvider.replaceCurrentProfileSession(localProfileId, null)
            }
        }
    }

    private fun requireConfigured() {
        if (!clientProvider.isConfigured) throw CloudAccountException.NotConfigured()
    }

    private suspend fun validateAccountForProfile(
        localProfileId: String,
        account: CloudAccount,
        previousSession: UserSession?,
    ) {
        val existingLink = profileLinkRepository.getForLocalProfile(localProfileId)
        if (existingLink == null || existingLink.cloudUserId == account.id) return

        clientProvider.replaceCurrentProfileSession(localProfileId, previousSession)
        throw CloudAccountException.ProfileAlreadyLinked()
    }
}
