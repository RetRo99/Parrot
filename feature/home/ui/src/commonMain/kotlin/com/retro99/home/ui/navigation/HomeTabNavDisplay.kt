package com.retro99.home.ui.navigation

import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.lifecycle.viewmodel.navigation3.rememberViewModelStoreNavEntryDecorator
import androidx.navigation3.runtime.NavEntry
import androidx.navigation3.runtime.NavEntryDecorator
import androidx.navigation3.runtime.rememberDecoratedNavEntries
import androidx.navigation3.runtime.rememberSaveableStateHolderNavEntryDecorator
import androidx.navigation3.ui.NavDisplay
import androidx.navigationevent.NavigationEventInfo
import androidx.navigationevent.compose.NavigationBackHandler
import androidx.navigationevent.compose.rememberNavigationEventState

/** Decorator owners stay composed for every tab, even when its display is not selected. */
@Composable
internal fun rememberHomeTabEntries(
    navigationState: HomeNavigationStateHolder,
    entryProvider: (HomeDestination) -> NavEntry<HomeDestination>,
    decorators: @Composable (HomeTab) -> List<NavEntryDecorator<HomeDestination>> = {
        listOf(
            rememberSaveableStateHolderNavEntryDecorator(),
            rememberViewModelStoreNavEntryDecorator(),
        )
    },
): Map<HomeTab, List<NavEntry<HomeDestination>>> {
    val latestProvider = rememberUpdatedState(bottomSheetEntryProvider(entryProvider))
    return HomeTab.entries.associateWith { tab ->
        key(tab) {
            val generation = navigationState.entryStateGeneration
            val stack = navigationState.backStacks.getValue(tab).toList()
            val entries = remember(stack, generation) {
                stack.map { destination ->
                    val baseEntry = latestProvider.value(destination)
                    // Each tab has separate decorator owners. The saveable generation key
                    // invalidates unchanged root entries on profile reset, as well as details.
                    NavEntry(
                        key = destination,
                        contentKey = "$generation:${baseEntry.contentKey}",
                        metadata = baseEntry.metadata,
                        content = { latestProvider.value(destination).Content() },
                    )
                }
            }
            rememberDecoratedNavEntries(
                entries = entries,
                entryDecorators = decorators(tab),
            )
        }
    }
}

@Composable
internal fun HomeTabNavDisplay(
    navigationState: HomeNavigationStateHolder,
    onBack: () -> Unit,
    entryProvider: (HomeDestination) -> NavEntry<HomeDestination>,
    modifier: Modifier = Modifier,
) {
    val entriesByTab = rememberHomeTabEntries(navigationState, entryProvider)
    val sheetStrategy = remember { BottomSheetSceneStrategy<HomeDestination>() }

    // NavDisplay only enables back when it has a previous scene. Android's root-tab back
    // is a selection change, not a pop, and must not invent an iOS swipe destination.
    val rootBackState = rememberNavigationEventState(NavigationEventInfo.None)
    NavigationBackHandler(
        state = rootBackState,
        isBackEnabled = navigationState.currentBackStack.size == 1 &&
            navigationState.backDestination != null,
        onBackCompleted = onBack,
    )

    // A new display on tab selection avoids a cross-tab push animation or predictive history.
    // Decorated entries are deliberately remembered OUTSIDE this key so their state survives.
    key(navigationState.currentTab) {
        NavDisplay(
            entries = entriesByTab.getValue(navigationState.currentTab),
            onBack = onBack,
            modifier = modifier,
            sceneStrategies = listOf(sheetStrategy),
        )
    }
}
