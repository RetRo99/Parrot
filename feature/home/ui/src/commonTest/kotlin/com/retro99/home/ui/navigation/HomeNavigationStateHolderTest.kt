package com.retro99.home.ui.navigation

import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.toMutableStateList
import com.retro99.books.domain.model.BookType
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class HomeNavigationStateHolderTest {
    @Test
    fun iosRootBackNeverChangesTabs() {
        val state = state(HomeRootBackPolicy.StayInTab)

        HomeTab.entries.forEach { tab ->
            state.switchTab(tab)

            assertNull(state.backDestination)
            assertFalse(state.goBack())
            assertEquals(tab, state.currentTab)
            assertEquals(listOf(tab.startDestination), state.currentBackStack.toList())
        }
    }

    @Test
    fun androidRootBackReturnsToTheExistingStartTabDestination() {
        val state = state(HomeRootBackPolicy.ReturnToStartTab)
        val detail = HomeDestination.BookDetail("server", "book")
        state.navigateTo(detail)
        state.switchTab(HomeTab.Settings)

        assertEquals(detail, state.backDestination)
        assertTrue(state.goBack())
        assertEquals(HomeTab.Books, state.currentTab)
        assertEquals(detail, state.currentDestination)
    }

    @Test
    fun startTabRootHasNoBackDestinationOnEitherPlatform() {
        HomeRootBackPolicy.entries.forEach { policy ->
            val state = state(policy)

            assertNull(state.backDestination)
            assertFalse(state.goBack())
        }
    }

    @Test
    fun nestedBackPopsOnlyTheSelectedTabOnEitherPlatform() {
        HomeRootBackPolicy.entries.forEach { policy ->
            val state = state(policy)
            val book = HomeDestination.BookDetail("server", "book")
            state.navigateTo(book)
            state.switchTab(HomeTab.Settings)
            state.navigateTo(HomeDestination.Diagnostics)

            assertEquals(HomeDestination.AppSettings, state.backDestination)
            assertTrue(state.goBack())
            assertEquals(HomeTab.Settings, state.currentTab)
            assertEquals(HomeDestination.AppSettings, state.currentDestination)
            assertEquals(book, state.backStacks.getValue(HomeTab.Books).last())
        }
    }

    @Test
    fun switchingAndReselectingTabsPreservesEveryHistory() {
        val state = state(HomeRootBackPolicy.StayInTab)
        val book = HomeDestination.BookDetail("server", "book")
        val series = HomeDestination.SeriesDetail("series", "Series")
        state.navigateTo(book)
        state.switchTab(HomeTab.Series)
        state.navigateTo(series)
        state.switchTab(HomeTab.Books)
        state.switchTab(HomeTab.Books)

        assertEquals(book, state.currentDestination)
        assertEquals(2, state.currentBackStack.size)
        state.switchTab(HomeTab.Series)
        assertEquals(series, state.currentDestination)
    }

    @Test
    fun theSameDestinationCanBelongToTwoIndependentTabs() {
        val state = state(HomeRootBackPolicy.StayInTab)
        state.navigateTo(HomeDestination.Diagnostics)
        state.switchTab(HomeTab.Settings)
        state.navigateTo(HomeDestination.Diagnostics)
        state.goBack()

        assertEquals(HomeDestination.AppSettings, state.currentDestination)
        assertEquals(HomeDestination.Diagnostics, state.backStacks.getValue(HomeTab.Books).last())
    }

    @Test
    fun replacementUpdatesOnlyItsTargetTabAndPreservesReaderAttribution() {
        val state = state(HomeRootBackPolicy.StayInTab)
        val oldReader = HomeDestination.Reader("server", "old", BookType.EBOOK)
        val reader = HomeDestination.Reader(
            serverId = "server",
            bookUuid = "new",
            bookType = BookType.EBOOK,
            readerOpenEntryPoint = "deep_link",
            readerOpenCorrelationId = "correlation",
        )
        state.navigateTo(oldReader)
        state.switchTab(HomeTab.Settings)
        state.navigateTo(HomeDestination.Diagnostics)

        state.navigateToReplacing(reader, HomeTab.Books)

        assertEquals(HomeTab.Books, state.currentTab)
        assertEquals(listOf(HomeDestination.BooksList, reader), state.currentBackStack.toList())
        assertEquals(HomeDestination.Diagnostics, state.backStacks.getValue(HomeTab.Settings).last())
        assertEquals("correlation", (state.currentDestination as HomeDestination.Reader)
            .readerOpenCorrelationId)
    }

    @Test
    fun profileResetRestoresEveryRootAndInvalidatesEvenUnchangedRootState() {
        val state = state(HomeRootBackPolicy.StayInTab)
        state.navigateTo(HomeDestination.Diagnostics)
        state.switchTab(HomeTab.Settings)
        state.navigateTo(HomeDestination.SyncAndBackup)

        state.resetAllStacks()

        assertEquals(HomeTab.Books, state.currentTab)
        assertEquals(1, state.entryStateGeneration)
        HomeTab.entries.forEach { tab ->
            assertEquals(listOf(tab.startDestination), state.backStacks.getValue(tab).toList())
        }
        state.resetAllStacks()
        assertEquals(2, state.entryStateGeneration)
    }

    @Test
    fun aNonBooksStartTabIsRespectedByTheAndroidPolicy() {
        val state = state(HomeRootBackPolicy.ReturnToStartTab, startTab = HomeTab.Series)
        state.switchTab(HomeTab.Settings)

        assertEquals(HomeDestination.SeriesList, state.backDestination)
        assertTrue(state.goBack())
        assertEquals(HomeTab.Series, state.currentTab)
    }

    @Test
    fun replacingTheOpenScreenKeepsWhatIsUnderItAndNeverRemovesATabRoot() {
        val state = state(HomeRootBackPolicy.entries.first())
        val root = state.currentDestination
        val list = HomeDestination.CatalogueBrowse("source")
        state.navigateTo(list)
        state.navigateTo(HomeDestination.CatalogueBrowse("source", "r1"))

        // A catalogue page that turned out to be one book gives way to the book's page.
        val book = HomeDestination.CataloguePublication("source", "r2")
        state.replaceCurrent(book)
        assertEquals(listOf(root, list, book), state.currentBackStack.toList())
        assertTrue(state.goBack())
        assertEquals(list, state.currentDestination)

        assertTrue(state.goBack())
        state.replaceCurrent(book)
        assertEquals(listOf(root, book), state.currentBackStack.toList())
    }

    private fun state(
        policy: HomeRootBackPolicy,
        startTab: HomeTab = HomeTab.DEFAULT,
    ) = HomeNavigationStateHolder(
        startTab = startTab,
        currentTabState = mutableStateOf(startTab),
        backStacks = HomeTab.entries.associateWith { tab ->
            listOf(tab.startDestination).toMutableStateList()
        },
        rootBackPolicy = policy,
    )
}
