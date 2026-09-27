package com.retro99.analytics.api

/**
 * Book discovery and download related analytics events.
 */
sealed interface BookAnalyticsEvent : AnalyticsEvent {

    data class BookDetailViewed(
        val bookUuid: String,
        val source: String,
    ) : BookAnalyticsEvent {
        override val name: String = "book_detail_viewed"
        override val parameters: Map<String, Any> = mapOf(
            "book_uuid" to bookUuid,
            "source" to source,
        )
    }

    data class BookDownloadStarted(
        val bookUuid: String,
        val bookType: String,
    ) : BookAnalyticsEvent {
        override val name: String = "book_download_started"
        override val parameters: Map<String, Any> = mapOf(
            "book_uuid" to bookUuid,
            "book_type" to bookType,
        )
    }

    data class BookDownloadCompleted(
        val bookUuid: String,
        val downloadDurationMs: Long,
    ) : BookAnalyticsEvent {
        override val name: String = "book_download_completed"
        override val parameters: Map<String, Any> = mapOf(
            "book_uuid" to bookUuid,
            "download_duration_ms" to downloadDurationMs,
        )
    }

    data class BookDownloadFailed(
        val bookUuid: String,
        val errorType: String,
    ) : BookAnalyticsEvent {
        override val name: String = "book_download_failed"
        override val parameters: Map<String, Any> = mapOf(
            "book_uuid" to bookUuid,
            "error_type" to errorType,
        )
    }

    data class BookDownloadCancelled(
        val bookUuid: String,
    ) : BookAnalyticsEvent {
        override val name: String = "book_download_cancelled"
        override val parameters: Map<String, Any> = mapOf(
            "book_uuid" to bookUuid,
        )
    }

    /**
     * Tracks when user clicks read/play button - helps understand format preferences.
     */
    data class ReadButtonClicked(
        val bookUuid: String,
        val bookType: String,
    ) : BookAnalyticsEvent {
        override val name: String = "read_button_clicked"
        override val parameters: Map<String, Any> = mapOf(
            "book_uuid" to bookUuid,
            "book_type" to bookType,
        )
    }

    /**
     * Tracks when user deletes cached media - helps understand storage management behavior.
     */
    data class BookCacheDeleted(
        val bookUuid: String,
        val bookType: String,
    ) : BookAnalyticsEvent {
        override val name: String = "book_cache_deleted"
        override val parameters: Map<String, Any> = mapOf(
            "book_uuid" to bookUuid,
            "book_type" to bookType,
        )
    }

    /**
     * Tracks when user adds or removes a book from favorites.
     */
    data class FavoriteToggled(
        val bookUuid: String,
        val isFavorite: Boolean,
        val source: String,
    ) : BookAnalyticsEvent {
        override val name: String = "favorite_toggled"
        override val parameters: Map<String, Any> = mapOf(
            "book_uuid" to bookUuid,
            "is_favorite" to isFavorite,
            "source" to source,
        )
    }

    /**
     * Tracks when user imports a local EPUB file.
     */
    data class BookImported(
        val bookUuid: String,
    ) : BookAnalyticsEvent {
        override val name: String = "book_imported"
        override val parameters: Map<String, Any> = mapOf(
            "book_uuid" to bookUuid,
        )
    }

    data class BookImportFailed(
        val errorType: String,
    ) : BookAnalyticsEvent {
        override val name: String = "book_import_failed"
        override val parameters: Map<String, Any> = mapOf(
            "error_type" to errorType,
        )
    }

    /**
     * Tracks when user imports a custom font file.
     */
    data class CustomFontImported(
        val fontName: String,
    ) : BookAnalyticsEvent {
        override val name: String = "custom_font_imported"
        override val parameters: Map<String, Any> = mapOf(
            "font_name" to fontName,
        )
    }
}

/**
 * Authentication related analytics events.
 */
sealed interface AuthAnalyticsEvent : AnalyticsEvent {

    data class WelcomeViewed(
        val sourceScreen: String,
        val entryPoint: String,
    ) : AuthAnalyticsEvent {
        override val name: String = "welcome_screen_viewed"
        override val parameters: Map<String, Any> = mapOf(
            "screen" to "welcome",
            "source_screen" to sourceScreen,
            "entry_point" to entryPoint,
        )
    }

    data class WelcomeActionAttempted(
        val action: String,
    ) : AuthAnalyticsEvent {
        override val name: String = "welcome_action_attempted"
        override val parameters: Map<String, Any> = mapOf(
            "screen" to "welcome",
            "action" to action,
            "outcome" to "started",
        )
    }

    data class WelcomeActionCompleted(
        val action: String,
        val outcome: String,
    ) : AuthAnalyticsEvent {
        override val name: String = "welcome_action_completed"
        override val parameters: Map<String, Any> = mapOf(
            "screen" to "welcome",
            "action" to action,
            "outcome" to outcome,
        )
    }

    data class LoginViewed(
        val sourceScreen: String,
        val entryPoint: String,
    ) : AuthAnalyticsEvent {
        override val name: String = "login_screen_viewed"
        override val parameters: Map<String, Any> = mapOf(
            "screen" to "login",
            "source_screen" to sourceScreen,
            "entry_point" to entryPoint,
        )
    }

    data class ServerTypePickerAttempted(
        val serverType: String,
    ) : AuthAnalyticsEvent {
        override val name: String = "login_server_type_picker_attempted"
        override val parameters: Map<String, Any> = mapOf(
            "screen" to "login",
            "action" to "select_server_type",
            "operation" to "server_type_picker",
            "stage" to "menu_open",
            "outcome" to "started",
            "server_type" to serverType,
        )
    }

    data class ServerTypeSelected(
        val previousServerType: String,
        val serverType: String,
    ) : AuthAnalyticsEvent {
        override val name: String = "login_server_type_selected"
        override val parameters: Map<String, Any> = mapOf(
            "screen" to "login",
            "action" to "select_server_type",
            "operation" to "server_type_picker",
            "stage" to "terminal",
            "outcome" to "succeeded",
            "previous_server_type" to previousServerType,
            "server_type" to serverType,
        )
    }

    data class ServerTypePickerCancelled(
        val serverType: String,
        val reasonCode: String,
    ) : AuthAnalyticsEvent {
        override val name: String = "login_server_type_picker_cancelled"
        override val parameters: Map<String, Any> = mapOf(
            "screen" to "login",
            "action" to "select_server_type",
            "operation" to "server_type_picker",
            "stage" to "terminal",
            "outcome" to "cancelled",
            "server_type" to serverType,
            "reason_code" to reasonCode,
        )
    }

    data class LoginAttempted(
        val serverType: String,
        val authMethod: String,
        val isRetry: Boolean,
    ) : AuthAnalyticsEvent {
        override val name: String = "login_attempted"
        override val parameters: Map<String, Any> = mapOf(
            "screen" to "login",
            "action" to "sign_in",
            "server_type" to serverType,
            "auth_method" to authMethod,
            "is_retry" to isRetry,
            "outcome" to "started",
        )
    }

    data class LoginSucceeded(
        val serverType: String,
        val authMethod: String,
        val durationMs: Long,
    ) : AuthAnalyticsEvent {
        override val name: String = "login_succeeded"
        override val parameters: Map<String, Any> = mapOf(
            "screen" to "login",
            "action" to "sign_in",
            "server_type" to serverType,
            "auth_method" to authMethod,
            "outcome" to "succeeded",
            "duration_ms" to durationMs,
        )
    }

    data class LoginFailed(
        val serverType: String,
        val authMethod: String,
        val errorType: String,
        val durationMs: Long,
    ) : AuthAnalyticsEvent {
        override val name: String = "login_failed"
        override val parameters: Map<String, Any> = mapOf(
            "screen" to "login",
            "action" to "sign_in",
            "server_type" to serverType,
            "auth_method" to authMethod,
            "error_type" to errorType,
            "outcome" to "failed",
            "duration_ms" to durationMs,
        )
    }

    data class LoginCancelled(
        val serverType: String,
        val authMethod: String,
        val reasonCode: String,
        val durationMs: Long,
    ) : AuthAnalyticsEvent {
        override val name: String = "login_cancelled"
        override val parameters: Map<String, Any> = mapOf(
            "screen" to "login",
            "action" to "sign_in",
            "server_type" to serverType,
            "auth_method" to authMethod,
            "reason_code" to reasonCode,
            "outcome" to "cancelled",
            "duration_ms" to durationMs,
        )
    }

    data class LoginAbandoned(
        val serverType: String,
        val authMethod: String,
        val reasonCode: String,
        val durationMs: Long,
    ) : AuthAnalyticsEvent {
        override val name: String = "login_abandoned"
        override val parameters: Map<String, Any> = mapOf(
            "screen" to "login",
            "action" to "sign_in",
            "server_type" to serverType,
            "auth_method" to authMethod,
            "reason_code" to reasonCode,
            "outcome" to "abandoned",
            "duration_ms" to durationMs,
        )
    }

    data class OAuthLoginStepFailed(
        val step: String,
        val errorType: String,
        val statusCode: Int? = null,
    ) : AuthAnalyticsEvent {
        override val name: String = "oauth_login_step_failed"
        override val parameters: Map<String, Any> = buildMap {
            put("screen", "login")
            put("auth_method", "oauth")
            put("step", step)
            put("error_type", errorType)
            put("outcome", "failed")
            statusCode?.let { put("status_code", it) }
        }
    }

    data object LogoutClicked : AuthAnalyticsEvent {
        override val name: String = "logout_clicked"
    }

    data object LogoutCompleted : AuthAnalyticsEvent {
        override val name: String = "logout_completed"
    }
}

/**
 * Navigation and screen view analytics events.
 */
sealed interface NavigationAnalyticsEvent : AnalyticsEvent {

    enum class AppVisibility(val value: String) {
        Foreground("foreground"),
        Background("background"),
    }

    /** Records a real app visibility transition; configuration changes are not app backgrounding. */
    data class AppVisibilityChanged(
        val visibility: AppVisibility,
    ) : NavigationAnalyticsEvent {
        override val name: String = "app_visibility_changed"
        override val parameters: Map<String, Any> = mapOf(
            "screen" to "app",
            "action" to "lifecycle",
            "operation" to "app_visibility",
            "stage" to visibility.value,
            "outcome" to when (visibility) {
                AppVisibility.Foreground -> "foregrounded"
                AppVisibility.Background -> "backgrounded"
            },
        )
    }

    enum class WelcomeRootBackOutcome(val value: String) {
        Exited("exited"),
        Failed("failed"),
    }

    /** Records the terminal outcome of system Back from the root Welcome destination. */
    data class WelcomeRootBackCompleted(
        val outcome: WelcomeRootBackOutcome,
    ) : NavigationAnalyticsEvent {
        override val name: String = "navigation_back"
        override val parameters: Map<String, Any> = mapOf(
            "screen" to "welcome",
            "source_screen" to "welcome",
            "destination_screen" to "app_exit",
            "entry_point" to "system_back",
            "outcome" to outcome.value,
        )
    }

    /** Records one actual Home root exposure, attributed to its bounded navigation source. */
    data class HomeViewed(
        val sourceScreen: String,
        val entryPoint: String,
    ) : NavigationAnalyticsEvent {
        override val name: String = "home_screen_viewed"
        override val parameters: Map<String, Any> = mapOf(
            "screen" to "home",
            "source_screen" to sourceScreen,
            "entry_point" to entryPoint,
        )
    }

    /** Records the route selected by the completed startup/authentication check. */
    data class AppLaunchRouteResolved(
        val destination: String,
        val outcome: String = "success",
        val reasonCode: String? = null,
    ) : NavigationAnalyticsEvent {
        override val name: String = "app_launch_route_resolved"
        override val parameters: Map<String, Any> = buildMap {
            put("screen", destination)
            put("source_screen", "splash")
            put("entry_point", "app_launch")
            put("outcome", outcome)
            reasonCode?.let { put("reason_code", it) }
        }
    }

    /** Records an accepted user request to switch to a different Home tab. */
    data class TabSwitchAttempted(
        val sourceTab: String,
        val destinationTab: String,
        val entryPoint: String = "bottom_navigation",
    ) : NavigationAnalyticsEvent {
        override val name: String = "tab_switch_attempted"
        override val parameters: Map<String, Any> = mapOf(
            "screen" to "home",
            "source_tab" to sourceTab,
            "destination_tab" to destinationTab,
            "entry_point" to entryPoint,
            "action" to "switch_tab",
            "operation" to "tab_navigation",
            "stage" to "navigation",
            "outcome" to "started",
        )
    }

    enum class TabSwitchOutcome(val value: String) {
        Succeeded("succeeded"),
        Failed("failed"),
    }

    /** Records a tab change after the navigation owner has applied the selected destination. */
    data class TabSwitched(
        val sourceTab: String,
        val destinationTab: String,
        val outcome: TabSwitchOutcome = TabSwitchOutcome.Succeeded,
        val entryPoint: String = "bottom_navigation",
    ) : NavigationAnalyticsEvent {
        override val name: String = "tab_switched"
        override val parameters: Map<String, Any> = mapOf(
            "screen" to "home",
            "source_tab" to sourceTab,
            "destination_tab" to destinationTab,
            "tab_name" to destinationTab,
            "entry_point" to entryPoint,
            "action" to "switch_tab",
            "operation" to "tab_navigation",
            "stage" to "terminal",
            "outcome" to outcome.value,
        )
    }

    /** Reselecting the active tab is tracked separately and is not counted as a switch. */
    data class TabReselected(
        val tabName: String,
        val entryPoint: String = "bottom_navigation",
    ) : NavigationAnalyticsEvent {
        override val name: String = "tab_reselected"
        override val parameters: Map<String, Any> = mapOf(
            "screen" to "home",
            "source_tab" to tabName,
            "destination_tab" to tabName,
            "tab_name" to tabName,
            "entry_point" to entryPoint,
            "action" to "reselect_tab",
            "operation" to "tab_navigation",
            "stage" to "terminal",
            "outcome" to "unchanged",
        )
    }

    /**
     * Tracks when user opens search - helps understand search feature usage.
     */
    data class SearchOpened(
        val source: String,
    ) : NavigationAnalyticsEvent {
        override val name: String = "search_opened"
        override val parameters: Map<String, Any> = mapOf(
            "source" to source,
        )
    }

    data class DeepLinkOpened(
        val bookUuid: String,
        val bookType: String,
    ) : NavigationAnalyticsEvent {
        override val name: String = "deep_link_opened"
        override val parameters: Map<String, Any> = mapOf(
            "book_uuid" to bookUuid,
            "book_type" to bookType,
        )
    }

    data class ContinueReadingLaunched(
        val bookUuid: String,
    ) : NavigationAnalyticsEvent {
        override val name: String = "continue_reading_launched"
        override val parameters: Map<String, Any> = mapOf(
            "book_uuid" to bookUuid,
        )
    }

    /** Records an enabled last-book startup attempt without exposing the local book identifier. */
    data class LastBookLaunchAttempted(
        val bookType: String,
        val stage: String = "navigation",
    ) : NavigationAnalyticsEvent {
        override val name: String = "last_book_launch_attempted"
        override val parameters: Map<String, Any> = mapOf(
            "screen" to "home",
            "source_screen" to "splash",
            "entry_point" to "app_launch",
            "action" to "open_last_book",
            "operation" to "reader_open",
            "stage" to stage,
            "outcome" to "started",
            "book_type" to bookType,
        )
    }

    enum class LastBookLaunchOutcome(val value: String) {
        Succeeded("succeeded"),
        Failed("failed"),
        Skipped("skipped"),
        Cancelled("cancelled"),
    }

    /** Records the terminal result of startup resume, only after content is usable or skipped. */
    data class LastBookLaunchCompleted(
        val screen: String,
        val outcome: LastBookLaunchOutcome,
        val stage: String = "terminal",
        val reasonCode: String? = null,
        val bookType: String? = null,
    ) : NavigationAnalyticsEvent {
        override val name: String = "last_book_launch_completed"
        override val parameters: Map<String, Any> = buildMap {
            put("screen", screen)
            put("source_screen", if (screen == "reader") "home" else "splash")
            put("entry_point", "app_launch")
            put("action", "open_last_book")
            put("operation", "reader_open")
            put("stage", stage)
            put("outcome", outcome.value)
            reasonCode?.let { put("reason_code", it) }
            bookType?.let { put("book_type", it) }
        }
    }
}

/**
 * Server management related analytics events.
 */
sealed interface ServerManagementAnalyticsEvent : AnalyticsEvent {

    data class ServerAdded(
        val serverType: String,
    ) : ServerManagementAnalyticsEvent {
        override val name: String = "server_added"
        override val parameters: Map<String, Any> = mapOf(
            "server_type" to serverType,
        )
    }

    data class ServerRemoved(
        val serverType: String,
    ) : ServerManagementAnalyticsEvent {
        override val name: String = "server_removed"
        override val parameters: Map<String, Any> = mapOf(
            "server_type" to serverType,
        )
    }

    data class ServerLoggedOut(
        val serverType: String,
    ) : ServerManagementAnalyticsEvent {
        override val name: String = "server_logged_out"
        override val parameters: Map<String, Any> = mapOf(
            "server_type" to serverType,
        )
    }
}

/**
 * App settings related analytics events.
 */
sealed interface AppSettingsAnalyticsEvent : AnalyticsEvent {

    data class ProfileCreated(
        val profileName: String,
    ) : AppSettingsAnalyticsEvent {
        override val name: String = "profile_created"
        override val parameters: Map<String, Any> = mapOf(
            "profile_name" to profileName,
        )
    }

    data object ProfileDeleted : AppSettingsAnalyticsEvent {
        override val name: String = "profile_deleted"
    }

    data class ProfileSwitched(
        val profileId: String,
    ) : AppSettingsAnalyticsEvent {
        override val name: String = "profile_switched"
        override val parameters: Map<String, Any> = mapOf(
            "profile_id" to profileId,
        )
    }

    data object ProfileRenamed : AppSettingsAnalyticsEvent {
        override val name: String = "profile_renamed"
    }

    data class FileLoggingToggled(
        val isEnabled: Boolean,
    ) : AppSettingsAnalyticsEvent {
        override val name: String = "file_logging_toggled"
        override val parameters: Map<String, Any> = mapOf(
            "is_enabled" to isEnabled,
        )
    }

    data class CrashOnlyLoggingToggled(
        val isEnabled: Boolean,
    ) : AppSettingsAnalyticsEvent {
        override val name: String = "crash_only_logging_toggled"
        override val parameters: Map<String, Any> = mapOf(
            "is_enabled" to isEnabled,
        )
    }

    data class OpenLastBookOnLaunchToggled(
        val isEnabled: Boolean,
    ) : AppSettingsAnalyticsEvent {
        override val name: String = "open_last_book_on_launch_toggled"
        override val parameters: Map<String, Any> = mapOf(
            "is_enabled" to isEnabled,
        )
    }

    data object LogsShared : AppSettingsAnalyticsEvent {
        override val name: String = "logs_shared"
    }

    data object LogsCleared : AppSettingsAnalyticsEvent {
        override val name: String = "logs_cleared"
    }

    data class CurrentBookCleared(
        val isRetry: Boolean = false,
    ) : AppSettingsAnalyticsEvent {
        override val name: String = "current_book_cleared"
        override val parameters: Map<String, Any> = mapOf(
            "screen" to "app_settings",
            "action" to "clear_current_book",
            "operation" to "clear_current_book",
            "stage" to "terminal",
            "outcome" to "succeeded",
            "is_retry" to isRetry,
        )
    }

    data class CurrentBookClearAttempted(
        val isRetry: Boolean,
    ) : AppSettingsAnalyticsEvent {
        override val name: String = "current_book_clear_attempted"
        override val parameters: Map<String, Any> = mapOf(
            "screen" to "app_settings",
            "action" to "clear_current_book",
            "operation" to "clear_current_book",
            "stage" to "started",
            "outcome" to "started",
            "is_retry" to isRetry,
        )
    }

    data class CurrentBookClearFailed(
        val isRetry: Boolean,
    ) : AppSettingsAnalyticsEvent {
        override val name: String = "current_book_clear_failed"
        override val parameters: Map<String, Any> = mapOf(
            "screen" to "app_settings",
            "action" to "clear_current_book",
            "operation" to "clear_current_book",
            "stage" to "terminal",
            "outcome" to "failed",
            "reason_code" to "current_book_clear_failed",
            "is_retry" to isRetry,
        )
    }
}

/**
 * Statistics screen related analytics events.
 */
sealed interface StatisticsAnalyticsEvent : AnalyticsEvent {

    data object StatisticsViewed : StatisticsAnalyticsEvent {
        override val name: String = "statistics_viewed"
    }

    data class StatisticsPeriodChanged(
        val period: String,
    ) : StatisticsAnalyticsEvent {
        override val name: String = "statistics_period_changed"
        override val parameters: Map<String, Any> = mapOf(
            "period" to period,
        )
    }

    data class StatisticsDetailShown(
        val detailType: String,
    ) : StatisticsAnalyticsEvent {
        override val name: String = "statistics_detail_shown"
        override val parameters: Map<String, Any> = mapOf(
            "detail_type" to detailType,
        )
    }
}

/**
 * Books list related analytics events for tracking filter and sort usage.
 */
sealed interface BooksListAnalyticsEvent : AnalyticsEvent {

    data class QuickFilterToggled(
        val filter: String,
        val isEnabled: Boolean,
    ) : BooksListAnalyticsEvent {
        override val name: String = "quick_filter_toggled"
        override val parameters: Map<String, Any> = mapOf(
            "filter" to filter,
            "is_enabled" to isEnabled,
        )
    }

    data class SortChanged(
        val sortConfig: String,
    ) : BooksListAnalyticsEvent {
        override val name: String = "sort_changed"
        override val parameters: Map<String, Any> = mapOf(
            "sort_config" to sortConfig,
        )
    }

    data class ViewModeChanged(
        val viewMode: String,
    ) : BooksListAnalyticsEvent {
        override val name: String = "view_mode_changed"
        override val parameters: Map<String, Any> = mapOf(
            "view_mode" to viewMode,
        )
    }

    data class ServerTypeFilterChanged(
        val serverType: String?,
    ) : BooksListAnalyticsEvent {
        override val name: String = "server_type_filter_changed"
        override val parameters: Map<String, Any> = buildMap {
            serverType?.let { put("server_type", it) }
        }
    }
}

/**
 * Network-related analytics events for debugging connectivity issues.
 */
sealed interface NetworkAnalyticsEvent : AnalyticsEvent {

    /**
     * Tracks network request failures - helps identify connectivity patterns and server issues.
     * The endpoint is the API path (e.g., "/api/v2/books/positions") without the base URL for privacy.
     */
    data class NetworkRequestFailed(
        val endpoint: String,
        val errorType: String,
        val isTimeout: Boolean,
        val isConnectivity: Boolean,
        val statusCode: Int? = null,
    ) : NetworkAnalyticsEvent {
        override val name: String = "network_request_failed"
        override val parameters: Map<String, Any> = buildMap {
            put("endpoint", endpoint)
            put("error_type", errorType)
            put("is_timeout", isTimeout)
            put("is_connectivity", isConnectivity)
            statusCode?.let { put("status_code", it) }
        }
    }
}
