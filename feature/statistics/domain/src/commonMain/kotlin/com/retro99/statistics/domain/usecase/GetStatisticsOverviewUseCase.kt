package com.retro99.statistics.domain.usecase

import com.github.michaelbull.result.map
import com.retro99.base.result.AppResult
import com.retro99.statistics.domain.StatisticsOverviewCalculator
import com.retro99.statistics.domain.StatisticsRepository
import com.retro99.statistics.domain.model.StatisticsOverview
import com.retro99.statistics.domain.model.StatisticsRange
import kotlinx.datetime.DayOfWeek
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import org.koin.core.annotation.Factory
import org.koin.core.annotation.Provided
import kotlin.time.Clock

/**
 * Use case for the statistics screen: totals, chart buckets and streaks for a period,
 * computed from all reading sessions in the device's time zone.
 */
@Factory
class GetStatisticsOverviewUseCase(
    @Provided private val repository: StatisticsRepository,
) {
    suspend operator fun invoke(
        range: StatisticsRange,
        firstDayOfWeek: DayOfWeek,
        timeZone: TimeZone = TimeZone.currentSystemDefault(),
        clock: Clock = Clock.System,
    ): AppResult<StatisticsOverview> {
        val today = clock.now().toLocalDateTime(timeZone).date
        return repository.getAllSessions().map { sessions ->
            StatisticsOverviewCalculator.calculate(
                sessions = sessions,
                range = range,
                today = today,
                timeZone = timeZone,
                firstDayOfWeek = firstDayOfWeek,
            )
        }
    }
}
