package com.retro99.catalogue.ui.add

import com.retro99.catalogue.domain.AcquisitionState
import com.retro99.server.api.OpdsAccountDetails
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/** One bounded fetch of the feed URL entered on Add a library. */
fun interface CatalogueAddressValidator {
    suspend fun validate(address: String, account: OpdsAccountDetails?): CatalogueValidation
}

/** The only persistence edge used by the add flow. Implementations save accounts via the editor. */
interface CatalogueAddStore {
    suspend fun existingAddresses(): Set<String>
    suspend fun addValidated(name: String, address: String, account: OpdsAccountDetails?): String
}

sealed interface CatalogueValidation {
    data object Accepted : CatalogueValidation
    data object WebPage : CatalogueValidation
    data object Unreachable : CatalogueValidation
    data object NotCatalogue : CatalogueValidation
    data object NeedsBasic : CatalogueValidation
    data class Unsupported(val rootAnswered401: Boolean) : CatalogueValidation
    data object CertificateFailure : CatalogueValidation
    data object InvalidCredentials : CatalogueValidation
}

enum class CatalogueAddPhase { Idle, Checking, SigningIn }

enum class CatalogueAddError {
    WebPage,
    Unreachable,
    NotCatalogue,
    SignInNeeded,
    WrongCredentials,
    DuplicateAddress,
    SaveFailed,
}

enum class CatalogueAddDialog {
    HttpWarning,
    HttpBlocked,
    PasswordHttp,
    PasswordHttpBlocked,
    Certificate,
    Unsupported,
    UnsupportedBlocked,
}

data class CatalogueAddViewState(
    val address: String = "",
    val needsAccount: Boolean = false,
    val username: String = "",
    val password: String = "",
    val phase: CatalogueAddPhase = CatalogueAddPhase.Idle,
    val error: CatalogueAddError? = null,
    val dialog: CatalogueAddDialog? = null,
    val addedSourceId: String? = null,
    val focusAddress: Boolean = false,
) {
    val isEditable: Boolean get() = phase == CatalogueAddPhase.Idle && dialog == null && addedSourceId == null
}

/**
 * Testable state holder for adding a catalogue. It does not write anything until the first page
 * has been validated and any required confirmation has been given.
 */
class CatalogueAddFlow(
    private val validator: CatalogueAddressValidator,
    private val store: CatalogueAddStore,
    private val allowHttp: Boolean,
    initialAddress: String = "",
    needsAccount: Boolean = false,
    username: String = "",
) {
    private val _state = MutableStateFlow(
        CatalogueAddViewState(
            address = initialAddress,
            needsAccount = needsAccount,
            username = username,
        ),
    )
    val state: StateFlow<CatalogueAddViewState> = _state.asStateFlow()

    private var generation = 0L
    private var activeJob: Job? = null
    private var validatedHttpWithoutAccount = false

    fun updateAddress(value: String) = edit { copy(address = value, error = null, focusAddress = false) }
    fun updateNeedsAccount(value: Boolean) = edit {
        copy(needsAccount = value, error = null, password = if (value) password else "")
    }
    fun updateUsername(value: String) = edit { copy(username = value, error = null) }
    fun updatePassword(value: String) = edit { copy(password = value, error = null) }

    suspend fun submit(name: String = suggestedName(_state.value.address)) {
        val before = _state.value
        if (!before.isEditable || before.address.isEmpty()) return
        val attempt = ++generation
        val job = currentCoroutineContext()[Job]
        activeJob = job
        _state.value = before.copy(phase = CatalogueAddPhase.Checking, error = null, dialog = null)
        try {
            if (before.address in store.existingAddresses()) {
                finish(attempt) { copy(phase = CatalogueAddPhase.Idle, error = CatalogueAddError.DuplicateAddress) }
                return
            }

            val plainHttp = before.address.startsWith("http://", ignoreCase = true)
            if (plainHttp && !allowHttp) {
                finish(attempt) { copy(phase = CatalogueAddPhase.Idle, dialog = CatalogueAddDialog.HttpBlocked) }
                return
            }

            // Basic credentials are never passed to the validator for a cleartext address.
            val account = if (before.needsAccount && !plainHttp) {
                OpdsAccountDetails(before.username, before.password)
            } else {
                null
            }
            if (account != null) {
                finish(attempt) { copy(phase = CatalogueAddPhase.SigningIn) }
            }
            val answer = validator.validate(before.address, account)
            currentCoroutineContext().ensureActive()
            if (attempt != generation) return

            when (answer) {
                CatalogueValidation.Accepted -> when {
                    plainHttp && before.needsAccount -> {
                        validatedHttpWithoutAccount = true
                        finish(attempt) { copy(phase = CatalogueAddPhase.Idle, dialog = CatalogueAddDialog.PasswordHttp) }
                    }
                    plainHttp -> {
                        validatedHttpWithoutAccount = true
                        finish(attempt) { copy(phase = CatalogueAddPhase.Idle, dialog = CatalogueAddDialog.HttpWarning) }
                    }
                    else -> persist(attempt, name, before.address, account)
                }
                CatalogueValidation.WebPage -> finish(attempt) {
                    copy(phase = CatalogueAddPhase.Idle, error = CatalogueAddError.WebPage)
                }
                CatalogueValidation.Unreachable -> finish(attempt) {
                    copy(phase = CatalogueAddPhase.Idle, error = CatalogueAddError.Unreachable)
                }
                CatalogueValidation.NotCatalogue -> finish(attempt) {
                    copy(phase = CatalogueAddPhase.Idle, error = CatalogueAddError.NotCatalogue)
                }
                CatalogueValidation.NeedsBasic -> if (plainHttp) {
                    finish(attempt) { copy(phase = CatalogueAddPhase.Idle, dialog = CatalogueAddDialog.PasswordHttpBlocked) }
                } else {
                    finish(attempt) {
                        copy(
                            phase = CatalogueAddPhase.Idle,
                            needsAccount = true,
                            error = CatalogueAddError.SignInNeeded,
                        )
                    }
                }
                is CatalogueValidation.Unsupported -> finish(attempt) {
                    copy(
                        phase = CatalogueAddPhase.Idle,
                        dialog = if (answer.rootAnswered401) CatalogueAddDialog.UnsupportedBlocked else CatalogueAddDialog.Unsupported,
                    )
                }
                CatalogueValidation.CertificateFailure -> finish(attempt) {
                    copy(phase = CatalogueAddPhase.Idle, dialog = CatalogueAddDialog.Certificate)
                }
                CatalogueValidation.InvalidCredentials -> finish(attempt) {
                    copy(phase = CatalogueAddPhase.Idle, error = CatalogueAddError.WrongCredentials, password = "")
                }
            }
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (_: Exception) {
            finish(attempt) { copy(phase = CatalogueAddPhase.Idle, error = CatalogueAddError.SaveFailed) }
        } finally {
            if (attempt == generation) activeJob = null
        }
    }

    suspend fun confirmHttp(name: String = suggestedName(_state.value.address)) {
        if (_state.value.dialog != CatalogueAddDialog.HttpWarning || !validatedHttpWithoutAccount) return
        persist(generation, name, _state.value.address, null)
    }

    suspend fun addWithoutAccount(name: String = suggestedName(_state.value.address)) {
        val dialog = _state.value.dialog
        if (dialog != CatalogueAddDialog.PasswordHttp && dialog != CatalogueAddDialog.Unsupported) return
        if (dialog == CatalogueAddDialog.PasswordHttp && !validatedHttpWithoutAccount) return
        persist(generation, name, _state.value.address, null)
    }

    fun changeAddress() {
        val dialog = _state.value.dialog
        if (dialog != CatalogueAddDialog.PasswordHttpBlocked && dialog != CatalogueAddDialog.HttpBlocked) return
        _state.update { it.copy(dialog = null, focusAddress = true) }
    }

    fun confirmDialogPrimary() {
        // The blocked and certificate dialogs have no continuation; the single action returns to Add.
        if (_state.value.dialog in setOf(CatalogueAddDialog.UnsupportedBlocked, CatalogueAddDialog.Certificate, CatalogueAddDialog.HttpBlocked)) {
            _state.update { it.copy(dialog = null, focusAddress = true) }
        }
    }

    fun dismissDialog() = _state.update { it.copy(dialog = null) }

    /** Cancels the request and invalidates even a result from a non-cooperative validator. */
    fun cancel() {
        generation++
        activeJob?.cancel(CancellationException("Catalogue add cancelled"))
        activeJob = null
        _state.update { it.copy(phase = CatalogueAddPhase.Idle, dialog = null, focusAddress = false) }
    }

    private suspend fun persist(attempt: Long, name: String, address: String, account: OpdsAccountDetails?) {
        if (attempt != generation) return
        try {
            val sourceId = store.addValidated(name, address, account)
            currentCoroutineContext().ensureActive()
            finish(attempt) { copy(phase = CatalogueAddPhase.Idle, dialog = null, addedSourceId = sourceId, error = null) }
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (_: Exception) {
            finish(attempt) { copy(phase = CatalogueAddPhase.Idle, error = CatalogueAddError.SaveFailed) }
        }
    }

    private fun edit(transform: CatalogueAddViewState.() -> CatalogueAddViewState) {
        _state.update { current -> if (current.isEditable) current.transform() else current }
    }

    private fun finish(attempt: Long, transform: CatalogueAddViewState.() -> CatalogueAddViewState) {
        if (attempt == generation) _state.update(transform)
    }

    private fun suggestedName(address: String): String = address
        .removePrefix("https://")
        .removePrefix("http://")
        .substringBefore('/')
        .substringBefore('?')
        .ifBlank { "Book catalogue" }
}

/** Waiting, running, failed and interrupted rows remain actionable in Downloads. */
fun activeOrFailedCatalogueDownloads(states: List<AcquisitionState>): Int = states.count { state ->
    state == AcquisitionState.Waiting || state.isRunning || state is AcquisitionState.Failed || state == AcquisitionState.Interrupted
}
