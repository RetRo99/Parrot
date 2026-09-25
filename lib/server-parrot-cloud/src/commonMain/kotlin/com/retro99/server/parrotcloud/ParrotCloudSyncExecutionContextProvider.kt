package com.retro99.server.parrotcloud

import com.retro99.cloud.implementation.SupabaseClientProvider
import com.retro99.cloudaccount.domain.CloudProfileLinkRepository
import com.retro99.database.api.ProfileDatabaseSession
import com.retro99.sync.data.SyncExecutionContext
import com.retro99.sync.data.SyncExecutionContextProvider
import com.retro99.sync.data.SyncExecutionResult
import com.retro99.user.api.UserRegistry
import kotlinx.coroutines.CancellationException
import org.koin.core.annotation.Provided
import org.koin.core.annotation.Single

/**
 * Pins the active profile, cloud account, credentials, and profile database
 * for one shared-engine execution.
 */
@Single(binds = [SyncExecutionContextProvider::class])
class ParrotCloudSyncExecutionContextProvider(
    @Provided private val clientProvider: SupabaseClientProvider,
    @Provided private val profileLinkRepository: CloudProfileLinkRepository,
    @Provided private val profileDatabaseSession: ProfileDatabaseSession,
    @Provided private val userRegistry: UserRegistry,
) : SyncExecutionContextProvider {
    override suspend fun <T> withPinnedContext(
        operation: suspend (SyncExecutionContext) -> T,
    ): SyncExecutionResult<T> {
        if (!clientProvider.isConfigured) return SyncExecutionResult.NotConfigured

        val localProfileId = userRegistry.getActiveProfileIdOrDefault()
        val link = profileLinkRepository.getForLocalProfile(localProfileId)
            ?: return SyncExecutionResult.ProfileNotLinked
        if (!link.syncEnabled) return SyncExecutionResult.SyncDisabled

        return try {
            clientProvider.withProfileSession(localProfileId) {
                if (!clientProvider.currentSessionState().isAuthenticatedAs(link.cloudUserId)) {
                    return@withProfileSession SyncExecutionResult.NotAuthenticated
                }
                profileDatabaseSession.withProfile(localProfileId) {
                    SyncExecutionResult.Ready(
                        operation(
                            SyncExecutionContext(
                                localProfileId = localProfileId,
                                remoteAccountId = link.cloudUserId,
                            ),
                        ),
                    )
                }
            }
        } catch (exception: CancellationException) {
            throw exception
        } catch (exception: Exception) {
            SyncExecutionResult.Failed(
                exception.message ?: "Cloud synchronization failed",
            )
        }
    }
}
