package com.retro99.home.ui.navigation

/** Bounded attribution for an actual Statistics destination exposure. */
internal data class StatisticsExposureContext(
    val sourceScreen: String,
    val entryPoint: String,
)

internal fun statisticsExposureContext(
    previousDestination: HomeDestination?,
    tabChanged: Boolean,
): StatisticsExposureContext = when {
    previousDestination == null -> StatisticsExposureContext(
        sourceScreen = "home",
        entryPoint = "route_restore",
    )

    tabChanged -> StatisticsExposureContext(
        sourceScreen = previousDestination.analyticsScreenName(),
        entryPoint = "bottom_navigation",
    )

    previousDestination == HomeDestination.AppSettings -> StatisticsExposureContext(
        sourceScreen = "app_settings",
        entryPoint = "statistics_row",
    )

    else -> StatisticsExposureContext(
        sourceScreen = previousDestination.analyticsScreenName(),
        entryPoint = "navigation_back",
    )
}
