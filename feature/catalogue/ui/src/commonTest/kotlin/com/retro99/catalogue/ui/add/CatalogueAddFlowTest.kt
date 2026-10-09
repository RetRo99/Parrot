package com.retro99.catalogue.ui.add

import com.retro99.catalogue.domain.AcquisitionFailureReason
import com.retro99.catalogue.domain.AcquisitionState
import com.retro99.server.api.OpdsAccountDetails
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class CatalogueAddFlowTest {
    @Test
    fun maps_web_page_unreachable_and_non_catalogue_to_address_errors_without_saving() = runTest {
        val cases = listOf(
            CatalogueValidation.WebPage to CatalogueAddError.WebPage,
            CatalogueValidation.Unreachable to CatalogueAddError.Unreachable,
            CatalogueValidation.NotCatalogue to CatalogueAddError.NotCatalogue,
        )

        cases.forEach { (answer, expectedError) ->
            val harness = Harness(answer)
            harness.flow.submit()
            assertEquals(expectedError, harness.flow.state.value.error)
            assertTrue(harness.store.added.isEmpty())
        }
    }

    @Test
    fun basic_challenge_turns_on_account_fields_and_shows_the_sign_in_message() = runTest {
        val harness = Harness(CatalogueValidation.NeedsBasic)

        harness.flow.submit()

        assertTrue(harness.flow.state.value.needsAccount)
        assertEquals(CatalogueAddError.SignInNeeded, harness.flow.state.value.error)
        assertTrue(harness.store.added.isEmpty())
    }

    @Test
    fun unsupported_first_page_is_blocked_but_a_public_first_page_can_be_added_without_an_account() = runTest {
        val blocked = Harness(CatalogueValidation.Unsupported(rootAnswered401 = true))
        blocked.flow.submit()
        assertEquals(CatalogueAddDialog.UnsupportedBlocked, blocked.flow.state.value.dialog)
        blocked.flow.confirmDialogPrimary()
        assertTrue(blocked.store.added.isEmpty())

        val visible = Harness(CatalogueValidation.Unsupported(rootAnswered401 = false))
        visible.flow.submit()
        assertEquals(CatalogueAddDialog.Unsupported, visible.flow.state.value.dialog)
        visible.flow.addWithoutAccount()
        assertEquals(1, visible.store.added.size)
        assertNull(visible.store.added.single().account)
    }

    @Test
    fun certificate_failure_has_no_continue_action_and_saves_nothing() = runTest {
        val harness = Harness(CatalogueValidation.CertificateFailure)
        harness.flow.submit()

        assertEquals(CatalogueAddDialog.Certificate, harness.flow.state.value.dialog)
        harness.flow.addWithoutAccount()
        assertTrue(harness.store.added.isEmpty())
    }

    @Test
    fun checking_and_signing_in_are_distinct_read_only_phases() = runTest {
        val checkingAnswer = CompletableDeferred<CatalogueValidation>()
        val checking = Harness(CatalogueValidation.Accepted(), validator = { _, _ -> checkingAnswer.await() })
        val anonymousSubmit = async { checking.flow.submit() }
        runCurrent()
        assertEquals(CatalogueAddPhase.Checking, checking.flow.state.value.phase)
        checkingAnswer.complete(CatalogueValidation.Accepted())
        anonymousSubmit.await()

        val signInAnswer = CompletableDeferred<CatalogueValidation>()
        val signingIn = Harness(
            CatalogueValidation.Accepted(),
            validator = { _, _ -> signInAnswer.await() },
        )
        signingIn.flow.updateNeedsAccount(true)
        signingIn.flow.updateUsername("patron")
        signingIn.flow.updatePassword("secret")
        val accountSubmit = async { signingIn.flow.submit() }
        runCurrent()
        assertEquals(CatalogueAddPhase.SigningIn, signingIn.flow.state.value.phase)
        assertFalse(signingIn.flow.state.value.isEditable)
        assertEquals(listOf("signing-in"), signingIn.validatorPhases)
        signInAnswer.complete(CatalogueValidation.Accepted())
        accountSubmit.await()
    }

    @Test
    fun android_http_can_be_confirmed_but_ios_http_is_blocked_before_fetching() = runTest {
        val android = Harness(CatalogueValidation.Accepted(), allowHttp = true, address = "http://books.home.lan/opds/")
        android.flow.submit()
        assertEquals(CatalogueAddDialog.HttpWarning, android.flow.state.value.dialog)
        assertTrue(android.store.added.isEmpty())
        android.flow.confirmHttp()
        assertEquals("http://books.home.lan/opds/", android.store.added.single().address)

        val ios = Harness(CatalogueValidation.Accepted(), allowHttp = false, address = "http://books.home.lan/opds/")
        ios.flow.submit()
        assertEquals(CatalogueAddDialog.HttpBlocked, ios.flow.state.value.dialog)
        assertTrue(ios.validatorPhases.isEmpty())
        assertTrue(ios.store.added.isEmpty())
    }

    @Test
    fun http_password_is_never_sent_and_the_open_or_blocked_page_selects_the_matching_dialog() = runTest {
        val public = Harness(CatalogueValidation.Accepted(), allowHttp = true, address = "http://books.home.lan/opds/")
        public.flow.updateNeedsAccount(true)
        public.flow.updateUsername("patron")
        public.flow.updatePassword("must-not-send")
        public.flow.submit()
        assertEquals(CatalogueAddDialog.PasswordHttp, public.flow.state.value.dialog)
        assertNull(public.validatorInputs.single().second)
        public.flow.addWithoutAccount()
        assertNull(public.store.added.single().account)

        val private = Harness(CatalogueValidation.NeedsBasic, allowHttp = true, address = "http://books.home.lan/opds/")
        private.flow.updateNeedsAccount(true)
        private.flow.updateUsername("patron")
        private.flow.updatePassword("must-not-send")
        private.flow.submit()
        assertEquals(CatalogueAddDialog.PasswordHttpBlocked, private.flow.state.value.dialog)
        assertNull(private.validatorInputs.single().second)
        private.flow.changeAddress()
        assertTrue(private.store.added.isEmpty())
    }

    @Test
    fun credentials_are_never_sent_to_non_http_addresses_and_whitespace_is_trimmed() = runTest {
        val unsupported = Harness(CatalogueValidation.Accepted(), address = "ftp://books.home.lan/opds/")
        unsupported.flow.updateNeedsAccount(true)
        unsupported.flow.updateUsername("patron")
        unsupported.flow.updatePassword("secret")
        unsupported.flow.submit()

        assertEquals(CatalogueAddError.Unreachable, unsupported.flow.state.value.error)
        assertTrue(unsupported.validatorInputs.isEmpty())
        assertTrue(unsupported.store.added.isEmpty())

        val spaced = Harness(CatalogueValidation.Accepted(), address = "  https://books.home.lan/opds/  ")
        spaced.flow.submit()
        assertEquals("https://books.home.lan/opds/", spaced.validatorInputs.single().first)
        assertEquals("https://books.home.lan/opds/", spaced.store.added.single().address)
    }

    @Test
    fun cancelling_a_request_prevents_a_late_success_from_adding_anything() = runTest {
        val lateAnswer = CompletableDeferred<CatalogueValidation>()
        val harness = Harness(
            CatalogueValidation.Accepted(),
            validator = { _, _ -> withContext(NonCancellable) { lateAnswer.await() } },
        )
        val pending = async { harness.flow.submit() }
        runCurrent()
        harness.flow.cancel()
        lateAnswer.complete(CatalogueValidation.Accepted())
        pending.join()

        assertTrue(harness.store.added.isEmpty())
        assertEquals(CatalogueAddPhase.Idle, harness.flow.state.value.phase)
    }

    @Test
    fun preset_add_saves_account_only_after_a_successful_check() = runTest {
        val failed = Harness(CatalogueValidation.InvalidCredentials, address = PRESET_ADDRESS)
        failed.flow.updateNeedsAccount(true)
        failed.flow.updateUsername("patron@example.org")
        failed.flow.updatePassword("")
        failed.flow.submit(name = "Standard Ebooks")
        assertEquals(CatalogueAddError.WrongCredentials, failed.flow.state.value.error)
        assertEquals("", failed.flow.state.value.password)
        assertTrue(failed.store.added.isEmpty())

        val accepted = Harness(CatalogueValidation.Accepted("ignored preset title"), address = PRESET_ADDRESS)
        accepted.flow.updateNeedsAccount(true)
        accepted.flow.updateUsername("patron@example.org")
        accepted.flow.updatePassword("")
        accepted.flow.submit(name = "Standard Ebooks")
        assertEquals("Standard Ebooks", accepted.store.added.single().name)
        assertEquals(OpdsAccountDetails("patron@example.org", ""), accepted.store.added.single().account)
    }

    @Test
    fun preset_without_an_account_is_added_after_the_first_page_succeeds() = runTest {
        val harness = Harness(CatalogueValidation.Accepted("Ignored catalogue title"), address = GUTENBERG_ADDRESS)
        harness.flow.submit(name = "Project Gutenberg")
        assertEquals("Project Gutenberg", harness.store.added.single().name)
        assertNull(harness.store.added.single().account)
    }

    @Test
    fun custom_catalogue_uses_trimmed_title_from_the_validated_first_page() = runTest {
        val harness = Harness(CatalogueValidation.Accepted("  My Reading Room  "))

        harness.flow.submit()

        assertEquals("My Reading Room", harness.store.added.single().name)
    }

    @Test
    fun custom_catalogue_without_a_title_falls_back_to_the_host() = runTest {
        val harness = Harness(CatalogueValidation.Accepted("  "), address = "https://books.example/opds")

        harness.flow.submit()

        assertEquals("books.example", harness.store.added.single().name)
    }

    @Test
    fun custom_catalogue_title_is_trimmed_and_capped_at_sixty_characters() = runTest {
        val title = "  ${"A".repeat(80)}  "
        val harness = Harness(CatalogueValidation.Accepted(title))

        harness.flow.submit()

        assertEquals("A".repeat(60), harness.store.added.single().name)
    }

    @Test
    fun duplicate_address_is_refused_exactly_and_editing_an_error_keeps_the_typed_address() = runTest {
        val address = "https://books.home.lan/opds/?key=secret"
        val harness = Harness(CatalogueValidation.Accepted(), address = address, existingAddresses = setOf(address))
        harness.flow.submit()
        assertEquals(CatalogueAddError.DuplicateAddress, harness.flow.state.value.error)
        assertTrue(harness.validatorInputs.isEmpty())
        assertTrue(harness.store.added.isEmpty())

        harness.flow.updateAddress("$address/")
        assertEquals("$address/", harness.flow.state.value.address)
    }

    @Test
    fun downloads_count_includes_running_failed_and_interrupted_but_not_finished() {
        val states = listOf(
            AcquisitionState.Waiting,
            AcquisitionState.Downloading,
            AcquisitionState.Checking,
            AcquisitionState.Adding,
            AcquisitionState.Failed(AcquisitionFailureReason.Connection),
            AcquisitionState.Interrupted,
            AcquisitionState.Done,
        )
        assertEquals(6, activeOrFailedCatalogueDownloads(states))
    }

    private class Harness(
        answer: CatalogueValidation,
        allowHttp: Boolean = true,
        address: String = "https://books.home.lan/opds/",
        existingAddresses: Set<String> = emptySet(),
        validator: suspend (String, OpdsAccountDetails?) -> CatalogueValidation = { _, _ -> answer },
    ) {
        val store = FakeStore(existingAddresses)
        val validatorInputs = mutableListOf<Pair<String, OpdsAccountDetails?>>()
        val validatorPhases = mutableListOf<String>()
        private val realValidator = CatalogueAddressValidator { url, account ->
            validatorInputs += url to account
            validatorPhases += if (account == null) "checking" else "signing-in"
            validator(url, account)
        }
        val flow = CatalogueAddFlow(realValidator, store, allowHttp, initialAddress = address)
    }

    private class FakeStore(private val existing: Set<String>) : CatalogueAddStore {
        data class Added(val name: String, val address: String, val account: OpdsAccountDetails?)
        val added = mutableListOf<Added>()
        override suspend fun existingAddresses(): Set<String> = existing + added.map { it.address }
        override suspend fun addValidated(name: String, address: String, account: OpdsAccountDetails?): String {
            added += Added(name, address, account)
            return "source-${added.size}"
        }
    }

    private companion object {
        const val GUTENBERG_ADDRESS = "https://www.gutenberg.org/ebooks/search.opds/"
        const val PRESET_ADDRESS = "https://standardebooks.org/feeds/opds"
    }
}
