package com.retro99.catalogue.ui.settings

import com.retro99.catalogue.ui.add.*
import com.retro99.server.api.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

enum class CatalogueLibraryAction { Browse, Account, Details, TurnOn, Retry }

data class CatalogueLibraryStatus(
    val access: ServerAccessState,
    val action: CatalogueLibraryAction,
    val checkedAt: Long? = null,
    val errorAt: Long? = null,
    val isLastError: Boolean = false,
)

/** Authentication and disabled states win over connectivity; a recovered error is historical. */
fun catalogueLibraryStatus(status: CatalogueAccessStatus, now: Long): CatalogueLibraryStatus {
    val check = status.lastCheck
    val errorAt = check.lastErrorAt
    val successAt = check.lastSuccessAt
    val errorIsLatest = check.lastError != null && errorAt != null &&
        (successAt == null || errorAt >= successAt)
    val action = when (status.access) {
        ServerAccessState.TurnedOff -> CatalogueLibraryAction.TurnOn
        ServerAccessState.SignInUnsupported -> CatalogueLibraryAction.Details
        ServerAccessState.SignInNeeded -> CatalogueLibraryAction.Account
        else -> if (errorIsLatest) CatalogueLibraryAction.Retry else CatalogueLibraryAction.Browse
    }
    return CatalogueLibraryStatus(
        access = status.access,
        action = action,
        checkedAt = check.lastSuccessAt?.takeIf { action == CatalogueLibraryAction.Browse && now - it < 7 * 86_400_000L },
        errorAt = check.lastErrorAt.takeIf { errorIsLatest },
        isLastError = action == CatalogueLibraryAction.Retry,
    )
}

fun catalogueSettingsCheckedAt(status: CatalogueAccessStatus): Long? =
    listOfNotNull(status.lastCheck.lastSuccessAt, status.lastCheck.lastErrorAt).maxOrNull()

data class CatalogueSettingsSource(
    val profileId: String,
    val config: ServerConfig,
    val status: CatalogueAccessStatus,
    val accountName: String?,
) {
    override fun toString() = "CatalogueSettingsSource(redacted)"
}

interface CatalogueSettingsGateway {
    fun observeSource(sourceId: String): Flow<CatalogueSettingsSource?>
    suspend fun existingAddresses(profileId: String, sourceId: String): Set<String>
    suspend fun updateAddress(source: CatalogueSettingsSource, address: String, account: OpdsAccountDetails?, validation: CatalogueValidation)
    suspend fun saveAccount(source: CatalogueSettingsSource, account: OpdsAccountDetails)
    suspend fun removeAccount(source: CatalogueSettingsSource)
    suspend fun setEnabled(source: CatalogueSettingsSource, enabled: Boolean)
    suspend fun countBooks(source: CatalogueSettingsSource): Long
    suspend fun removeCatalogue(source: CatalogueSettingsSource)
    suspend fun retry(source: CatalogueSettingsSource)
}

enum class CatalogueSettingsDialog { Address, Account, RemoveAccount, RemoveCatalogue }

data class CatalogueSettingsState(
    val source: CatalogueSettingsSource? = null,
    val loading: Boolean = true,
    val closed: Boolean = false,
    val showAddress: Boolean = false,
    val dialog: CatalogueSettingsDialog? = null,
    val downloadedBooks: Long? = null,
    val busy: Boolean = false,
    val failed: Boolean = false,
) {
    val hasKey: Boolean get() = source?.config?.baseUrl?.let(::addressHasKey) == true
    val displayAddress: String get() = source?.config?.baseUrl?.let { if (showAddress) it else maskAddress(it) }.orEmpty()
    val canBrowse: Boolean get() = source?.let { it.config.enabled && it.status.access != ServerAccessState.SignInUnsupported && it.status.access != ServerAccessState.TurnedOff } == true
    override fun toString() = "CatalogueSettingsState(redacted)"
}

/** View-model logic, shared by production and fixtures. No address or password in saved state. */
class CatalogueSettingsController(
    private val sourceId: String,
    private val gateway: CatalogueSettingsGateway,
    private val validator: CatalogueAddressValidator,
    private val allowHttp: Boolean,
) {
    private val _state = MutableStateFlow(CatalogueSettingsState())
    val state = _state.asStateFlow()
    private var profileId: String? = null
    private var editor: CatalogueAddFlow? = null
    private var committingAddress: String? = null

    suspend fun observe() {
        try {
            gateway.observeSource(sourceId).collect { source ->
                if (_state.value.closed) return@collect
                if (source == null || (profileId != null && source.profileId != profileId)) {
                    editor?.cancel()
                    editor = null
                    _state.value = CatalogueSettingsState(loading = false, closed = true)
                } else {
                    profileId = source.profileId
                    val before = _state.value.source
                    val changedAddress = before != null && before.config.baseUrl != source.config.baseUrl
                    if (changedAddress && source.config.baseUrl != committingAddress) { editor?.cancel(); editor = null }
                    _state.update { it.copy(source = source, loading = false, showAddress = it.showAddress && !changedAddress, dialog = it.dialog.takeUnless { changedAddress && source.config.baseUrl != committingAddress }) }
                }
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            _state.update { it.copy(loading = false, failed = true) }
        }
    }

    fun toggleAddressVisibility() {
        if (_state.value.hasKey) _state.update { it.copy(showAddress = !it.showAddress) }
    }

    fun openAddressEditor(): CatalogueAddFlow? = openEditor(accountOnly = false)
    fun openAccountEditor(): CatalogueAddFlow? = openEditor(accountOnly = true)

    private fun openEditor(accountOnly: Boolean): CatalogueAddFlow? {
        val source = _state.value.source ?: return null
        if (_state.value.busy || _state.value.closed || (accountOnly && source.status.access == ServerAccessState.SignInUnsupported)) return null
        editor?.cancel()
        var validation: CatalogueValidation? = null
        val flow = CatalogueAddFlow(
            validator = CatalogueAddressValidator { address, account -> validator.validate(address, account).also { validation = it } },
            store = object : CatalogueAddStore {
                override suspend fun existingAddresses(): Set<String> =
                    if (accountOnly) emptySet() else gateway.existingAddresses(source.profileId, sourceId)
                override suspend fun addValidated(name: String, address: String, account: OpdsAccountDetails?): String {
                    requireCurrent(source)
                    if (accountOnly) {
                        requireNotNull(account)
                        gateway.saveAccount(source, account)
                    } else {
                        committingAddress = address
                        try { gateway.updateAddress(source, address, account, checkNotNull(validation)) }
                        finally { committingAddress = null }
                    }
                    requireCurrentProfile(source)
                    return sourceId
                }
            },
            allowHttp = allowHttp,
            initialAddress = source.config.baseUrl,
            needsAccount = accountOnly,
            username = if (accountOnly) source.accountName.orEmpty() else "",
        )
        editor = flow
        _state.update { it.copy(dialog = if (accountOnly) CatalogueSettingsDialog.Account else CatalogueSettingsDialog.Address, failed = false) }
        return flow
    }

    fun dismissDialog() {
        editor?.cancel()
        editor = null
        _state.update { it.copy(dialog = null, downloadedBooks = null, failed = false) }
    }

    fun askRemoveAccount() {
        if (_state.value.source != null && !_state.value.busy) _state.update { it.copy(dialog = CatalogueSettingsDialog.RemoveAccount, failed = false) }
    }

    suspend fun confirmRemoveAccount() {
        if (_state.value.dialog == CatalogueSettingsDialog.RemoveAccount) mutate { source ->
            gateway.removeAccount(source)
            _state.update { it.copy(source = it.source?.copy(accountName = null)) }
        }
    }

    suspend fun setEnabled(enabled: Boolean) = mutate { gateway.setEnabled(it, enabled) }
    suspend fun retry() = mutate { gateway.retry(it) }

    suspend fun askRemoveCatalogue() = mutate(closeDialog = false) { source ->
        val count = gateway.countBooks(source)
        requireCurrent(source)
        _state.update { it.copy(dialog = CatalogueSettingsDialog.RemoveCatalogue, downloadedBooks = count) }
    }

    suspend fun confirmRemoveCatalogue() {
        if (_state.value.dialog == CatalogueSettingsDialog.RemoveCatalogue && _state.value.downloadedBooks != null) {
            mutate { gateway.removeCatalogue(it) }
        }
    }

    private suspend fun mutate(closeDialog: Boolean = true, block: suspend (CatalogueSettingsSource) -> Unit) {
        val source = _state.value.source ?: return
        if (_state.value.busy || _state.value.closed) return
        _state.update { it.copy(busy = true, failed = false) }
        try {
            requireCurrent(source)
            block(source)
            if (closeDialog) _state.update { it.copy(dialog = null) }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            if (!_state.value.closed) _state.update { it.copy(failed = true) }
        } finally {
            _state.update { it.copy(busy = false) }
        }
    }

    private fun requireCurrentProfile(source: CatalogueSettingsSource) {
        check(!_state.value.closed && _state.value.source?.profileId == source.profileId)
    }
    private fun requireCurrent(source: CatalogueSettingsSource) {
        requireCurrentProfile(source)
        check(_state.value.source?.config?.baseUrl == source.config.baseUrl)
    }

    fun close() { editor?.cancel(); editor = null }
}
