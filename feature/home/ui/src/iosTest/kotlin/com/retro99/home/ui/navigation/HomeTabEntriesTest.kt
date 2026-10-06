package com.retro99.home.ui.navigation

import androidx.compose.runtime.AbstractApplier
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Composition
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.MonotonicFrameClock
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.Recomposer
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.runtime.toMutableStateList
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.viewmodel.compose.LocalViewModelStoreOwner
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import androidx.navigation3.runtime.NavEntry
import androidx.navigation3.runtime.NavEntryDecorator
import androidx.navigation3.runtime.rememberDecoratedNavEntries
import androidx.navigation3.runtime.rememberSaveableStateHolderNavEntryDecorator
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.compose.LocalSavedStateRegistryOwner
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotSame
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest

/** Exercises real Compose/Nav3 decoration on iOS without requiring UI nodes. */
@OptIn(ExperimentalCoroutinesApi::class)
class HomeTabEntriesTest {
    @Test
    fun baselineActiveStackDecorationTreatsTabSelectionAsAPop() = runTest {
        val state = state()
        val popped = mutableListOf<Any>()
        val composition = RuntimeComposition(this) {
            val entries = rememberDecoratedNavEntries(
                backStack = state.currentBackStack,
                entryDecorators = listOf(
                    rememberSaveableStateHolderNavEntryDecorator(),
                    remember { popObserver(popped) },
                ),
                entryProvider = { destination -> NavEntry(destination) {} },
            )
            key(state.currentTab) { entries.last().Content() }
        }
        try {
            composition.flush()
            state.switchTab(HomeTab.Settings)
            composition.flush()

            // Reproduces the old ownership defect: inactive Books is considered removed.
            assertTrue(popped.isNotEmpty())
        } finally {
            composition.close()
        }
    }

    @Test
    fun switchingTabsRetainsSavedScreenStateAndDoesNotInvokePopCleanup() = runTest {
        val state = state()
        val popped = mutableListOf<Any>()
        var screenState: MutableState<Int>? = null
        val composition = RuntimeComposition(this) {
            val entries = rememberHomeTabEntries(
                navigationState = state,
                entryProvider = { destination ->
                    NavEntry(destination) {
                        val position = rememberSaveable { mutableStateOf(0) }
                        SideEffect { screenState = position }
                    }
                },
                decorators = {
                    listOf(
                        rememberSaveableStateHolderNavEntryDecorator(),
                        remember { popObserver(popped) },
                    )
                },
            )
            key(state.currentTab) { entries.getValue(state.currentTab).last().Content() }
        }
        try {
            composition.flush()
            checkNotNull(screenState).value = 17
            composition.flush()
            state.switchTab(HomeTab.Settings)
            composition.flush()
            assertEquals(0, checkNotNull(screenState).value)
            state.switchTab(HomeTab.Books)
            composition.flush()

            assertEquals(17, checkNotNull(screenState).value)
            assertTrue(popped.isEmpty())
        } finally {
            composition.close()
        }
    }

    @Test
    fun identicalRoutesInDifferentTabsHaveIndependentSavedState() = runTest {
        val state = state()
        state.navigateTo(HomeDestination.Diagnostics)
        state.switchTab(HomeTab.Settings)
        state.navigateTo(HomeDestination.Diagnostics)
        state.switchTab(HomeTab.Books)
        var screenState: MutableState<Int>? = null
        val composition = RuntimeComposition(this) {
            val entries = rememberHomeTabEntries(
                navigationState = state,
                entryProvider = { destination ->
                    NavEntry(destination) {
                        val position = rememberSaveable { mutableStateOf(0) }
                        SideEffect { screenState = position }
                    }
                },
                decorators = { listOf(rememberSaveableStateHolderNavEntryDecorator()) },
            )
            key(state.currentTab) { entries.getValue(state.currentTab).last().Content() }
        }
        try {
            composition.flush()
            checkNotNull(screenState).value = 11
            composition.flush()
            state.switchTab(HomeTab.Settings)
            composition.flush()
            assertEquals(0, checkNotNull(screenState).value)
            checkNotNull(screenState).value = 22
            composition.flush()
            state.switchTab(HomeTab.Books)
            composition.flush()
            assertEquals(11, checkNotNull(screenState).value)
            state.switchTab(HomeTab.Settings)
            composition.flush()
            assertEquals(22, checkNotNull(screenState).value)
        } finally {
            composition.close()
        }
    }

    @Test
    fun realPopWaitsUntilOutgoingContentLeavesCompositionBeforeCleanup() = runTest {
        val state = state()
        state.navigateTo(HomeDestination.Diagnostics)
        val popped = mutableListOf<Any>()
        val outgoing = mutableStateOf<NavEntry<HomeDestination>?>(null)
        var topEntry: NavEntry<HomeDestination>? = null
        val composition = RuntimeComposition(this) {
            val entries = rememberHomeTabEntries(
                navigationState = state,
                entryProvider = { destination -> NavEntry(destination) {} },
                decorators = { remember { listOf(popObserver(popped)) } },
            )
            val currentEntry = entries.getValue(state.currentTab).last()
            SideEffect { topEntry = currentEntry }
            // Keep the outgoing entry at the same keyed call site, rather than disposing
            // and recreating it in a second group when the simulated animation begins.
            listOfNotNull(outgoing.value, currentEntry)
                .distinctBy { entry -> entry.contentKey }
                .forEach { entry -> key(entry.contentKey) { entry.Content() } }
        }
        try {
            composition.flush()
            val detail = checkNotNull(topEntry)
            // Retain outgoing content, as NavDisplay does during its pop animation.
            outgoing.value = detail
            assertTrue(state.goBack())
            composition.flush()
            assertTrue(popped.isEmpty())

            outgoing.value = null
            composition.flush()
            assertEquals(listOf(detail.contentKey), popped)
        } finally {
            composition.close()
        }
    }

    @Test
    fun profileResetInvalidatesActiveAndInactiveRootsAndTheirSavedState() = runTest {
        val state = state()
        val popped = mutableListOf<Any>()
        var screenState: MutableState<Int>? = null
        var roots: Map<HomeTab, Any> = emptyMap()
        val composition = RuntimeComposition(this) {
            val entries = rememberHomeTabEntries(
                navigationState = state,
                entryProvider = { destination ->
                    NavEntry(destination) {
                        val position = rememberSaveable { mutableStateOf(0) }
                        SideEffect { screenState = position }
                    }
                },
                decorators = {
                    listOf(
                        rememberSaveableStateHolderNavEntryDecorator(),
                        remember { popObserver(popped) },
                    )
                },
            )
            SideEffect { roots = entries.mapValues { (_, tabEntries) -> tabEntries.first().contentKey } }
            key(state.currentTab) { entries.getValue(state.currentTab).last().Content() }
        }
        try {
            composition.flush()
            checkNotNull(screenState).value = 17
            composition.flush()
            val oldRoots = roots

            state.resetAllStacks()
            composition.flush()

            assertEquals(0, checkNotNull(screenState).value)
            HomeTab.entries.forEach { tab ->
                assertNotEquals(oldRoots.getValue(tab), roots.getValue(tab))
                assertTrue(oldRoots.getValue(tab) in popped)
            }
            assertEquals(HomeTab.entries.size, popped.size)
            assertFalse(roots.values.any { contentKey -> contentKey in popped })
        } finally {
            composition.close()
        }
    }

    @Test
    fun defaultViewModelScopesSurviveTabSelectionAndClearOnPopAndProfileReset() = runTest {
        val state = state()
        state.navigateTo(HomeDestination.Diagnostics)
        val owner = TestOwner()
        var currentModel: ProbeViewModel? = null
        val composition = RuntimeComposition(this) {
            CompositionLocalProvider(
                LocalLifecycleOwner provides owner,
                LocalViewModelStoreOwner provides owner,
                LocalSavedStateRegistryOwner provides owner,
            ) {
                val entries = rememberHomeTabEntries(
                    navigationState = state,
                    entryProvider = { destination ->
                        NavEntry(destination) {
                            val factory = remember {
                                viewModelFactory { initializer { ProbeViewModel() } }
                            }
                            val model = viewModel<ProbeViewModel>(factory = factory)
                            SideEffect { currentModel = model }
                        }
                    },
                )
                key(state.currentTab) { entries.getValue(state.currentTab).last().Content() }
            }
        }
        try {
            composition.flush()
            val detailModel = checkNotNull(currentModel)
            state.switchTab(HomeTab.Settings)
            composition.flush()
            val settingsModel = checkNotNull(currentModel)
            assertNotSame(detailModel, settingsModel)
            assertFalse(detailModel.cleared)

            state.switchTab(HomeTab.Books)
            composition.flush()
            assertSame(detailModel, currentModel)
            assertFalse(settingsModel.cleared)

            assertTrue(state.goBack())
            composition.flush()
            assertTrue(detailModel.cleared)
            val booksModel = checkNotNull(currentModel)
            assertNotSame(detailModel, booksModel)

            state.switchTab(HomeTab.Settings)
            composition.flush()
            assertSame(settingsModel, currentModel)
            state.resetAllStacks()
            composition.flush()
            assertTrue(booksModel.cleared)
            assertTrue(settingsModel.cleared)
            assertNotSame(booksModel, currentModel)
            assertFalse(checkNotNull(currentModel).cleared)
        } finally {
            composition.close()
            owner.viewModelStore.clear()
        }
    }

    private class ProbeViewModel : ViewModel() {
        var cleared = false
            private set

        override fun onCleared() {
            cleared = true
        }
    }

    private class TestOwner : SavedStateRegistryOwner, ViewModelStoreOwner {
        override val lifecycle = LifecycleRegistry.createUnsafe(this)
        override val viewModelStore = ViewModelStore()
        private val controller = SavedStateRegistryController.create(this)
        override val savedStateRegistry: SavedStateRegistry
            get() = controller.savedStateRegistry

        init {
            controller.performAttach()
            controller.performRestore(null)
            lifecycle.currentState = Lifecycle.State.CREATED
        }
    }

    private fun popObserver(popped: MutableList<Any>) = NavEntryDecorator<HomeDestination>(
        onPop = { contentKey -> popped.add(contentKey) },
        decorate = { entry -> entry.Content() },
    )

    private fun state() = HomeNavigationStateHolder(
        startTab = HomeTab.Books,
        currentTabState = mutableStateOf(HomeTab.Books),
        backStacks = HomeTab.entries.associateWith { tab ->
            listOf(tab.startDestination).toMutableStateList()
        },
        rootBackPolicy = HomeRootBackPolicy.StayInTab,
    )

    private class RuntimeComposition(
        private val scope: TestScope,
        content: @Composable () -> Unit,
    ) {
        private val clock = object : MonotonicFrameClock {
            override suspend fun <R> withFrameNanos(onFrame: (Long) -> R): R = onFrame(0)
        }
        private val recomposer = Recomposer(scope.coroutineContext + clock)
        private val composition = Composition(NoOpApplier(), recomposer)

        init {
            scope.backgroundScope.launch(clock) { recomposer.runRecomposeAndApplyChanges() }
            composition.setContent(content)
        }

        fun flush() {
            Snapshot.sendApplyNotifications()
            scope.runCurrent()
        }

        fun close() {
            composition.dispose()
            recomposer.cancel()
        }
    }

    private class NoOpApplier : AbstractApplier<Unit>(Unit) {
        override fun insertTopDown(index: Int, instance: Unit) = Unit
        override fun insertBottomUp(index: Int, instance: Unit) = Unit
        override fun remove(index: Int, count: Int) = Unit
        override fun move(from: Int, to: Int, count: Int) = Unit
        override fun onClear() = Unit
    }
}
