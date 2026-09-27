package com.retro99.analytics.api

/** Records an actual visible Reader Settings destination exposure. */
data class ReaderSettingsScreenViewed(
    val sourceScreen: String,
    val entryPoint: String,
) : ReaderAnalyticsEvent {
    override val name: String = "reader_settings_screen_viewed"
    override val parameters: Map<String, Any> = mapOf(
        "screen" to "reader_settings",
        "source_screen" to sourceScreen,
        "entry_point" to entryPoint,
    )
}
