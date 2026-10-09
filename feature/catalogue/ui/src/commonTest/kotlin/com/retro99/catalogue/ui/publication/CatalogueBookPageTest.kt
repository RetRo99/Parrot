package com.retro99.catalogue.ui.publication

import com.retro99.catalogue.domain.*
import com.retro99.catalogue.ui.browse.*
import com.retro99.catalogue.ui.navigation.CatalogueBookPlace
import com.retro99.server.api.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.*
import kotlin.test.*

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class CatalogueBookPageTest {
    private class Queue : CatalogueAcquisitionManager {
        val rows = MutableStateFlow<List<CatalogueAcquisition>>(emptyList())
        val requests = mutableListOf<CatalogueAcquisitionRequest>()
        val cancelled = mutableListOf<String>()
        val actions = mutableListOf<String>()
        override fun observeAcquisitions() = rows
        override suspend fun request(request: CatalogueAcquisitionRequest): CatalogueRequestOutcome {
            requests += request
            val row = acquisition(AcquisitionState.Waiting)
            rows.value = listOf(row)
            return CatalogueRequestOutcome.Queued(row)
        }
        override suspend fun cancel(requestId: String): Boolean { cancelled += requestId; rows.value = emptyList(); return true }
        override suspend fun retry(requestId: String): Boolean { actions += "retry"; return true }
        override suspend fun dismiss(requestId: String): Boolean { actions += "dismiss"; rows.value = emptyList(); return true }
        override suspend fun startAgain(requestId: String): Boolean { actions += "startAgain"; return true }
        override suspend fun signedIn(sourceId: String) { actions += "signedIn" }
        override suspend fun start() {}
        override suspend fun restoreAfterRestart() {}
        override suspend fun finishedInLast24Hours() = emptyList<CatalogueAcquisition>()
        override suspend fun purgeFinished() {}
        override suspend fun purgeExpired() {}
    }
    private class Harness(scope: TestScope, missing: Boolean = false, val publications: List<CataloguePublication> = listOf(book("Treasure Island"))) {
        val gateway = FakeGateway()
        val queue = Queue()
        val libraryRows = mutableMapOf<CatalogueEntryIdentity, String>()
        val library = object : CatalogueLibraryLookup {
            override suspend fun libraryBooksFor(sourceId: String, entries: Collection<CatalogueEntryIdentity>) = libraryRows.filterKeys { it in entries }
        }
        val page = CatalogueBookPage(SOURCE, if (missing) null else CatalogueBookPlace(FakeTarget("listing"), publications), gateway, library, queue, scope.backgroundScope)
        val state get() = page.state.value
        suspend fun loaded(scope: TestScope) {
            scope.runCurrent()
            gateway.repository.answer("listing", feed("listing", books = publications))
            scope.runCurrent()
        }
    }

    @Test fun main_action_updates_live_through_every_state_and_cancel_returns_to_download() = runTest {
        val h = Harness(this); h.loaded(this)
        assertIs<BookMainAction.Download>(h.state.action)
        h.page.download(); runCurrent()
        assertIs<BookMainAction.Waiting>(h.state.action)
        assertTrue(h.state.downloadNotice)
        h.queue.rows.value = listOf(acquisition(AcquisitionState.Downloading)); runCurrent()
        val known = assertIs<BookMainAction.Downloading>(h.state.action)
        assertEquals(500_000, known.bytes); assertEquals(1_200_000, known.total)
        h.queue.rows.value = listOf(acquisition(AcquisitionState.Downloading).copy(expectedSizeBytes = null, bytesSoFar = 1_400_000)); runCurrent()
        assertNull(assertIs<BookMainAction.Downloading>(h.state.action).total)
        for (adding in listOf(AcquisitionState.Checking, AcquisitionState.Adding)) {
            h.queue.rows.value = listOf(acquisition(adding)); runCurrent()
            assertEquals(BookMainAction.Adding, h.state.action)
            h.page.cancelDownload(); runCurrent(); assertTrue(h.queue.cancelled.isEmpty())
        }
        h.queue.rows.value = listOf(acquisition(AcquisitionState.Done).copy(libraryBookId = "uuid", completedAt = 123)); runCurrent()
        assertEquals(BookMainAction.Done("uuid", 123), h.state.action)
        h.page.cancelDownload(); runCurrent(); assertTrue(h.queue.cancelled.isEmpty())
        h.page.readNow(); assertEquals(BookNavigation.Read("uuid"), h.state.navigation)
        for (cancellable in listOf(AcquisitionState.Waiting, AcquisitionState.Downloading)) {
            h.queue.rows.value = listOf(acquisition(cancellable)); runCurrent()
            h.page.cancelDownload(); runCurrent(); assertIs<BookMainAction.Download>(h.state.action)
        }
        assertEquals(listOf("request", "request"), h.queue.cancelled)
    }

    @Test fun download_is_not_offered_before_the_session_has_loaded_the_entry_or_after_load_failure() = runTest {
        val h = Harness(this); runCurrent()
        assertNull(h.state.action)
        h.page.download(); runCurrent(); assertTrue(h.queue.requests.isEmpty())
        h.gateway.repository.answer("listing", failure(CatalogueErrorKind.Timeout)); runCurrent()
        assertTrue(h.state.loadFailed)
        assertNull(h.state.action)
    }

    @Test fun partial_entry_follows_its_full_entry_link_and_download_uses_that_document() = runTest {
        val partial = book("Treasure Island").copy(links = listOf(link("full", relations = listOf("alternate")).copy(
            mediaType = CatalogueMediaType("application", "atom+xml", mapOf("type" to "entry", "profile" to "opds-catalog")))))
        val h = Harness(this, publications = listOf(partial)); h.loaded(this)
        assertEquals(listOf("listing", "full"), h.gateway.repository.requested)
        assertNull(h.state.action)
        val full = partial.copy(summary = text("Full description"), acquisitionChoices = book("Treasure Island", files = 2).acquisitionChoices)
        h.gateway.repository.answer("full", bookDocument(full, "full")); runCurrent()
        assertEquals("Full description", h.state.book!!.summary.display())
        assertEquals(2, h.state.files.size)
        h.page.download(); runCurrent()
        assertEquals("$ORIGIN/full", h.queue.requests.single().listingUrl)
    }

    @Test fun completed_mapping_stays_live_when_finished_queue_rows_are_removed() = runTest {
        val h = Harness(this); h.loaded(this)
        h.libraryRows[CatalogueEntryIdentity("id:Treasure Island")] = "library-uuid"
        h.queue.rows.value = listOf(acquisition(AcquisitionState.Done).copy(libraryBookId = "library-uuid")); runCurrent()
        h.queue.rows.value = emptyList(); runCurrent()
        assertEquals(BookMainAction.Done("library-uuid", null), h.state.action)
    }

    @Test fun waiting_counts_only_other_running_downloads() = runTest {
        val h = Harness(this); h.loaded(this)
        for (count in 1..2) {
            h.queue.rows.value = listOf(acquisition(AcquisitionState.Waiting)) + (1..count).map { acquisition(AcquisitionState.Downloading).copy(requestId = "other$it", publicationKey = "other$it") }
            runCurrent(); assertEquals(count, assertIs<BookMainAction.Waiting>(h.state.action).otherDownloads)
        }
    }

    @Test fun file_selection_is_guarded_and_download_snapshots_the_selected_edition() = runTest {
        val first = book("Treasure Island", files = 2, edition = "Illustrated")
        val second = book("Treasure Island", id = "edition2", files = 2)
        val unavailable = second.acquisitionChoices.last().let { it.copy(isOpenable = false, action = CatalogueAcquisitionAction.NotAvailable(CatalogueUnavailableReason.UnsupportedFormat)) }
        val h = Harness(this, publications = listOf(first, second.copy(acquisitionChoices = listOf(second.acquisitionChoices.first(), unavailable))))
        h.loaded(this)
        assertEquals(1, h.state.selectedOrdinal)
        h.page.openFiles(); assertTrue(h.state.chooseFile)
        h.page.chooseFile(4); assertEquals(1, h.state.selectedOrdinal)
        h.page.chooseFile(3); assertEquals(3, h.state.selectedOrdinal)
        h.page.download(); runCurrent()
        assertFalse(h.state.chooseFile)
        assertEquals("edition2", h.queue.requests.single().publicationKey)
        assertEquals("application/epub+zip#1", h.queue.requests.single().representationKey)
    }

    @Test fun blocked_card_never_queues_even_a_sample_and_page_without_rights_has_no_invented_metadata() = runTest {
        for (reason in CatalogueUnavailableReason.entries) {
            val p = book("Treasure Island", author = null, files = 0).copy(acquisitionAction = CatalogueAcquisitionAction.NotAvailable(reason))
            val h = Harness(this, publications = listOf(p)); h.loaded(this)
            assertEquals(reason, assertIs<BookMainAction.Blocked>(h.state.action).blocked.reason)
            h.page.download(); runCurrent(); assertTrue(h.queue.requests.isEmpty())
            assertNull(h.state.book!!.rights); assertTrue(h.state.book!!.languages.isEmpty())
        }
    }

    @Test fun late_sign_in_answer_after_profile_change_never_saves_details_or_resumes_a_queue() = runTest {
        val h = Harness(this); h.loaded(this)
        h.queue.rows.value = listOf(acquisition(AcquisitionState.Failed(AcquisitionFailureReason.SignIn))); runCurrent()
        h.page.signIn("rok", "secret"); runCurrent()
        h.gateway.source.value = CatalogueBrowseSource("different-profile", "Different", ORIGIN); runCurrent()
        assertTrue(h.state.closed, "profile change did not close page")
        h.gateway.repository.answer("listing", feed("listing", books = h.publications)); runCurrent()
        assertTrue(h.gateway.savedAccounts.isEmpty(), "late answer saved credentials")
        assertTrue(h.queue.actions.isEmpty(), "late answer resumed queue: ${h.queue.actions}")
        assertTrue(h.state.closed, "closed page reopened: ${h.state}")
    }

    @Test fun failure_actions_follow_downloads_copy_and_are_not_cancel() = runTest {
        val h = Harness(this); h.loaded(this)
        for (reason in AcquisitionFailureReason.entries) {
            h.queue.rows.value = listOf(acquisition(AcquisitionState.Failed(reason))); runCurrent()
            val action = assertIs<BookMainAction.Failed>(h.state.action)
            assertEquals(reason, action.acquisition.state.failureReason)
            h.page.cancelDownload(); runCurrent(); assertTrue(h.queue.cancelled.isEmpty())
            h.page.failureAction(); runCurrent()
            if (reason == AcquisitionFailureReason.SignIn) assertNotNull(h.state.signIn)
            else assertEquals(if (reason in listOf(AcquisitionFailureReason.TooLarge, AcquisitionFailureReason.Invalid, AcquisitionFailureReason.Protected)) "dismiss" else "retry", h.queue.actions.last())
        }
        h.queue.rows.value = listOf(acquisition(AcquisitionState.Interrupted)); runCurrent()
        h.page.failureAction(); runCurrent(); assertEquals("startAgain", h.queue.actions.last())
    }

    @Test fun sign_in_is_not_saved_until_success_and_continues_the_download() = runTest {
        val h = Harness(this); h.loaded(this)
        h.queue.rows.value = listOf(acquisition(AcquisitionState.Failed(AcquisitionFailureReason.SignIn))); runCurrent()
        assertNotNull(h.state.signIn)
        h.page.signIn("rok", "wrong"); runCurrent()
        assertTrue(h.gateway.savedAccounts.isEmpty())
        h.gateway.repository.answer("listing", failure(CatalogueErrorKind.SignInNeeded)); runCurrent()
        assertTrue(h.gateway.savedAccounts.isEmpty()); assertTrue(h.state.signIn!!.wrongDetails)
        h.page.signIn("rok", ""); runCurrent()
        h.gateway.repository.answer("listing", feed("listing", books = listOf(book("Treasure Island")))); runCurrent()
        assertEquals(listOf(OpdsAccountDetails("rok", "")), h.gateway.savedAccounts)
        assertEquals(listOf("signedIn"), h.queue.actions); assertNull(h.state.signIn)
    }

    @Test fun returning_rechecks_library_and_leaving_does_not_cancel_queue() = runTest {
        val h = Harness(this); h.loaded(this)
        h.libraryRows[CatalogueEntryIdentity("id:Treasure Island")] = "library-uuid"
        h.page.onReturn(); runCurrent()
        assertEquals(BookMainAction.Done("library-uuid", null), h.state.action)
        h.libraryRows.clear(); h.page.onReturn(); runCurrent()
        assertIs<BookMainAction.Download>(h.state.action)
        h.page.cancel(); assertTrue(h.queue.cancelled.isEmpty())
        h.queue.rows.value = listOf(acquisition(AcquisitionState.Downloading)); runCurrent()
        assertIs<BookMainAction.Download>(h.state.action)
    }

    @Test fun lost_reference_returns_to_catalogue_root() = runTest {
        val h = Harness(this, missing = true); runCurrent()
        assertEquals(BookNavigation.CatalogueRoot, h.state.navigation)
        assertTrue(h.gateway.repository.requested.isEmpty())
    }

    @Test fun disabled_source_or_changed_profile_closes_without_canceling_another_profiles_queue() = runTest {
        for (next in listOf(null, CatalogueBrowseSource("profile-2", "Other", ORIGIN))) {
            val h = Harness(this); h.loaded(this)
            h.gateway.source.value = next; runCurrent()
            assertTrue(h.state.closed); assertNull(h.state.book)
            assertTrue(h.queue.cancelled.isEmpty())
        }
    }

    companion object {
        fun acquisition(state: AcquisitionState) = CatalogueAcquisition("request", SOURCE, "id:Treasure Island", "application/epub+zip#1", null, "Treasure Island", "Robert Louis Stevenson", null, "Project Gutenberg", state, 1, 1_200_000, 500_000, null, null, null, 0, 0, null, 1)
    }
}
