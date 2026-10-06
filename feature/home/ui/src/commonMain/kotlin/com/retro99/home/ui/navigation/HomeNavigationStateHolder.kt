package com.retro99.home.ui.navigation

import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.compose.runtime.toMutableStateList
import androidx.navigation3.runtime.NavKey
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * Creates and remembers a [HomeNavigationStateHolder] that persists navigation state
 * across configuration changes and process death.
 *
 * This uses [rememberSaveable] with custom serialization for each tab's back stack,
 * which automatically handles state persistence through Android's saved instance state mechanism.
 *
 * @param startTab The default tab to show when the app starts fresh.
 * @return A [HomeNavigationStateHolder] that manages multiple back stacks.
 */
@Composable
fun rememberHomeNavigationState(
    startTab: HomeTab = HomeTab.DEFAULT,
    rootBackPolicy: HomeRootBackPolicy = platformHomeRootBackPolicy(),
): HomeNavigationStateHolder {
    // Remember the current tab, persisted across process death
    val currentTab = rememberSaveable { mutableStateOf(startTab) }
    val entryStateGeneration = rememberSaveable { mutableStateOf(0) }

    // Create a back stack for each tab using rememberSaveable
    // Each back stack automatically persists its state using kotlinx.serialization
    val booksBackStack = rememberSaveableBackStack(HomeTab.Books.startDestination)
    val seriesBackStack = rememberSaveableBackStack(HomeTab.Series.startDestination)
    val statisticsBackStack = rememberSaveableBackStack(HomeTab.Statistics.startDestination)
    val settingsBackStack = rememberSaveableBackStack(HomeTab.Settings.startDestination)

    val backStacks: Map<HomeTab, SnapshotStateList<HomeDestination>> = remember {
        mapOf(
            HomeTab.Books to booksBackStack,
            HomeTab.Series to seriesBackStack,
            HomeTab.Statistics to statisticsBackStack,
            HomeTab.Settings to settingsBackStack,
        )
    }

    return remember(startTab, rootBackPolicy) {
        HomeNavigationStateHolder(
            startTab = startTab,
            currentTabState = currentTab,
            backStacks = backStacks,
            rootBackPolicy = rootBackPolicy,
            entryStateGenerationState = entryStateGeneration,
        )
    }
}

/**
 * Creates a saveable back stack for a single tab.
 * Uses kotlinx.serialization to persist the back stack across process death.
 */
@Composable
private fun rememberSaveableBackStack(
    startDestination: HomeDestination,
): SnapshotStateList<HomeDestination> {
    return rememberSaveable(
        saver = HomeDestinationListSaver,
    ) {
        mutableListOf(startDestination).toMutableStateList()
    }
}

/**
 * State holder for home navigation with multiple back stacks.
 *
 * This class manages navigation state using [SnapshotStateList] for each tab,
 * which automatically persists state across configuration changes and process death.
 *
 * @param startTab The default tab (used for "exit through home" pattern).
 * @param currentTabState The mutable state backing the current tab selection.
 * @param backStacks Map of back stacks, one for each tab.
 */
class HomeNavigationStateHolder(
    val startTab: HomeTab,
    private val currentTabState: MutableState<HomeTab>,
    val backStacks: Map<HomeTab, SnapshotStateList<HomeDestination>>,
    val rootBackPolicy: HomeRootBackPolicy = platformHomeRootBackPolicy(),
    private val entryStateGenerationState: MutableState<Int> = mutableStateOf(0),
) : NavKey {
    /** Gives even unchanged tab roots fresh entry state after changing profiles. */
    var entryStateGeneration by entryStateGenerationState
        private set

    /**
     * The currently selected tab.
     */
    var currentTab: HomeTab
        get() = currentTabState.value
        set(value) {
            currentTabState.value = value
        }

    /**
     * The back stack for the current tab.
     */
    val currentBackStack: SnapshotStateList<HomeDestination>
        get() = backStacks[currentTab] ?: error("No back stack for tab: $currentTab")

    /**
     * The current destination (top of the current tab's back stack).
     */
    val currentDestination: HomeDestination?
        get() = currentBackStack.lastOrNull()

    /** Shared by back handling and telemetry so they cannot disagree about the target. */
    val backDestination: HomeDestination?
        get() = when {
            currentBackStack.size > 1 -> currentBackStack[currentBackStack.lastIndex - 1]
            rootBackPolicy == HomeRootBackPolicy.ReturnToStartTab && currentTab != startTab ->
                backStacks[startTab]?.lastOrNull()
            else -> null
        }

    /**
     * Navigate to a destination within the current tab.
     */
    fun navigateTo(destination: HomeDestination) {
        currentBackStack.add(destination)
    }

    /**
     * Switch to a different tab.
     */
    fun switchTab(tab: HomeTab) {
        currentTab = tab
    }

    /**
     * Handle back navigation.
     *
     * @return true if back was handled, false if the current root has no back destination.
     */
    fun goBack(): Boolean {
        val stack = currentBackStack
        return if (stack.size > 1) {
            // Pop the current tab's back stack
            stack.removeLastOrNull()
            true
        } else if (
            rootBackPolicy == HomeRootBackPolicy.ReturnToStartTab && currentTab != startTab
        ) {
            // If at the root of a non-default tab, switch to the default tab
            currentTab = startTab
            true
        } else {
            // This platform policy has no back action at the selected root.
            false
        }
    }

    /**
     * Navigate to a destination, replacing any existing destination of the same type.
     * Useful for deep links to avoid stacking multiple instances.
     */
    fun navigateToReplacing(destination: HomeDestination, tab: HomeTab = currentTab) {
        currentTab = tab
        val stack = backStacks[tab] ?: return

        // Remove any existing destination of the same type using KClass comparison
        val destinationClass = destination::class
        val toRemove = stack.filter { it::class == destinationClass }
        toRemove.forEach { stack.remove(it) }

        // Add the new destination
        stack.add(destination)
    }

    /**
     * Reset all back stacks to their root destinations.
     * Used when switching user profiles to ensure a clean navigation state.
     */
    fun resetAllStacks() {
        entryStateGeneration += 1
        HomeTab.entries.forEach { tab ->
            val stack = backStacks[tab] ?: return@forEach
            stack.clear()
            stack.add(tab.startDestination)
        }
        currentTab = startTab
    }
}


/**
 * Custom Saver for a list of [HomeDestination] using kotlinx.serialization.
 * This allows the back stack to be persisted across process death.
 */
private val HomeDestinationListSaver = Saver<SnapshotStateList<HomeDestination>, String>(
    save = { list ->
        Json.encodeToString(list.toList())
    },
    restore = { jsonString ->
        Json.decodeFromString<List<HomeDestination>>(jsonString).toMutableStateList()
    }
)
