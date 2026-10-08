package com.retro99.statistics.data

import com.github.michaelbull.result.getError
import com.retro99.base.result.AppError
import com.retro99.database.api.statistics.ReadingSessionDatabase
import com.retro99.statistics.data.source.StatisticsLocalDataSource
import java.lang.reflect.Proxy
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertSame

class StatisticsLocalSourceRegressionTest {
    @Test
    fun `real source propagates database exceptions through the repository`() = runTest {
        val failure = IllegalStateException("database unavailable")
        val repository = StatisticsDataRepository(StatisticsLocalDataSource(failingDatabase(failure)))

        val result = repository.getReadingStatistics().first()

        assertSame(failure, assertIs<AppError.UnknownError>(result.getError()).throwable)
    }

    @Test
    fun `cancelled aggregate queries propagate cancellation instead of showing zero or an error`() = runTest {
        val repository = StatisticsDataRepository(StatisticsLocalDataSource(failingDatabase(CancellationException("cancelled"))))

        assertFailsWith<CancellationException> { repository.getReadingStatistics().first() }
        assertFailsWith<CancellationException> { repository.getTotalReadingTimeMs() }
    }

    @Test
    fun `cancelled history queries do not masquerade as empty charts or streaks`() = runTest {
        val repository = StatisticsDataRepository(StatisticsLocalDataSource(failingDatabase(CancellationException("cancelled"))))

        assertFailsWith<CancellationException> { repository.getAllSessions() }
        assertFailsWith<CancellationException> { repository.getDailyReadingTime(30) }
        assertFailsWith<CancellationException> { repository.getReadingStreak() }
    }

    private fun failingDatabase(failure: RuntimeException): ReadingSessionDatabase =
        Proxy.newProxyInstance(ReadingSessionDatabase::class.java.classLoader, arrayOf(ReadingSessionDatabase::class.java)) { _, _, _ ->
            throw failure
        } as ReadingSessionDatabase
}
