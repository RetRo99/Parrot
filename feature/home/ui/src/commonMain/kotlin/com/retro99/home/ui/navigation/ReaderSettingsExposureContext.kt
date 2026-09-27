package com.retro99.home.ui.navigation

/** Bounded attribution for an actual Reader Settings route exposure. */
internal data class ReaderSettingsExposureContext(
    val sourceScreen: String,
    val entryPoint: String,
)

internal fun readerSettingsExposureContext(
    previousDestination: HomeDestination?,
): ReaderSettingsExposureContext = when (previousDestination) {
    HomeDestination.AppSettings -> ReaderSettingsExposureContext(
        sourceScreen = "app_settings",
        entryPoint = "reader_settings_row",
    )
    is HomeDestination.Reader -> ReaderSettingsExposureContext(
        sourceScreen = "reader",
        entryPoint = "reader_settings_button",
    )
    null -> ReaderSettingsExposureContext(
        sourceScreen = "home",
        entryPoint = "route_restore",
    )
    else -> ReaderSettingsExposureContext(
        sourceScreen = previousDestination.analyticsScreenName(),
        entryPoint = "in_app_navigation",
    )
}
