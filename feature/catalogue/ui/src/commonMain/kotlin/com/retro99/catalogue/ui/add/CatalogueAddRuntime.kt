package com.retro99.catalogue.ui.add

import androidx.lifecycle.ViewModel
import com.retro99.server.api.CatalogueAccountEditor
import com.retro99.server.api.CatalogueConnectionResult
import com.retro99.server.api.CatalogueConnectionValidator
import com.retro99.server.api.OpdsAccountDetails
import com.retro99.server.api.ServerRegistry
import com.retro99.server.api.ServerType
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.koin.core.annotation.InjectedParam
import org.koin.core.annotation.KoinViewModel
import org.koin.core.annotation.Provided
import org.koin.core.annotation.Single

@Single(binds = [CatalogueAddressValidator::class])
class RepositoryCatalogueAddressValidator(
    @Provided private val validator: CatalogueConnectionValidator,
) : CatalogueAddressValidator {
    override suspend fun validate(address: String, account: OpdsAccountDetails?): CatalogueValidation {
        val result = validator.validate(address, account)
        return when (result) {
            CatalogueConnectionResult.Accepted -> CatalogueValidation.Accepted
            CatalogueConnectionResult.WebPage -> CatalogueValidation.WebPage
            CatalogueConnectionResult.Unreachable -> CatalogueValidation.Unreachable
            CatalogueConnectionResult.NotCatalogue -> CatalogueValidation.NotCatalogue
            CatalogueConnectionResult.NeedsBasic -> CatalogueValidation.NeedsBasic
            is CatalogueConnectionResult.UnsupportedSignIn -> CatalogueValidation.Unsupported(rootAnswered401 = result.rootAnswered401)
            CatalogueConnectionResult.CertificateFailure -> CatalogueValidation.CertificateFailure
            CatalogueConnectionResult.InvalidCredentials -> CatalogueValidation.InvalidCredentials
        }
    }
}

@Single(binds = [CatalogueAddStore::class])
class RegistryCatalogueAddStore(
    @Provided private val registry: ServerRegistry,
    @Provided private val accountEditor: CatalogueAccountEditor,
) : CatalogueAddStore {
    private val addMutex = Mutex()

    override suspend fun existingAddresses(): Set<String> = registry.getAllServers()
        .filter { it.type == ServerType.Opds }
        .mapTo(mutableSetOf()) { it.baseUrl }

    override suspend fun addValidated(name: String, address: String, account: OpdsAccountDetails?): String = addMutex.withLock {
        if (existingAddresses().any { it == address }) throw DuplicateCatalogueAddressException()
        val source = registry.addServer(name, ServerType.Opds, address)
        try {
            currentCoroutineContext().ensureActive()
            if (account != null) accountEditor.saveAccount(source.id, account)
            currentCoroutineContext().ensureActive()
            source.id
        } catch (failure: Exception) {
            // A failed account write or cancellation must not leave a partial source behind.
            withContext(NonCancellable) { runCatching { registry.removeServer(source.id) } }
            throw failure
        }
    }
}

@KoinViewModel
class CatalogueAddViewModel(
    @Provided validator: CatalogueAddressValidator,
    @Provided store: CatalogueAddStore,
    @InjectedParam initialAddress: String,
    @InjectedParam initialNeedsAccount: Boolean,
    @InjectedParam initialUsername: String,
    @InjectedParam private val presetName: String?,
) : ViewModel() {
    val flow = CatalogueAddFlow(
        validator = validator,
        store = store,
        allowHttp = CatalogueHttpPolicy.allowHttp,
        initialAddress = initialAddress,
        needsAccount = initialNeedsAccount,
        username = initialUsername,
    )

    suspend fun submit() {
        val name = presetName
        if (name == null) flow.submit() else flow.submit(name)
    }

    suspend fun addWithoutAccount() {
        val name = presetName
        if (name == null) flow.addWithoutAccount() else flow.addWithoutAccount(name)
    }

    suspend fun confirmHttp() {
        val name = presetName
        if (name == null) flow.confirmHttp() else flow.confirmHttp(name)
    }
    fun cancel() = flow.cancel()
}
