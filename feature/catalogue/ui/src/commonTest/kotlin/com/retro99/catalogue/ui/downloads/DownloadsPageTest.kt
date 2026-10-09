package com.retro99.catalogue.ui.downloads

import com.retro99.catalogue.domain.*
import com.retro99.catalogue.ui.browse.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.*
import kotlin.test.*

internal class TestDownloadQueue : CatalogueAcquisitionManager {
    val rows = MutableStateFlow<List<CatalogueAcquisition>>(emptyList())
    val actions = mutableListOf<String>()
    val requests = mutableListOf<CatalogueAcquisitionRequest>()
    var now = 0L
    override fun observeAcquisitions() = rows
    override suspend fun request(request: CatalogueAcquisitionRequest): CatalogueRequestOutcome {
        requests += request
        val row = downloadFixture(AcquisitionState.Waiting).copy(sourceId = request.sourceId, publicationKey = request.publicationKey, detailIdentity = request.detailIdentity)
        rows.value = rows.value + row
        return CatalogueRequestOutcome.Queued(row)
    }
    override suspend fun cancel(requestId: String): Boolean { actions += "cancel:$requestId"; rows.value = rows.value.filterNot { it.requestId == requestId }; return true }
    override suspend fun retry(requestId: String): Boolean { actions += "retry:$requestId"; return true }
    override suspend fun dismiss(requestId: String): Boolean { actions += "dismiss:$requestId"; rows.value = rows.value.filterNot { it.requestId == requestId }; return true }
    override suspend fun startAgain(requestId: String): Boolean { actions += "startAgain:$requestId"; return true }
    override suspend fun signedIn(sourceId: String) { actions += "signedIn:$sourceId" }
    override suspend fun start() { actions += "start" }
    override suspend fun restoreAfterRestart() {}
    override suspend fun finishedInLast24Hours() = rows.value.filter { it.state == AcquisitionState.Done }
    override suspend fun purgeFinished() { actions += "purgeFinished"; rows.value = rows.value.filterNot { it.state == AcquisitionState.Done } }
    override suspend fun purgeExpired() { actions += "purgeExpired"; rows.value = rows.value.filterNot { it.state == AcquisitionState.Done && (it.completedAt ?: 0) + CatalogueAcquisitionLimits.FINISHED_RETENTION_MILLIS <= now } }
}

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class DownloadsPageTest {
    @Test fun liveRowsDispatchEveryActionAndOpenOnlyCompletedLibraryBooks() = runTest {
        val queue = TestDownloadQueue()
        val page = DownloadsPage(queue, FakeGateway(), MutableStateFlow("profile-1"), backgroundScope, { testScheduler.currentTime })
        runCurrent()
        val states = listOf(AcquisitionState.Waiting, AcquisitionState.Downloading, AcquisitionState.Checking, AcquisitionState.Adding, AcquisitionState.Done, AcquisitionState.Interrupted) + AcquisitionFailureReason.entries.map { AcquisitionState.Failed(it) }
        for (state in states) {
            queue.rows.value = listOf(downloadFixture(state).copy(sourceId = SOURCE, completedAt = 0)); runCurrent()
            val before = queue.actions.size
            page.action("request"); runCurrent()
            when (state) {
                AcquisitionState.Checking, AcquisitionState.Adding -> assertEquals(before, queue.actions.size)
                AcquisitionState.Done -> { assertEquals("library", page.state.value.openBookId); page.navigationHandled() }
                AcquisitionState.Waiting, AcquisitionState.Downloading -> assertEquals("cancel:request", queue.actions.last())
                AcquisitionState.Interrupted -> assertEquals("startAgain:request", queue.actions.last())
                is AcquisitionState.Failed -> when (state.reason) {
                    AcquisitionFailureReason.SignIn -> assertEquals(SOURCE, page.state.value.signInSourceId)
                    AcquisitionFailureReason.TooLarge, AcquisitionFailureReason.Invalid, AcquisitionFailureReason.Protected -> assertEquals("dismiss:request", queue.actions.last())
                    else -> assertEquals("retry:request", queue.actions.last())
                }
            }
        }
    }

    @Test fun finishedStayUntilLeaveOrExactly24HoursAndRunningRowsStay() = runTest {
        val queue = TestDownloadQueue()
        val page = DownloadsPage(queue, FakeGateway(), MutableStateFlow("profile-1"), backgroundScope, { queue.now })
        runCurrent()
        queue.rows.value = listOf(downloadFixture(AcquisitionState.Done, "done", 100), downloadFixture(AcquisitionState.Downloading, "running")); runCurrent()
        assertEquals(2, page.state.value.rows.size)
        queue.now = 100 + CatalogueAcquisitionLimits.FINISHED_RETENTION_MILLIS
        advanceTimeBy(queue.now); runCurrent()
        assertEquals(listOf("running"), page.state.value.rows.map { it.acquisition.requestId })
        queue.rows.value += downloadFixture(AcquisitionState.Done, "new", queue.now); runCurrent()
        page.leave(); runCurrent()
        assertEquals(listOf("running"), queue.rows.value.map { it.requestId })
        assertFalse(queue.actions.any { it.startsWith("cancel:") })
    }

    @Test fun signInSavesOnlyVerifiedDetailsAndContinuesOnlyThatCatalogue() = runTest {
        val queue = TestDownloadQueue()
        val gateway = FakeGateway()
        val page = DownloadsPage(queue, gateway, MutableStateFlow("profile-1"), backgroundScope)
        runCurrent()
        queue.rows.value = listOf(downloadFixture(AcquisitionState.Failed(AcquisitionFailureReason.SignIn)).copy(sourceId = SOURCE)); runCurrent()
        page.action("request"); runCurrent()
        page.signIn("rok", "wrong"); runCurrent()
        gateway.repository.answer("root", failure(com.retro99.server.api.CatalogueErrorKind.SignInNeeded)); runCurrent()
        assertTrue(gateway.savedAccounts.isEmpty()); assertTrue(page.state.value.signIn!!.wrongDetails)
        page.signIn("rok", ""); runCurrent()
        gateway.repository.answer("root", feed()); runCurrent()
        assertEquals("", gateway.savedAccounts.single().password)
        assertEquals("signedIn:$SOURCE", queue.actions.last())
        assertNull(page.state.value.signIn)
    }

    @Test fun profileChangeDuringSignInDropsLateAnswerAndNeverPurgesNewProfile() = runTest {
        val queue = TestDownloadQueue(); val gateway = FakeGateway(); val profiles = MutableStateFlow<String?>("profile-1")
        val page = DownloadsPage(queue, gateway, profiles, backgroundScope)
        runCurrent()
        queue.rows.value = listOf(downloadFixture(AcquisitionState.Failed(AcquisitionFailureReason.SignIn)).copy(sourceId = SOURCE)); runCurrent()
        page.action("request"); runCurrent(); page.signIn("rok", "secret"); runCurrent()
        profiles.value = "other"; runCurrent()
        gateway.repository.answer("root", feed()); runCurrent()
        page.leave(); runCurrent()
        assertTrue(page.state.value.closed)
        assertTrue(gateway.savedAccounts.isEmpty()); assertFalse(queue.actions.contains("purgeFinished")); assertFalse(queue.actions.any { it.startsWith("signedIn:") })
    }
}
