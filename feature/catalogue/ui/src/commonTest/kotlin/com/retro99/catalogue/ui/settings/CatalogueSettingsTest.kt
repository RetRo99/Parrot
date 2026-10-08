package com.retro99.catalogue.ui.settings

import com.retro99.catalogue.ui.add.*
import com.retro99.server.api.*
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.*
import kotlin.test.*

class CatalogueSettingsTest {
    private val now = 2_000_000_000L
    private val source = ServerConfig("cat", "Home Calibre", ServerType.Opds, "https://books.home.lan/opds?apikey=secret", 0)

    @Test fun every_status_has_its_own_action_and_public_never_is_signed_out() {
        val cases = listOf(
            ServerAccessState.Public to CatalogueLibraryAction.Browse,
            ServerAccessState.SignedIn("rok") to CatalogueLibraryAction.Browse,
            ServerAccessState.SignInNeeded to CatalogueLibraryAction.Account,
            ServerAccessState.SignInUnsupported to CatalogueLibraryAction.Details,
            ServerAccessState.TurnedOff to CatalogueLibraryAction.TurnOn,
        )
        cases.forEach { (access, action) ->
            val row = catalogueLibraryStatus(CatalogueAccessStatus(access), now)
            assertEquals(action, row.action)
            assertEquals(access, row.access)
            assertFalse(row.isLastError)
        }
        val error = CatalogueLastCheck(now - 2000, CatalogueErrorKind.Unreachable, now - 1000)
        assertEquals(CatalogueLibraryAction.Retry, catalogueLibraryStatus(CatalogueAccessStatus(lastCheck = error), now).action)
        assertEquals(CatalogueLibraryAction.Browse, catalogueLibraryStatus(CatalogueAccessStatus(lastCheck = error.copy(lastSuccessAt = now)), now).action)
    }

    @Test fun healthy_checks_hide_at_seven_days_but_errors_and_settings_keep_the_time() {
        val sevenDays = 7 * 86_400_000L
        listOf(ServerAccessState.Public, ServerAccessState.SignedIn("rok")).forEach { access ->
            assertNotNull(catalogueLibraryStatus(CatalogueAccessStatus(access, CatalogueLastCheck(now - sevenDays + 1)), now).checkedAt)
            assertNull(catalogueLibraryStatus(CatalogueAccessStatus(access, CatalogueLastCheck(now - sevenDays)), now).checkedAt)
        }
        val old = CatalogueAccessStatus(lastCheck = CatalogueLastCheck(lastError = CatalogueErrorKind.Timeout, lastErrorAt = now - sevenDays * 2))
        assertEquals(now - sevenDays * 2, catalogueLibraryStatus(old, now).errorAt)
        assertEquals(now - sevenDays * 2, catalogueSettingsCheckedAt(old))
    }

    @Test fun two_catalogues_on_one_host_use_paths_then_ports_and_never_queries() {
        val a = source.copy(baseUrl = "https://books.home.lan/opds/fiction?key=private")
        val b = source.copy(id = "other", baseUrl = "https://books.home.lan/opds/comics?key=another")
        assertEquals("books.home.lan/opds/fiction", rowAddress(a, listOf(a, b)))
        assertEquals("books.home.lan/opds/comics", rowAddress(b, listOf(a, b)))
        val c = b.copy(baseUrl = "https://books.home.lan:8443/opds/fiction")
        assertEquals("books.home.lan:443/opds/fiction", rowAddress(a, listOf(a, c)))
    }

    @Test fun masking_show_hide_and_source_changes_reset_visibility() = runTest {
        val gateway = FakeSettingsGateway(snapshot())
        val controller = CatalogueSettingsController("cat", gateway, validator(), true)
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { controller.observe() }
        assertEquals(maskAddress(source.baseUrl), controller.state.value.displayAddress)
        assertTrue(controller.state.value.hasKey)
        controller.toggleAddressVisibility()
        assertEquals(source.baseUrl, controller.state.value.displayAddress)
        controller.toggleAddressVisibility()
        assertEquals(maskAddress(source.baseUrl), controller.state.value.displayAddress)
        controller.toggleAddressVisibility()
        gateway.snapshot.value = snapshot().copy(config = source.copy(baseUrl = "https://books.home.lan/opds?token=new"))
        assertFalse(controller.state.value.showAddress)
    }

    @Test fun account_add_edit_wrong_details_and_confirmed_removal() = runTest {
        val gateway = FakeSettingsGateway(snapshot())
        var answer: CatalogueValidation = CatalogueValidation.InvalidCredentials
        val controller = CatalogueSettingsController("cat", gateway, CatalogueAddressValidator { _, _ -> answer }, true)
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { controller.observe() }
        val editor = controller.openAccountEditor()!!
        editor.updateUsername("rok")
        editor.updatePassword("wrong")
        editor.submit()
        assertEquals(CatalogueAddError.WrongCredentials, editor.state.value.error)
        assertEquals("", editor.state.value.password)
        assertTrue(gateway.accounts.isEmpty())
        answer = CatalogueValidation.Accepted()
        editor.updatePassword("") // Empty passwords are legitimate.
        editor.submit()
        assertEquals(listOf(OpdsAccountDetails("rok", "")), gateway.accounts)
        gateway.snapshot.value = snapshot().copy(accountName = "rok")
        val edit = controller.openAccountEditor()!!
        assertEquals("rok", edit.state.value.username)
        edit.updatePassword("new")
        edit.submit()
        assertEquals(OpdsAccountDetails("rok", "new"), gateway.accounts.last())
        controller.askRemoveAccount()
        assertEquals(CatalogueSettingsDialog.RemoveAccount, controller.state.value.dialog)
        assertEquals(0, gateway.removedAccounts)
        controller.dismissDialog()
        assertEquals(0, gateway.removedAccounts)
        controller.askRemoveAccount()
        controller.confirmRemoveAccount()
        assertEquals(1, gateway.removedAccounts)
    }

    @Test fun turn_off_and_on_keep_accounts_and_hide_browse_while_off_or_unsupported() = runTest {
        val gateway = FakeSettingsGateway(snapshot())
        val controller = CatalogueSettingsController("cat", gateway, validator(), true)
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { controller.observe() }
        controller.setEnabled(false)
        assertEquals(listOf(false), gateway.enabled)
        gateway.snapshot.value = snapshot().copy(config = source.copy(enabled = false))
        assertFalse(controller.state.value.canBrowse)
        controller.setEnabled(true)
        assertEquals(listOf(false, true), gateway.enabled)
        gateway.snapshot.value = snapshot().copy(status = CatalogueAccessStatus(ServerAccessState.SignInUnsupported), accountName = "rok")
        assertFalse(controller.state.value.canBrowse)
        assertNull(controller.openAccountEditor())
        assertTrue(gateway.accounts.isEmpty())
    }

    @Test fun edit_address_reuses_all_add_validation_outcomes_without_adding_a_source() = runTest {
        val cases = listOf(
            CatalogueValidation.WebPage to CatalogueAddError.WebPage,
            CatalogueValidation.Unreachable to CatalogueAddError.Unreachable,
            CatalogueValidation.NotCatalogue to CatalogueAddError.NotCatalogue,
            CatalogueValidation.NeedsBasic to CatalogueAddError.SignInNeeded,
            CatalogueValidation.InvalidCredentials to CatalogueAddError.WrongCredentials,
        )
        for ((answer, error) in cases) {
            val gateway = FakeSettingsGateway(snapshot())
            val controller = CatalogueSettingsController("cat", gateway, validator(answer), true)
            backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { controller.observe() }
            val edit = controller.openAddressEditor()!!
            assertEquals(source.baseUrl, edit.state.value.address)
            edit.submit()
            assertEquals(error, edit.state.value.error)
            assertTrue(gateway.addresses.isEmpty())
        }
        val dialogs = listOf(
            CatalogueValidation.CertificateFailure to CatalogueAddDialog.Certificate,
            CatalogueValidation.Unsupported(true) to CatalogueAddDialog.UnsupportedBlocked,
            CatalogueValidation.Unsupported(false) to CatalogueAddDialog.Unsupported,
        )
        for ((answer, dialog) in dialogs) {
            val gateway = FakeSettingsGateway(snapshot())
            val controller = CatalogueSettingsController("cat", gateway, validator(answer), true)
            backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { controller.observe() }
            val edit = controller.openAddressEditor()!!
            edit.submit()
            assertEquals(dialog, edit.state.value.dialog)
            assertTrue(gateway.addresses.isEmpty())
            if (dialog == CatalogueAddDialog.Unsupported) {
                edit.addWithoutAccount()
                assertEquals(listOf(source.baseUrl), gateway.addresses)
                assertEquals(listOf(answer), gateway.addressValidations)
            }
        }
    }

    @Test fun edit_address_success_http_confirmation_and_ios_block() = runTest {
        for (allowHttp in listOf(true, false)) {
            val gateway = FakeSettingsGateway(snapshot())
            val controller = CatalogueSettingsController("cat", gateway, validator(), allowHttp)
            backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { controller.observe() }
            val edit = controller.openAddressEditor()!!
            edit.updateAddress("https://other.example/opds/")
            edit.submit()
            assertEquals(listOf("https://other.example/opds/"), gateway.addresses)
            assertEquals(listOf<CatalogueValidation>(CatalogueValidation.Accepted()), gateway.addressValidations)
            val http = controller.openAddressEditor()!!
            http.updateAddress("http://home.lan/opds")
            http.submit()
            assertEquals(if (allowHttp) CatalogueAddDialog.HttpWarning else CatalogueAddDialog.HttpBlocked, http.state.value.dialog)
            if (allowHttp) {
                http.confirmHttp()
                assertEquals("http://home.lan/opds", gateway.addresses.last())
            } else assertEquals(1, gateway.addresses.size)
        }
    }

    @Test fun removing_zero_one_or_many_books_only_removes_the_catalogue_after_confirmation() = runTest {
        for (count in listOf(0L, 1L, 12L)) {
            val gateway = FakeSettingsGateway(snapshot()).apply { books = count }
            val controller = CatalogueSettingsController("cat", gateway, validator(), true)
            backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { controller.observe() }
            controller.askRemoveCatalogue()
            assertEquals(count, controller.state.value.downloadedBooks)
            assertEquals(CatalogueSettingsDialog.RemoveCatalogue, controller.state.value.dialog)
            assertEquals(0, gateway.removedCatalogues)
            controller.confirmRemoveCatalogue()
            assertEquals(1, gateway.removedCatalogues)
            assertEquals(count, gateway.books)
        }
    }

    @Test fun source_removed_or_profile_switched_closes_settings_and_fences_late_validation() = runTest {
        for (switched in listOf(false, true)) {
            val gateway = FakeSettingsGateway(snapshot())
            val answer = CompletableDeferred<CatalogueValidation>()
            val controller = CatalogueSettingsController("cat", gateway, CatalogueAddressValidator { _, _ -> answer.await() }, true)
            backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { controller.observe() }
            controller.toggleAddressVisibility()
            val edit = controller.openAddressEditor()!!
            val work = launch { edit.submit() }
            runCurrent()
            gateway.snapshot.value = if (switched) snapshot().copy(profileId = "other-profile") else null
            answer.complete(CatalogueValidation.Accepted())
            work.join()
            assertTrue(controller.state.value.closed)
            assertNull(controller.state.value.source)
            assertFalse(controller.state.value.showAddress)
            assertTrue(gateway.addresses.isEmpty())
        }
    }

    @Test fun own_address_commit_does_not_cancel_the_account_save_when_the_registry_emits() = runTest {
        val gateway = FakeSettingsGateway(snapshot()).apply { emitAddressChanges = true }
        val controller = CatalogueSettingsController("cat", gateway, validator(), true)
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { controller.observe() }
        val edit = controller.openAddressEditor()!!
        edit.updateAddress("https://other.example/opds")
        edit.updateNeedsAccount(true)
        edit.updateUsername("new-account")
        edit.updatePassword("new-password")
        edit.submit()
        assertEquals("cat", edit.state.value.addedSourceId)
        assertEquals(listOf(OpdsAccountDetails("new-account", "new-password")), gateway.accounts)
        assertFalse(controller.state.value.showAddress)
    }

    @Test fun address_duplicates_and_storage_failures_do_not_close_the_editor() = runTest {
        val gateway = FakeSettingsGateway(snapshot()).apply { existing = setOf("https://other.example/opds") }
        val controller = CatalogueSettingsController("cat", gateway, validator(), true)
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { controller.observe() }
        val edit = controller.openAddressEditor()!!
        edit.updateAddress("https://other.example/opds")
        edit.submit()
        assertEquals(CatalogueAddError.DuplicateAddress, edit.state.value.error)
        assertTrue(gateway.addresses.isEmpty())
        gateway.fail = true
        edit.updateAddress("https://new.example/opds")
        edit.submit()
        assertEquals(CatalogueAddError.SaveFailed, edit.state.value.error)
        assertNull(edit.state.value.addedSourceId)
    }

    @Test fun edit_address_never_sends_a_password_over_http() = runTest {
        val gateway = FakeSettingsGateway(snapshot())
        var sent: OpdsAccountDetails? = null
        var answer: CatalogueValidation = CatalogueValidation.Accepted()
        val controller = CatalogueSettingsController("cat", gateway, CatalogueAddressValidator { _, account -> sent = account; answer }, true)
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { controller.observe() }
        val edit = controller.openAddressEditor()!!
        edit.updateAddress("http://home.lan/opds")
        edit.updateNeedsAccount(true)
        edit.updateUsername("rok")
        edit.updatePassword("secret")
        edit.submit()
        assertNull(sent)
        assertEquals(CatalogueAddDialog.PasswordHttp, edit.state.value.dialog)
        assertTrue(gateway.accounts.isEmpty())
        edit.addWithoutAccount()
        assertTrue(gateway.accounts.isEmpty())
        answer = CatalogueValidation.NeedsBasic
        val blocked = controller.openAddressEditor()!!
        blocked.updateAddress("http://home.lan/private")
        blocked.submit()
        assertEquals(CatalogueAddDialog.PasswordHttpBlocked, blocked.state.value.dialog)
    }

    @Test fun failed_remove_count_does_not_offer_a_confirmation_with_a_guessed_count() = runTest {
        val gateway = FakeSettingsGateway(snapshot()).apply { fail = true }
        val controller = CatalogueSettingsController("cat", gateway, validator(), true)
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { controller.observe() }
        controller.askRemoveCatalogue()
        assertNull(controller.state.value.dialog)
        assertNull(controller.state.value.downloadedBooks)
        assertTrue(controller.state.value.failed)
        controller.confirmRemoveCatalogue()
        assertEquals(0, gateway.removedCatalogues)
    }

    @Test fun remove_account_updates_the_open_account_group_without_rewriting_last_verified_access() = runTest {
        val signedIn = snapshot().copy(status = CatalogueAccessStatus(ServerAccessState.SignedIn("rok")), accountName = "rok")
        val gateway = FakeSettingsGateway(signedIn)
        val controller = CatalogueSettingsController("cat", gateway, validator(), true)
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { controller.observe() }
        controller.askRemoveAccount()
        controller.confirmRemoveAccount()
        assertNull(controller.state.value.source?.accountName)
        assertEquals(ServerAccessState.SignedIn("rok"), controller.state.value.source?.status?.access)
    }

    private fun snapshot() = CatalogueSettingsSource("profile", source, CatalogueAccessStatus(), null)
    private fun validator(result: CatalogueValidation = CatalogueValidation.Accepted()) = CatalogueAddressValidator { _, _ -> result }
}

private class FakeSettingsGateway(initial: CatalogueSettingsSource) : CatalogueSettingsGateway {
    val snapshot = MutableStateFlow<CatalogueSettingsSource?>(initial)
    val accounts = mutableListOf<OpdsAccountDetails>()
    val addresses = mutableListOf<String>()
    val addressValidations = mutableListOf<CatalogueValidation>()
    val enabled = mutableListOf<Boolean>()
    var books = 0L
    var removedAccounts = 0
    var removedCatalogues = 0
    var emitAddressChanges = false
    var existing = emptySet<String>()
    var fail = false
    override fun observeSource(sourceId: String) = snapshot
    override suspend fun existingAddresses(profileId: String, sourceId: String) = existing
    override suspend fun updateAddress(source: CatalogueSettingsSource, address: String, account: OpdsAccountDetails?, validation: CatalogueValidation) {
        check(!fail)
        addresses += address
        addressValidations += validation
        if (emitAddressChanges) snapshot.value = source.copy(config = source.config.copy(baseUrl = address))
        kotlinx.coroutines.yield()
        account?.let { accounts += it }
    }
    override suspend fun saveAccount(source: CatalogueSettingsSource, account: OpdsAccountDetails) { accounts += account }
    override suspend fun removeAccount(source: CatalogueSettingsSource) { removedAccounts++ }
    override suspend fun setEnabled(source: CatalogueSettingsSource, enabled: Boolean) { this.enabled += enabled }
    override suspend fun countBooks(source: CatalogueSettingsSource): Long { check(!fail); return books }
    override suspend fun removeCatalogue(source: CatalogueSettingsSource) { removedCatalogues++ }
    override suspend fun retry(source: CatalogueSettingsSource) = Unit
}
