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

    data class LoginAttempted(
        val serverUrlHash: String,
    ) : AuthAnalyticsEvent {
        override val name: String = "login_attempted"
        override val parameters: Map<String, Any> = mapOf(
            "server_url_hash" to serverUrlHash,
        )
    }

    data object LoginSucceeded : AuthAnalyticsEvent {
        override val name: String = "login_succeeded"
    }

    data class LoginFailed(
        val errorType: String,
    ) : AuthAnalyticsEvent {
        override val name: String = "login_failed"
        override val parameters: Map<String, Any> = mapOf(
            "error_type" to errorType,
        )
    }

    data class OAuthLoginStepFailed(
        val step: String,
        val errorType: String,
        val statusCode: Int? = null,
    ) : AuthAnalyticsEvent {
        override val name: String = "oauth_login_step_failed"
        override val parameters: Map<String, Any> = buildMap {
            put("step", step)
            put("error_type", errorType)
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

    /**
     * Tracks when user switches between tabs - helps understand which sections are most used.
     */
    data class TabSwitched(
        val tabName: String,
    ) : NavigationAnalyticsEvent {
        override val name: String = "tab_switched"
        override val parameters: Map<String, Any> = mapOf(
            "tab_name" to tabName,
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

    data object CurrentBookCleared : AppSettingsAnalyticsEvent {
        override val name: String = "current_book_cleared"
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
