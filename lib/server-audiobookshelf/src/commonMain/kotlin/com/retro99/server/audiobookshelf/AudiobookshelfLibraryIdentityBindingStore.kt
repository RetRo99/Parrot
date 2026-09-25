package com.retro99.server.audiobookshelf

import com.retro99.base.server.ServerType
import com.retro99.cloudaccount.domain.CloudAccountRepository
import com.retro99.cloudaccount.domain.CloudProfileLinkRepository
import com.retro99.cloudaccount.domain.model.CloudAuthState
import com.retro99.server.api.ServerAuthState
import com.retro99.server.api.ServerRegistry
import com.retro99.server.api.library.LibraryAdapterId
import com.retro99.server.api.library.LibraryProfileId
import com.retro99.server.api.library.LibrarySourceIdentityBinding
import com.retro99.server.api.library.LibrarySourceIdentityPairingException
import com.retro99.server.api.library.LibrarySourceIdentityPairingFailure
import com.retro99.server.api.library.LibrarySourceIdentityPairingStatus
import com.retro99.server.api.library.LibrarySourceIdentitySyncAuthorization
import com.retro99.server.api.library.SourceAccountIdentity
import com.retro99.user.api.UserRegistry
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapLatest
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import org.koin.core.annotation.Provided
import org.koin.core.annotation.Single
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

interface AudiobookshelfLibraryIdentityBindingProvider {
    suspend fun currentIdentity(serverId: String): SourceAccountIdentity.Portable?

    fun observePairingStatus(serverId: String): Flow<LibrarySourceIdentityPairingStatus>

    suspend fun createPairingCode(serverId: String): String

    suspend fun importPairingCode(serverId: String, code: String)
}

@Single(
    binds = [
        AudiobookshelfLibraryIdentityBindingProvider::class,
        LibrarySourceIdentitySyncAuthorization::class,
    ],
)
class AudiobookshelfLibraryIdentityBindingStore(
    @Provided private val serverRegistry: ServerRegistry,
    @Provided private val cloudProfileLinkRepository: CloudProfileLinkRepository,
    @Provided private val cloudAccountRepository: CloudAccountRepository,
    @Provided private val userRegistry: UserRegistry,
) : AudiobookshelfLibraryIdentityBindingProvider,
    LibrarySourceIdentitySyncAuthorization {
    private val json = Json {
        encodeDefaults = true
    }

    override suspend fun currentIdentity(serverId: String): SourceAccountIdentity.Portable? {
        val profileId = userRegistry.getActiveProfileIdOrDefault()
        val server = serverRegistry.getServer(serverId) ?: return null
        if (server.type != ServerType.Audiobookshelf) return null
        val binding = server.libraryIdentityBindings.firstOrNull { candidate ->
            candidate.adapterId == AUDIOBOOKSHELF_ADAPTER_ID
        } ?: return null
        if (!binding.hasValidBackendId()) return null
        val cloudAccountId = cloudProfileLinkRepository.getForLocalProfile(profileId)
            ?.cloudUserId ?: return null
        if (cloudAccountId != binding.cloudAccountId) return null
        if (!serverRegistry.isAuthenticated(serverId)) return null
        val sourceAccountId = serverRegistry.getCredentials(serverId)?.accountId ?: return null
        if (sourceAccountId != binding.sourceAccountId) return null
        return binding.toPortableIdentity()
    }

    override suspend fun isAuthorized(
        profileId: LibraryProfileId,
        cloudAccountId: String,
        adapterId: LibraryAdapterId,
        identity: SourceAccountIdentity.Portable,
    ): Boolean {
        if (adapterId != AUDIOBOOKSHELF_ADAPTER_ID) return true
        if (!identity.backendId.isValidAudiobookshelfBackendId()) return false
        if (profileId.value != userRegistry.getActiveProfileIdOrDefault()) return false
        val cloudLink = cloudProfileLinkRepository.getForLocalProfile(profileId.value)
            ?: return false
        if (cloudLink.cloudUserId != cloudAccountId) return false
        val authenticatedCloudUserId =
            (cloudAccountRepository.currentAuthState() as? CloudAuthState.SignedIn)
                ?.account?.id ?: return false
        if (authenticatedCloudUserId != cloudAccountId) return false

        return serverRegistry.getAllServers().any { server ->
            if (server.type != ServerType.Audiobookshelf) return@any false
            val binding = server.libraryIdentityBindings.firstOrNull { candidate ->
                candidate.adapterId == AUDIOBOOKSHELF_ADAPTER_ID
            } ?: return@any false
            binding.cloudAccountId == cloudAccountId &&
                binding.sourceAccountId == identity.accountId &&
                binding.backendId == identity.backendId &&
                binding.hasValidBackendId() &&
                serverRegistry.isAuthenticated(server.id) &&
                serverRegistry.getCredentials(server.id)?.accountId == identity.accountId
        }
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    override fun observePairingStatus(serverId: String): Flow<LibrarySourceIdentityPairingStatus> =
        userRegistry.observeActiveProfile()
            .map { profile -> profile?.id ?: UserRegistry.DEFAULT_USER_ID }
            .distinctUntilChanged()
            .flatMapLatest { profileId ->
                combine(
                    serverRegistry.observeAllServers(),
                    cloudProfileLinkRepository.observeForLocalProfile(profileId),
                    serverRegistry.observeAuthState(serverId),
                ) { servers, cloudLink, authState ->
                    Triple(servers, cloudLink, authState)
                }.mapLatest { (servers, cloudLink, authState) ->
                    val server = servers.firstOrNull { candidate -> candidate.id == serverId }
                    val binding = server?.libraryIdentityBindings?.firstOrNull { candidate ->
                        candidate.adapterId == AUDIOBOOKSHELF_ADAPTER_ID
                    }
                    when {
                        server?.type != ServerType.Audiobookshelf ->
                            LibrarySourceIdentityPairingStatus.ServerUnavailable
                        binding == null -> LibrarySourceIdentityPairingStatus.Unpaired
                        !binding.hasValidBackendId() ->
                            LibrarySourceIdentityPairingStatus.ServerUnavailable
                        cloudLink == null ->
                            LibrarySourceIdentityPairingStatus.CloudAccountRequired
                        cloudLink.cloudUserId != binding.cloudAccountId ->
                            LibrarySourceIdentityPairingStatus.CloudAccountMismatch
                        authState !is ServerAuthState.Authenticated ->
                            LibrarySourceIdentityPairingStatus.NotAuthenticated
                        serverRegistry.getCredentials(serverId)?.accountId !=
                            binding.sourceAccountId ->
                            LibrarySourceIdentityPairingStatus.SourceAccountMismatch
                        else -> LibrarySourceIdentityPairingStatus.Paired
                    }
                }.distinctUntilChanged()
            }

    @OptIn(ExperimentalUuidApi::class)
    override suspend fun createPairingCode(serverId: String): String {
        val context = requireCurrentContext(serverId)
        val existing = context.server.libraryIdentityBindings.firstOrNull { binding ->
            binding.adapterId == AUDIOBOOKSHELF_ADAPTER_ID
        }
        val binding = when {
            existing == null -> LibrarySourceIdentityBinding(
                adapterId = AUDIOBOOKSHELF_ADAPTER_ID,
                cloudAccountId = context.cloudAccountId,
                sourceAccountId = context.sourceAccountId,
                backendId = "$AUDIOBOOKSHELF_BACKEND_PREFIX${Uuid.random()}",
            )
            !existing.hasValidBackendId() -> throw pairingFailure(
                LibrarySourceIdentityPairingFailure.InvalidCode,
            )
            existing.cloudAccountId != context.cloudAccountId -> throw pairingFailure(
                LibrarySourceIdentityPairingFailure.CloudAccountMismatch,
            )
            existing.sourceAccountId != context.sourceAccountId -> throw pairingFailure(
                LibrarySourceIdentityPairingFailure.SourceAccountMismatch,
            )
            else -> existing
        }
        if (existing == null) saveBinding(context.server, binding)
        return encodePairingCode(binding)
    }

    override suspend fun importPairingCode(serverId: String, code: String) {
        val binding = decodePairingCode(code)
        val context = requireCurrentContext(serverId)
        if (binding.cloudAccountId != context.cloudAccountId) {
            throw pairingFailure(LibrarySourceIdentityPairingFailure.CloudAccountMismatch)
        }
        if (binding.sourceAccountId != context.sourceAccountId) {
            throw pairingFailure(LibrarySourceIdentityPairingFailure.SourceAccountMismatch)
        }
        val existing = context.server.libraryIdentityBindings.firstOrNull { candidate ->
            candidate.adapterId == AUDIOBOOKSHELF_ADAPTER_ID
        }
        if (existing != null && existing != binding) {
            throw pairingFailure(LibrarySourceIdentityPairingFailure.AlreadyPaired)
        }
        if (existing == null) saveBinding(context.server, binding)
    }

    private suspend fun requireCurrentContext(serverId: String): CurrentContext {
        val server = serverRegistry.getServer(serverId)
            ?: throw pairingFailure(LibrarySourceIdentityPairingFailure.ServerUnavailable)
        if (server.type != ServerType.Audiobookshelf) {
            throw pairingFailure(LibrarySourceIdentityPairingFailure.ServerUnavailable)
        }
        if (!serverRegistry.isAuthenticated(serverId)) {
            throw pairingFailure(LibrarySourceIdentityPairingFailure.NotAuthenticated)
        }
        val sourceAccountId = serverRegistry.getCredentials(serverId)?.accountId
            ?.takeIf { accountId -> accountId.isNotBlank() }
            ?: throw pairingFailure(
                LibrarySourceIdentityPairingFailure.SourceAccountIdUnavailable,
            )
        val profileId = userRegistry.getActiveProfileIdOrDefault()
        val cloudAccountId = cloudProfileLinkRepository.getForLocalProfile(profileId)
            ?.cloudUserId
            ?: throw pairingFailure(LibrarySourceIdentityPairingFailure.CloudAccountRequired)
        return CurrentContext(server, cloudAccountId, sourceAccountId)
    }

    private suspend fun saveBinding(
        server: com.retro99.server.api.ServerConfig,
        binding: LibrarySourceIdentityBinding,
    ) {
        val remaining = server.libraryIdentityBindings.filterNot { candidate ->
            candidate.adapterId == AUDIOBOOKSHELF_ADAPTER_ID
        }
        serverRegistry.updateServer(
            server.copy(libraryIdentityBindings = remaining + binding),
        )
    }

    private fun encodePairingCode(binding: LibrarySourceIdentityBinding): String =
        PAIRING_CODE_PREFIX + json.encodeToString(
            AudiobookshelfPairingCode(
                version = PAIRING_CODE_VERSION,
                cloudAccountId = binding.cloudAccountId,
                sourceAccountId = binding.sourceAccountId,
                backendId = binding.backendId,
            ),
        )

    private fun decodePairingCode(code: String): LibrarySourceIdentityBinding {
        if (!code.startsWith(PAIRING_CODE_PREFIX)) {
            throw pairingFailure(LibrarySourceIdentityPairingFailure.InvalidCode)
        }
        val pairingCode = try {
            json.decodeFromString<AudiobookshelfPairingCode>(
                code.removePrefix(PAIRING_CODE_PREFIX),
            )
        } catch (exception: SerializationException) {
            throw pairingFailure(LibrarySourceIdentityPairingFailure.InvalidCode)
        } catch (exception: IllegalArgumentException) {
            throw pairingFailure(LibrarySourceIdentityPairingFailure.InvalidCode)
        }
        if (pairingCode.version != PAIRING_CODE_VERSION) {
            throw pairingFailure(
                LibrarySourceIdentityPairingFailure.UnsupportedCodeVersion,
            )
        }
        return try {
            LibrarySourceIdentityBinding(
                adapterId = AUDIOBOOKSHELF_ADAPTER_ID,
                cloudAccountId = pairingCode.cloudAccountId,
                sourceAccountId = pairingCode.sourceAccountId,
                backendId = pairingCode.backendId,
            ).also { binding ->
                require(binding.hasValidBackendId())
            }
        } catch (exception: IllegalArgumentException) {
            throw pairingFailure(LibrarySourceIdentityPairingFailure.InvalidCode)
        }
    }

    private fun LibrarySourceIdentityBinding.toPortableIdentity() =
        SourceAccountIdentity.Portable(
            backendId = backendId,
            accountId = sourceAccountId,
        )

    private fun LibrarySourceIdentityBinding.hasValidBackendId(): Boolean =
        backendId.isValidAudiobookshelfBackendId()

    private fun String.isValidAudiobookshelfBackendId(): Boolean {
        if (!startsWith(AUDIOBOOKSHELF_BACKEND_PREFIX)) return false
        val uuidSuffix = removePrefix(AUDIOBOOKSHELF_BACKEND_PREFIX)
        return AUDIOBOOKSHELF_BACKEND_UUID_PATTERN.matches(uuidSuffix)
    }

    private fun pairingFailure(reason: LibrarySourceIdentityPairingFailure) =
        LibrarySourceIdentityPairingException(reason)

    private data class CurrentContext(
        val server: com.retro99.server.api.ServerConfig,
        val cloudAccountId: String,
        val sourceAccountId: String,
    )

    @Serializable
    private data class AudiobookshelfPairingCode(
        val version: Int,
        val cloudAccountId: String,
        val sourceAccountId: String,
        val backendId: String,
    )

    private companion object {
        val AUDIOBOOKSHELF_ADAPTER_ID = LibraryAdapterId("audiobookshelf")
        const val AUDIOBOOKSHELF_BACKEND_PREFIX = "audiobookshelf-instance:"
        val AUDIOBOOKSHELF_BACKEND_UUID_PATTERN = Regex(
            "[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}",
        )
        const val PAIRING_CODE_PREFIX = "parrot-abs-library-v1:"
        const val PAIRING_CODE_VERSION = 1
    }
}
