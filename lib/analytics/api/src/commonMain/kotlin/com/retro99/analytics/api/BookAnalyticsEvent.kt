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
        val fileName: String,
    ) : BookAnalyticsEvent {
        override val name: String = "book_imported"
        override val parameters: Map<String, Any> = mapOf(
            "file_name" to fileName,
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
    ) : NetworkAnalyticsEvent {
        override val name: String = "network_request_failed"
        override val parameters: Map<String, Any> = mapOf(
            "endpoint" to endpoint,
            "error_type" to errorType,
            "is_timeout" to isTimeout,
            "is_connectivity" to isConnectivity,
        )
    }
}

