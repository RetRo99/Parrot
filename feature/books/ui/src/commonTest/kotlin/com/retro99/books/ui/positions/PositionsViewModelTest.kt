package com.retro99.books.ui.positions

import androidx.lifecycle.ViewModelStore
import com.retro99.analytics.api.*
import com.retro99.books.domain.model.BookHome
import com.retro99.books.domain.model.links.*
import com.retro99.reader.domain.model.PositionDomainModel
import com.retro99.reader.domain.positions.*
import com.retro99.reader.domain.translate.TranslationFailure
import com.retro99.reader.domain.usecase.ApplyResult
import com.retro99.reader.domain.write.CopyWriteResult
import com.retro99.translations.StringRes
import resources.translations.positions_missing_file
import resources.translations.positions_no_match
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.test.*
import kotlin.test.*

@OptIn(ExperimentalCoroutinesApi::class)
class PositionsViewModelTest {
    private fun copy(id: String) = LinkedCopy(CopyKey(CopySource.Storyteller, id), "server", id,
        "The Lantern Ferry", BookHome.Storyteller, true, false, false, true)
    private fun place(id: String) = PositionDomainModel(id, "server", null, null, null,
        "chapter.xhtml", "application/xhtml+xml", "Chapter 8", null, null, 7, .2, 12, null, .62, null)
    private fun row(id: String, started: Boolean = true) = CopyPositionRow(copy(id), place(id).takeIf { started },
        null, null, PositionSource.ThisDevice, false, false, null, candidateId = id)
    private val source = row("source")
    private val target = copy("target")
    private val reliable = ApplyPreview(target, null, true, true, null, null)
    private val disabled = ApplyPreview(copy("disabled"), null, false, false, null,
        ApplyDisabledReason.NoTranslation(TranslationFailure.NoMatch))
    private val analytics = object : Analytics {
        override fun logException(throwable: Throwable, message: String?) = Unit
        override fun logEvent(event: AnalyticsEvent) = Unit
        override fun setUserId(userId: String?) = Unit
    }
    private inner class Fake : PositionsDataSource {
        var rows: List<CopyPositionRow>? = listOf(source, row("empty", false), row("target"))
        var previews = listOf(reliable, disabled)
        var results = listOf(ApplyResult(target, CopyWriteResult.Written))
        var fail = false
        var delayLoad = false
        var loads = 0
        var cancellations = 0
        val changes = MutableStateFlow(0)
        override suspend fun metadata(serverId: String, bookUuid: String) =
            PositionsMetadata("The Lantern Ferry", "This phone", mapOf("server" to "home"))
        override suspend fun load(serverId: String, bookUuid: String): List<CopyPositionRow>? {
            loads++
            if (delayLoad) try { awaitCancellation() } catch (e: CancellationException) { cancellations++; throw e }
            if (fail) error("offline")
            return rows
        }
        override suspend fun preview(source: CopyPositionRow, rows: List<CopyPositionRow>) = previews
        override suspend fun apply(source: CopyPositionRow, previews: List<ApplyPreview>) = results
        override fun changes() = changes.map { Unit }
    }
    private fun test(body: suspend TestScope.(Fake, PositionsViewModel) -> Unit) = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val fake = Fake()
        val vm = PositionsViewModel("server", "source", {}, { _, _ -> }, fake, analytics)
        val store = ViewModelStore().also { it.put("positions", vm) }
        try { runCurrent(); body(fake, vm) } finally { store.clear(); Dispatchers.resetMain() }
    }

    @Test fun `selection is a radio group and unavailable rows cannot be chosen`() = test { _, vm ->
        vm.onIntent(PositionsIntent.OnUseThisPositionClicked)
        runCurrent()
        assertNull(vm.viewState.value.previews)
        vm.onIntent(PositionsIntent.OnRowClicked("empty"))
        assertNull(vm.viewState.value.selectedKey)
        vm.onIntent(PositionsIntent.OnRowClicked("source"))
        vm.onIntent(PositionsIntent.OnRowClicked("source"))
        assertEquals("source", vm.viewState.value.selectedKey)
        vm.onIntent(PositionsIntent.OnRowClicked("target"))
        assertEquals("target", vm.viewState.value.selectedKey)
    }
    @Test fun `sheet defaults toggles disabled gating and dismiss cleanup`() = test { _, vm ->
        vm.onIntent(PositionsIntent.OnRowClicked("source"))
        vm.onIntent(PositionsIntent.OnUseThisPositionClicked)
        runCurrent()
        assertEquals(setOf("storyteller:target"), vm.viewState.value.checkedKeys)
        vm.onIntent(PositionsIntent.OnTargetToggled(disabled.target.key.value))
        assertEquals(1, vm.viewState.value.checkedKeys.size)
        vm.onIntent(PositionsIntent.OnTargetToggled(target.key.value))
        assertEquals(0, vm.viewState.value.checkedKeys.size)
        vm.onIntent(PositionsIntent.OnSheetDismissed)
        assertNull(vm.viewState.value.previews)
        assertTrue(vm.viewState.value.checkedKeys.isEmpty())
    }
    @Test fun `load failure is distinct from no longer linked and retry recovers`() = test { fake, vm ->
        fake.fail = true
        vm.onIntent(PositionsIntent.OnRefresh); runCurrent()
        assertTrue(vm.viewState.value.loadError)
        assertFalse(vm.viewState.value.isUnlinked)
        fake.fail = false; fake.rows = null
        vm.onIntent(PositionsIntent.OnRefresh); runCurrent()
        assertFalse(vm.viewState.value.loadError)
        assertTrue(vm.viewState.value.isUnlinked)
    }
    @Test fun `refresh cancels in flight loads`() = test { fake, vm ->
        fake.delayLoad = true
        vm.onIntent(PositionsIntent.OnRefresh); runCurrent()
        fake.delayLoad = false
        vm.onIntent(PositionsIntent.OnRefresh); runCurrent()
        assertEquals(1, fake.cancellations)
        assertFalse(vm.viewState.value.isRefreshing)
    }
    @Test fun `live updates retain selection and update its position`() = test { fake, vm ->
        vm.onIntent(PositionsIntent.OnRowClicked("source"))
        fake.rows = listOf(source.copy(position = source.position!!.copy(totalProgression = .8)))
        fake.changes.value++; runCurrent()
        assertEquals("source", vm.viewState.value.selectedKey)
        assertEquals(.8, vm.viewState.value.rows.single().position!!.totalProgression)
    }
    @Test fun `all partial and none outcomes close sheet and reload`() = test { fake, vm ->
        val failure = ApplyResult(copy("failed"), CopyWriteResult.Refused(CopyWriteResult.Reason.NotSupported))
        for (results in listOf(listOf(ApplyResult(target, CopyWriteResult.Written)),
                listOf(ApplyResult(target, CopyWriteResult.Written), failure), listOf(failure))) {
            fake.results = results
            vm.onIntent(PositionsIntent.OnRowClicked("source"))
            vm.onIntent(PositionsIntent.OnUseThisPositionClicked); runCurrent()
            val before = fake.loads
            vm.onIntent(PositionsIntent.OnApplyClicked); runCurrent()
            assertNull(vm.viewState.value.previews)
            assertEquals(positionApplyNotice(results), vm.viewState.value.notice)
            assertTrue(fake.loads > before)
        }
    }
    @Test fun `failure causes preserved in view state choose exact copy`() = test { fake, vm ->
        val causes = listOf(TranslationFailure.MissingFile(target.key), TranslationFailure.NoMatch, TranslationFailure.Unknown)
        causes.forEach { cause ->
            fake.previews = listOf(disabled.copy(disabledReason = ApplyDisabledReason.NoTranslation(cause)))
            vm.onIntent(PositionsIntent.OnRowClicked("source"))
            vm.onIntent(PositionsIntent.OnUseThisPositionClicked); runCurrent()
            val reason = vm.viewState.value.previews!!.single().disabledReason as ApplyDisabledReason.NoTranslation
            assertEquals(if (cause is TranslationFailure.MissingFile) StringRes.positions_missing_file else StringRes.positions_no_match,
                reason.messageResource())
            assertTrue(vm.viewState.value.checkedKeys.isEmpty())
            vm.onIntent(PositionsIntent.OnSheetDismissed)
        }
    }
}
