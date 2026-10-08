package com.retro99.statistics.ui

import androidx.lifecycle.viewModelScope
import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.github.michaelbull.result.getOrElse
import com.retro99.analytics.api.Analytics
import com.retro99.books.domain.model.BookType
import com.retro99.database.api.statistics.ReadingSessionDatabase
import com.retro99.database.implementation.AppDatabase
import com.retro99.database.implementation.DatabaseManager
import com.retro99.database.implementation.SqlDriverFactory
import com.retro99.database.implementation.di.DatabaseModule
import com.retro99.preferences.api.Preferences
import com.retro99.preferences.api.PreferencesKey
import com.retro99.reader.domain.recap.RecapEngineSelector
import com.retro99.reader.domain.recap.RecapRepository
import com.retro99.reader.domain.recap.RecapSettings
import com.retro99.statistics.data.di.StatisticsDataModule
import com.retro99.statistics.domain.ActiveSessionTimer
import com.retro99.statistics.domain.AudiobookSessionTracker
import com.retro99.statistics.domain.StatisticsRepository
import com.retro99.statistics.domain.di.StatisticsDomainModule
import com.retro99.statistics.domain.model.ReadingSessionDomainModel
import com.retro99.statistics.domain.model.StatisticsRange
import com.retro99.statistics.domain.usecase.GetAllBooksReadUseCase
import com.retro99.statistics.domain.usecase.GetRecentSessionsUseCase
import com.retro99.statistics.domain.usecase.GetStatisticsOverviewUseCase
import com.retro99.statistics.domain.usecase.SaveReadingSessionUseCase
import com.retro99.statistics.ui.di.StatisticsUiModule
import com.retro99.user.api.UserRegistry
import java.lang.reflect.Proxy
import java.nio.file.Files
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withTimeout
import org.koin.core.KoinApplication
import org.koin.core.module.Module
import org.koin.core.parameter.parametersOf
import org.koin.core.parameter.ParametersHolder
import org.koin.dsl.koinApplication
import org.koin.dsl.module
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.reflect.KClass

/** Only platform drivers and unrelated services are replaced; statistics wiring and SQL are real. */
@OptIn(ExperimentalCoroutinesApi::class)
class StatisticsKoinIntegrationTest {
    @Test
    fun `sessions under thirty active seconds do not create rows or affect dashboard totals`() = runBlocking {
        withFixture { fixture ->
            for (type in BookType.entries) {
                for (duration in listOf(-1L, 0L, 1L, 10_000L, 29_999L)) {
                    fixture.save("short-${type.name}", type, duration)
                }
            }
            assertTrue(fixture.database.getAllSessions().isEmpty())
            val state = fixture.loadedViewModel().currentViewState()
            assertEquals(0L, state.statistics?.totalSessions)
            assertEquals(0L, state.statistics?.totalBooksRead)
            assertEquals(0L, state.overview?.allTimeMs)
            assertEquals(0, state.overview?.currentStreak)
        }
    }

    @Test
    fun `exactly thirty active seconds qualifies for every format without page turns`() = runBlocking {
        withFixture { fixture ->
            for (type in BookType.entries) fixture.save(type.name, type, 30_000L)
            val sessions = fixture.database.getAllSessions()
            assertEquals(BookType.entries.size, sessions.size)
            assertTrue(sessions.all { it.durationMs == 30_000L && it.pagesRead == null })
            assertEquals(BookType.entries.size * 30_000L, fixture.database.getTotalReadingTimeMs())
        }
    }

    @Test
    fun `long wall time does not qualify a session with less than thirty active seconds`() = runBlocking {
        withFixture { fixture ->
            fixture.resolve(SaveReadingSessionUseCase::class)(
                "book", "Book", BookType.EBOOK, fixture.start - 600_000L, fixture.start,
                durationMs = 10_000L, pagesRead = 2,
            ).getOrElse { error("$it") }
            assertTrue(fixture.database.getAllSessions().isEmpty())
        }
    }
    @Test
    fun `concurrent session saves preserve every row and converge to consistent dashboard totals`() = runBlocking {
        withFixture { fixture ->
            coroutineScope {
                (1..40).map { index -> async(Dispatchers.Default) {
                    fixture.save("book-${index % 4}", BookType.EBOOK, index * 30_000L, fixture.start - index * 100_000L)
                } }.awaitAll()
            }
            val sessions = fixture.repository.getAllSessions().getOrElse { error("$it") }
            assertEquals(40, sessions.size)
            assertEquals(40, sessions.map { it.id }.distinct().size)
            val statistics = fixture.repository.getReadingStatistics().first().getOrElse { error("$it") }
            assertEquals(24_600_000L, statistics.totalReadingTimeMs)
            assertEquals(40L, statistics.totalSessions)
            assertEquals(4L, statistics.totalBooksRead)
            val state = fixture.loadedViewModel().currentViewState()
            assertEquals(24_600_000L, state.overview?.allTimeMs)
            assertEquals(40, state.overview?.totalSessions)
        }
    }

    @Test
    fun `queries during concurrent saves do not lose rows and refresh converges after writers finish`() = runBlocking {
        withFixture { fixture ->
            val viewModel = fixture.loadedViewModel()
            coroutineScope {
                val writer = async(Dispatchers.Default) {
                    repeat(30) { fixture.save("book", BookType.AUDIOBOOK, 30_000L, fixture.start - it * 30_000L) }
                }
                repeat(10) {
                    val sessions = fixture.repository.getAllSessions().getOrElse { error("$it") }
                    assertEquals(sessions.size, sessions.map { it.id }.distinct().size)
                    assertTrue(sessions.size in 0..30)
                    fixture.repository.getReadingStatistics().first().getOrElse { error("$it") }
                }
                writer.await()
            }
            viewModel.onIntent(StatisticsIntent.OnRefresh)
            withTimeout(10_000L) { viewModel.viewState.first { it.statistics?.totalSessions == 30L && it.overview?.totalSessions == 30 } }
            assertEquals(900_000L, viewModel.currentViewState().overview?.allTimeMs)
            assertEquals(900_000L, fixture.database.getTotalReadingTimeMs())
        }
    }
    @BeforeTest
    fun setMain() = Dispatchers.setMain(Dispatchers.Unconfined)

    @AfterTest
    fun resetMain() = Dispatchers.resetMain()

    @Test
    fun `generated Koin graph resolves every statistics use case and shares the repository`() = runBlocking {
        withFixture { fixture ->
            assertSame(fixture.repository, fixture.resolve(StatisticsRepository::class))
            assertSame(fixture.database, fixture.resolve(ReadingSessionDatabase::class))
            assertNotNull(fixture.resolve(SaveReadingSessionUseCase::class))
            assertNotNull(fixture.resolve(GetRecentSessionsUseCase::class))
            assertNotNull(fixture.resolve(GetAllBooksReadUseCase::class))
            assertNotNull(fixture.resolve(GetStatisticsOverviewUseCase::class))
            assertEquals(0L, fixture.loadedViewModel().currentViewState().statistics?.totalSessions)
        }
    }

    @Test
    fun `empty SQLite produces a complete zero dashboard rather than an error`() = runBlocking {
        withFixture { fixture ->
            val statistics = fixture.repository.getReadingStatistics().first().getOrElse { error("$it") }
            assertEquals(0L, statistics.totalReadingTimeMs)
            assertEquals(0L, statistics.totalBooksRead)
            assertEquals(0, statistics.currentStreak)
            assertTrue(statistics.mostReadBooks.isEmpty())
            val state = fixture.loadedViewModel().currentViewState()
            assertFalse(state.isLoading)
            assertNull(state.error)
            assertEquals(0L, state.overview?.allTimeMs)
            assertEquals(0, state.overview?.totalSessions)
        }
    }

    @Test
    fun `saving all formats flows through generated wiring into SQL totals and chart`() = runBlocking {
        withFixture { fixture ->
            fixture.save("ebook", BookType.EBOOK, 60_000L)
            fixture.save("audio", BookType.AUDIOBOOK, 120_000L)
            fixture.save("readaloud", BookType.READALOUD, 180_000L)

            val statistics = fixture.repository.getReadingStatistics().first().getOrElse { error("$it") }
            assertEquals(360_000L, statistics.totalReadingTimeMs)
            assertEquals(3L, statistics.totalSessions)
            assertEquals(3L, statistics.totalBooksRead)
            assertEquals(mapOf(BookType.EBOOK to 60_000L, BookType.AUDIOBOOK to 120_000L, BookType.READALOUD to 180_000L), statistics.readingTimeByType)
            val state = fixture.loadedViewModel().currentViewState()
            assertEquals(statistics.totalSessions, state.statistics?.totalSessions)
            assertEquals(statistics.readingTimeByType, state.statistics?.readingTimeByType)
            assertEquals(statistics.totalReadingTimeMs, state.overview?.allTimeMs)
            assertEquals(state.overview?.totalMs, state.overview?.buckets?.sumOf { it.durationMs })
        }
    }

    @Test
    fun `book totals group repeat sessions and order most read books by time`() = runBlocking {
        withFixture { fixture ->
            fixture.save("a", BookType.EBOOK, 60_000L)
            fixture.save("b", BookType.AUDIOBOOK, 120_000L)
            fixture.save("a", BookType.EBOOK, 180_000L)

            val books = fixture.resolve(GetAllBooksReadUseCase::class)().getOrElse { error("$it") }
            assertEquals(listOf("a", "b"), books.map { it.bookUuid })
            assertEquals(listOf(240_000L, 120_000L), books.map { it.totalDurationMs })
            assertEquals(listOf(2L, 1L), books.map { it.sessionCount })
            assertEquals(2L, fixture.repository.getReadingStatistics().first().getOrElse { error("$it") }.totalBooksRead)
        }
    }

    @Test
    fun `session metadata survives SQL round trip and selection in the actual ViewModel`() = runBlocking {
        withFixture { fixture ->
            fixture.resolve(SaveReadingSessionUseCase::class)(
                "ebook", "Book title", BookType.EBOOK, fixture.start, fixture.start + 60_000L, 60_000L,
                pagesRead = 8, startProgression = 0.2, endProgression = 0.4, readingSpeedWpm = 275,
                recapSessionId = "reader-session-recap",
            ).getOrElse { error("$it") }
            val viewModel = fixture.loadedViewModel()
            viewModel.onIntent(StatisticsIntent.OnTotalSessionsClicked)
            val state = withTimeout(10_000L) { viewModel.viewState.first { it.sessionsDetailState?.isLoading == false } }
            val session = state.sessionsDetailState!!.sessions.single()
            assertTrue(session.id > 0L)
            assertEquals(8, session.pagesRead)
            assertEquals(0.2, session.startProgression)
            assertEquals(0.4, session.endProgression)
            assertEquals(275, session.readingSpeedWpm)
            assertEquals("reader-session-recap", session.recapSessionId)
            viewModel.onIntent(StatisticsIntent.OnSessionClicked(session.id))
            assertEquals(session, viewModel.currentViewState().sessionsDetailState?.selected?.session)
            assertTrue(fixture.recaps.requested.isEmpty(), "Viewing statistics must not generate a recap")
        }
    }

    @Test
    fun `recent sessions are newest first with a strict limit even when inserted out of order`() = runBlocking {
        withFixture { fixture ->
            fixture.save("old", BookType.EBOOK, 30_000L, fixture.start - 90_000L)
            fixture.save("new", BookType.EBOOK, 60_000L, fixture.start)
            fixture.save("middle", BookType.EBOOK, 90_000L, fixture.start - 40_000L)
            val recent = fixture.resolve(GetRecentSessionsUseCase::class)(limit = 2).getOrElse { error("$it") }
            assertEquals(listOf("new", "middle"), recent.map { it.bookUuid })
            assertTrue(fixture.resolve(GetRecentSessionsUseCase::class)(limit = 0).getOrElse { error("$it") }.isEmpty())
        }
    }

    @Test
    fun `pause resume and repeated playback teardown persist active listening exactly once`() = runBlocking {
        withFixture { fixture ->
            var elapsed = 0L
            val pending = mutableListOf<ReadingSessionDomainModel>()
            val tracker = AudiobookSessionTracker(pending::add, { fixture.start + elapsed }, { ActiveSessionTimer { elapsed } })
            tracker.setBook("audio", "Audiobook")
            tracker.setPlaying(true)
            elapsed = 20_000L
            tracker.setPlaying(false)
            elapsed = 25_000L
            tracker.setPlaying(true)
            elapsed = 55_000L
            repeat(3) { tracker.finish() }
            pending.forEach { fixture.save(it.bookUuid, it.bookType, it.durationMs, it.startTime) }

            val sessions = fixture.repository.getAllSessions().getOrElse { error("$it") }
            assertEquals(1, sessions.size)
            assertEquals(50_000L, sessions.single().durationMs)
            assertEquals(50_000L, fixture.repository.getTotalReadingTimeMs().getOrElse { error("$it") })
        }
    }

    @Test
    fun `refresh reads new persisted sessions and clearing history resets the dashboard`() = runBlocking {
        withFixture { fixture ->
            fixture.save("a", BookType.EBOOK, 60_000L)
            val viewModel = fixture.loadedViewModel()
            fixture.save("b", BookType.AUDIOBOOK, 120_000L)
            viewModel.onIntent(StatisticsIntent.OnRefresh)
            withTimeout(10_000L) { viewModel.viewState.first { it.statistics?.totalSessions == 2L && it.overview?.totalSessions == 2 } }
            assertEquals(180_000L, viewModel.currentViewState().overview?.allTimeMs)
            fixture.repository.clearAllSessions().getOrElse { error("$it") }
            viewModel.onIntent(StatisticsIntent.OnRefresh)
            withTimeout(10_000L) { viewModel.viewState.first { it.statistics?.totalSessions == 0L && it.overview?.totalSessions == 0 } }
            assertEquals(0L, fixture.database.getTotalReadingTimeMs())
            assertNull(viewModel.currentViewState().error)
        }
    }

    @Test
    fun `statistics remain isolated and durable across profile switches`() = runBlocking {
        withFixture { fixture ->
            fixture.save("a", BookType.EBOOK, 60_000L)
            fixture.switchProfile("profile-b")
            assertEquals(0L, fixture.repository.getTotalReadingTimeMs().getOrElse { error("$it") })
            fixture.save("b", BookType.AUDIOBOOK, 120_000L)
            fixture.switchProfile("profile-a")
            assertEquals(listOf("a"), fixture.repository.getAllSessions().getOrElse { error("$it") }.map { it.bookUuid })
            assertEquals(60_000L, fixture.repository.getTotalReadingTimeMs().getOrElse { error("$it") })
            fixture.switchProfile("profile-b")
            assertEquals(120_000L, fixture.repository.getTotalReadingTimeMs().getOrElse { error("$it") })
        }
    }

    @Test
    fun `generated ViewModel wiring honors stored range and injected Back callback`() = runBlocking {
        withFixture { fixture ->
            fixture.preferences.putString(PreferencesKey.StatisticsRange, StatisticsRange.YEAR.name)
            val viewModel = fixture.loadedViewModel()
            assertEquals(StatisticsRange.YEAR, viewModel.currentViewState().range)
            assertEquals(12, viewModel.currentViewState().overview?.buckets?.size)
            viewModel.onIntent(StatisticsIntent.OnRangeSelected(StatisticsRange.MONTH))
            withTimeout(10_000L) { viewModel.viewState.first { it.overview?.range == StatisticsRange.MONTH } }
            assertEquals(StatisticsRange.MONTH.name, fixture.preferences.getStringOrNull(PreferencesKey.StatisticsRange))
            viewModel.onIntent(StatisticsIntent.OnBackClicked)
            assertEquals(1, fixture.backCalls)
        }
    }

    private suspend fun withFixture(test: suspend (Fixture) -> Unit) {
        val fixture = Fixture()
        try {
            fixture.switchProfile("profile-a")
            test(fixture)
        } finally {
            fixture.close()
        }
    }

    private class Fixture {
        private val directory = Files.createTempDirectory("parrot-statistics-test").toFile()
        private var profile = "profile-a"
        private val viewModels = mutableListOf<StatisticsViewModel>()
        val preferences = InMemoryPreferences()
        val recaps = FakeRecapRepository()
        var backCalls = 0
        val start = Clock.System.now().toEpochMilliseconds() - 1_000L
        private val productionModules = listOf(
            generatedModule(DatabaseModule()), generatedModule(StatisticsDataModule()),
            generatedModule(StatisticsDomainModule()), generatedModule(StatisticsUiModule()),
        )
        val app: KoinApplication = koinApplication {
            modules(
                productionModules + module {
                    single<UserRegistry> {
                        Proxy.newProxyInstance(UserRegistry::class.java.classLoader, arrayOf(UserRegistry::class.java)) { _, method, _ ->
                            when (method.name) {
                                "observeActiveProfile" -> emptyFlow<Nothing>()
                                "getActiveProfileId", "getActiveProfileIdOrDefault" -> profile
                                else -> error("Unexpected UserRegistry call: ${method.name}")
                            }
                        } as UserRegistry
                    }
                    single<SqlDriverFactory> {
                        object : SqlDriverFactory {
                            override fun createDriver(userId: String): SqlDriver {
                                val file = directory.resolve("$userId.db")
                                val existed = file.exists()
                                return JdbcSqliteDriver("jdbc:sqlite:${file.absolutePath}").also {
                                    if (!existed) AppDatabase.Schema.create(it)
                                }
                            }
                            override fun deleteUserDatabase(userId: String): Boolean = directory.resolve("$userId.db").delete()
                        }
                    }
                    single<Preferences> { preferences }
                    single<Analytics> { RecordingAnalytics() }
                    single<RecapRepository> { recaps }
                    single<RecapSettings> { FakeRecapSettings() }
                    single<RecapEngineSelector> { FakeRecapEngineSelector() }
                },
            )
        }
        val repository get() = resolve(StatisticsRepository::class)
        val database get() = resolve(ReadingSessionDatabase::class)

        // Non-reified lookup: static graph validation cannot inspect reflective generated
        // module loading. These tests validate those exact definitions at runtime instead.
        fun <T : Any> resolve(type: KClass<T>, parameters: (() -> ParametersHolder)? = null): T =
            app.koin.get(type, parameters = parameters)

        suspend fun switchProfile(id: String) {
            profile = id
            resolve(DatabaseManager::class).withProfile(id) { }
        }

        suspend fun save(book: String, type: BookType, duration: Long, at: Long = start) {
            resolve(SaveReadingSessionUseCase::class)(book, "Title $book", type, at, at + duration, duration)
                .getOrElse { error("$it") }
        }

        suspend fun loadedViewModel(): StatisticsViewModel {
            val viewModel = resolve(StatisticsViewModel::class) { parametersOf({ backCalls++; Unit }) }
            viewModels += viewModel
            withTimeout(10_000L) {
                viewModel.viewState.first { !it.isLoading && it.statistics != null && it.overview != null }
            }
            return viewModel
        }

        suspend fun close() {
            viewModels.forEach { it.viewModelScope.cancel() }
            resolve(DatabaseManager::class).close()
            app.close()
            directory.listFiles()?.forEach { check(it.delete()) }
            check(directory.delete())
        }
    }
}

private fun generatedModule(instance: Any): Module {
    val type = instance.javaClass
    val generatedName = type.name.split('.').joinToString("") { it.replaceFirstChar(Char::uppercaseChar) } + "ModuleKt"
    return Class.forName("${type.packageName}.$generatedName")
        .getMethod("module", type).invoke(null, instance) as Module
}
