package com.retro99.analytics.implementation

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import com.retro99.analytics.api.DiagnosticContext
import com.retro99.analytics.api.AuthAnalyticsEvent
import com.retro99.analytics.api.NavigationAnalyticsEvent
import com.retro99.analytics.api.AppSettingsAnalyticsEvent
import com.retro99.analytics.api.ReaderAnalyticsEvent

class AnalyticsParameterSanitizerTest {

    @Test
    fun removesPrivateAndUnregisteredDimensions() {
        val sanitized = sanitizeAnalyticsParameters(
            mapOf(
                "book_uuid" to "private-book-id",
                "profile_name" to "private-profile",
                "profile_id" to "private-profile-id",
                "error_message" to "private exception text",
                "server_url_hash" to "server-identifier",
                "font_name" to "private-font-filename",
                "query" to "private search text",
                "preview_text" to "private spoken text",
                "endpoint" to "/user/private-id/books",
            ),
        )

        assertEquals(emptyMap(), sanitized)
    }

    @Test
    fun retainsRegisteredBoundedDimensionsAndMetrics() {
        val sanitized = sanitizeAnalyticsParameters(
            mapOf(
                "screen" to "books_library",
                "entry_point" to "continue_reading",
                "book_type" to "ebook",
                "is_success" to true,
                "progress_percent" to 42,
                "status_code" to 404,
                "duration_ms" to 1_200L,
                "rate" to 1.25f,
            ),
        )

        assertEquals(
            mapOf(
                "screen" to "books_library",
                "entry_point" to "continue_reading",
                "book_type" to "ebook",
                "is_success" to true,
                "progress_percent" to 42,
                "status_code" to 404,
                "duration_ms" to 1_200L,
                "rate" to 1.25f,
            ),
            sanitized,
        )
    }

    @Test
    fun retainsSafeStartupRouteDimensions() {
        val sanitized = sanitizeAnalyticsParameters(
            mapOf(
                "screen" to "home",
                "source_screen" to "splash",
                "entry_point" to "app_launch",
                "outcome" to "success",
            ),
        )

        assertEquals(
            mapOf(
                "screen" to "home",
                "source_screen" to "splash",
                "entry_point" to "app_launch",
                "outcome" to "success",
            ),
            sanitized,
        )
    }

    @Test
    fun homeExposureRetainsOnlyBoundedSourceAndEntryPoint() {
        val sanitized = sanitizeAnalyticsParameters(
            NavigationAnalyticsEvent.HomeViewed(
                sourceScreen = "welcome",
                entryPoint = "browse_without_account",
            ).parameters + ("profile_name" to "private profile"),
        )

        assertEquals(
            mapOf(
                "screen" to "home",
                "source_screen" to "welcome",
                "entry_point" to "browse_without_account",
            ),
            sanitized,
        )
    }

    @Test
    fun rootWelcomeBackRetainsBoundedExitOutcome() {
        val exited = sanitizeAnalyticsParameters(
            NavigationAnalyticsEvent.WelcomeRootBackCompleted(
                NavigationAnalyticsEvent.WelcomeRootBackOutcome.Exited,
            ).parameters,
        )
        val failed = sanitizeAnalyticsParameters(
            NavigationAnalyticsEvent.WelcomeRootBackCompleted(
                NavigationAnalyticsEvent.WelcomeRootBackOutcome.Failed,
            ).parameters,
        )

        assertEquals(
            mapOf(
                "screen" to "welcome",
                "source_screen" to "welcome",
                "destination_screen" to "app_exit",
                "entry_point" to "system_back",
                "outcome" to "exited",
            ),
            exited,
        )
        assertEquals("failed", failed["outcome"])
        assertEquals("app_exit", failed["destination_screen"])
    }

    @Test
    fun appVisibilityRetainsBoundedLifecycleState() {
        val background = sanitizeAnalyticsParameters(
            NavigationAnalyticsEvent.AppVisibilityChanged(
                NavigationAnalyticsEvent.AppVisibility.Background,
            ).parameters,
        )
        val foreground = sanitizeAnalyticsParameters(
            NavigationAnalyticsEvent.AppVisibilityChanged(
                NavigationAnalyticsEvent.AppVisibility.Foreground,
            ).parameters,
        )

        assertEquals(
            mapOf(
                "screen" to "app",
                "action" to "lifecycle",
                "operation" to "app_visibility",
                "stage" to "background",
                "outcome" to "backgrounded",
            ),
            background,
        )
        assertEquals("foreground", foreground["stage"])
        assertEquals("foregrounded", foreground["outcome"])
    }

    @Test
    fun lastBookLaunchEventsRetainBoundedAttemptAndTerminalOutcomesWithoutIdentifiers() {
        val attempted = sanitizeAnalyticsParameters(
            NavigationAnalyticsEvent.LastBookLaunchAttempted(
                bookType = "imported",
            ).parameters + ("book_uuid" to "private-book-id"),
        )
        val completed = sanitizeAnalyticsParameters(
            NavigationAnalyticsEvent.LastBookLaunchCompleted(
                screen = "reader",
                outcome = NavigationAnalyticsEvent.LastBookLaunchOutcome.Failed,
                reasonCode = "publication_open_failed",
                bookType = "imported",
            ).parameters + mapOf(
                "book_uuid" to "private-book-id",
                "book_title" to "private title",
                "file_path" to "/private/path.epub",
            ),
        )

        assertEquals(
            mapOf(
                "screen" to "home",
                "source_screen" to "splash",
                "entry_point" to "app_launch",
                "action" to "open_last_book",
                "operation" to "reader_open",
                "stage" to "navigation",
                "outcome" to "started",
                "book_type" to "imported",
            ),
            attempted,
        )
        assertEquals(
            mapOf(
                "screen" to "reader",
                "source_screen" to "home",
                "entry_point" to "app_launch",
                "action" to "open_last_book",
                "operation" to "reader_open",
                "stage" to "terminal",
                "outcome" to "failed",
                "reason_code" to "publication_open_failed",
                "book_type" to "imported",
            ),
            completed,
        )
    }

    @Test
    fun currentBookClearEventsRetainAttemptFailureAndRetryDimensions() {
        val attempted = sanitizeAnalyticsParameters(
            AppSettingsAnalyticsEvent.CurrentBookClearAttempted(isRetry = true).parameters,
        )
        val failed = sanitizeAnalyticsParameters(
            AppSettingsAnalyticsEvent.CurrentBookClearFailed(isRetry = true).parameters +
                mapOf("book_uuid" to "private-book-id", "error_message" to "private failure"),
        )
        val succeeded = sanitizeAnalyticsParameters(
            AppSettingsAnalyticsEvent.CurrentBookCleared(isRetry = true).parameters,
        )

        assertEquals(
            mapOf(
                "screen" to "app_settings",
                "action" to "clear_current_book",
                "operation" to "clear_current_book",
                "stage" to "started",
                "outcome" to "started",
                "is_retry" to true,
            ),
            attempted,
        )
        assertEquals(
            mapOf(
                "screen" to "app_settings",
                "action" to "clear_current_book",
                "operation" to "clear_current_book",
                "stage" to "terminal",
                "outcome" to "failed",
                "reason_code" to "current_book_clear_failed",
                "is_retry" to true,
            ),
            failed,
        )
        assertEquals(
            mapOf(
                "screen" to "app_settings",
                "action" to "clear_current_book",
                "operation" to "clear_current_book",
                "stage" to "terminal",
                "outcome" to "succeeded",
                "is_retry" to true,
            ),
            succeeded,
        )
    }

    @Test
    fun currentBookTargetEventsRetainSafeOperationDimensionsOnly() {
        val attempted = sanitizeAnalyticsParameters(
            ReaderAnalyticsEvent.CurrentBookTargetSaveAttempted(
                entryPoint = "reading_duration_threshold",
                bookType = "ebook",
                isRetry = false,
            ).parameters + mapOf("book_uuid" to "private-id", "book_title" to "private title"),
        )
        val failed = sanitizeAnalyticsParameters(
            ReaderAnalyticsEvent.CurrentBookTargetSaveFailed(
                entryPoint = "reader_close",
                bookType = "ebook",
                isRetry = true,
            ).parameters + mapOf("error_message" to "private failure"),
        )
        val completed = sanitizeAnalyticsParameters(
            ReaderAnalyticsEvent.CurrentBookTargetSaveCompleted(
                entryPoint = "reading_duration_threshold",
                bookType = "ebook",
                isRetry = true,
            ).parameters,
        )

        assertEquals(
            mapOf(
                "screen" to "reader",
                "action" to "save_current_book_target",
                "operation" to "current_book_target_save",
                "stage" to "started",
                "outcome" to "started",
                "entry_point" to "reading_duration_threshold",
                "book_type" to "ebook",
                "is_retry" to false,
            ),
            attempted,
        )
        assertEquals(
            mapOf(
                "screen" to "reader",
                "action" to "save_current_book_target",
                "operation" to "current_book_target_save",
                "stage" to "terminal",
                "outcome" to "failed",
                "reason_code" to "current_book_target_save_failed",
                "entry_point" to "reader_close",
                "book_type" to "ebook",
                "is_retry" to true,
            ),
            failed,
        )
        assertEquals(
            mapOf(
                "screen" to "reader",
                "action" to "save_current_book_target",
                "operation" to "current_book_target_save",
                "stage" to "terminal",
                "outcome" to "succeeded",
                "entry_point" to "reading_duration_threshold",
                "book_type" to "ebook",
                "is_retry" to true,
            ),
            completed,
        )
    }

    @Test
    fun welcomeEventsRetainOnlyBoundedRouteAndOutcomeDimensions() {
        val viewed = sanitizeAnalyticsParameters(
            AuthAnalyticsEvent.WelcomeViewed(
                sourceScreen = "splash",
                entryPoint = "app_launch",
            ).parameters,
        )
        val attempted = sanitizeAnalyticsParameters(
            AuthAnalyticsEvent.WelcomeActionAttempted("get_started").parameters,
        )
        val completed = sanitizeAnalyticsParameters(
            AuthAnalyticsEvent.WelcomeActionCompleted(
                action = "browse_without_account",
                outcome = "succeeded",
            ).parameters,
        )

        assertEquals(
            mapOf("screen" to "welcome", "source_screen" to "splash", "entry_point" to "app_launch"),
            viewed,
        )
        assertEquals(
            mapOf("screen" to "welcome", "action" to "get_started", "outcome" to "started"),
            attempted,
        )
        assertEquals(
            mapOf("screen" to "welcome", "action" to "browse_without_account", "outcome" to "succeeded"),
            completed,
        )
    }

    @Test
    fun loginEventsRetainRouteMethodRetryAndTerminalOutcomesWithoutIdentifiers() {
        val viewed = sanitizeAnalyticsParameters(
            AuthAnalyticsEvent.LoginViewed(
                sourceScreen = "welcome",
                entryPoint = "get_started",
            ).parameters,
        )
        val attempted = sanitizeAnalyticsParameters(
            AuthAnalyticsEvent.LoginAttempted(
                serverType = "storyteller",
                authMethod = "credentials",
                isRetry = true,
            ).parameters,
        )
        val succeeded = sanitizeAnalyticsParameters(
            AuthAnalyticsEvent.LoginSucceeded(
                serverType = "storyteller",
                authMethod = "credentials",
                durationMs = 1_250,
            ).parameters,
        )
        val failed = sanitizeAnalyticsParameters(
            AuthAnalyticsEvent.LoginFailed(
                serverType = "audiobookshelf",
                authMethod = "credentials",
                errorType = "network_unavailable",
                durationMs = 250,
            ).parameters,
        )
        val cancelled = sanitizeAnalyticsParameters(
            AuthAnalyticsEvent.LoginCancelled(
                serverType = "storyteller",
                authMethod = "oauth",
                reasonCode = "oauth_cancelled",
                durationMs = 4_000,
            ).parameters,
        )
        val abandoned = sanitizeAnalyticsParameters(
            AuthAnalyticsEvent.LoginAbandoned(
                serverType = "storyteller",
                authMethod = "oauth",
                reasonCode = "left_login_screen",
                durationMs = 2_500,
            ).parameters,
        )

        assertEquals(
            mapOf("screen" to "login", "source_screen" to "welcome", "entry_point" to "get_started"),
            viewed,
        )
        assertEquals(
            mapOf(
                "screen" to "login",
                "action" to "sign_in",
                "server_type" to "storyteller",
                "auth_method" to "credentials",
                "is_retry" to true,
                "outcome" to "started",
            ),
            attempted,
        )
        assertEquals(
            mapOf(
                "screen" to "login",
                "action" to "sign_in",
                "server_type" to "storyteller",
                "auth_method" to "credentials",
                "outcome" to "succeeded",
                "duration_ms" to 1_250L,
            ),
            succeeded,
        )
        assertEquals(
            mapOf(
                "screen" to "login",
                "action" to "sign_in",
                "server_type" to "audiobookshelf",
                "auth_method" to "credentials",
                "error_type" to "network_unavailable",
                "outcome" to "failed",
                "duration_ms" to 250L,
            ),
            failed,
        )
        assertEquals(
            mapOf(
                "screen" to "login",
                "action" to "sign_in",
                "server_type" to "storyteller",
                "auth_method" to "oauth",
                "reason_code" to "oauth_cancelled",
                "outcome" to "cancelled",
                "duration_ms" to 4_000L,
            ),
            cancelled,
        )
        assertEquals(
            mapOf(
                "screen" to "login",
                "action" to "sign_in",
                "server_type" to "storyteller",
                "auth_method" to "oauth",
                "reason_code" to "left_login_screen",
                "outcome" to "abandoned",
                "duration_ms" to 2_500L,
            ),
            abandoned,
        )
    }

    @Test
    fun rejectsFreeFormValuesAndUnexpectedTypesEvenForRegisteredKeys() {
        val sanitized = sanitizeAnalyticsParameters(
            mapOf(
                "source" to "https://example.invalid/user/private",
                "error_type" to "private failure details with spaces",
                "screen" to "x".repeat(65),
                "is_success" to "true",
                "progress_percent" to 1000,
                "status_code" to 600,
                "duration_ms" to -1L,
                "rate" to Float.NaN,
            ),
        )

        assertEquals(emptyMap(), sanitized)
        assertFalse("profile_name" in sanitized)
    }
}

class DiagnosticPayloadSanitizerTest {

    @Test
    fun removesThrowableAndCauseMessagesButRetainsTypesAndCauseStructure() {
        val cause = IllegalStateException("private cause path /storage/emulated/0/secret.epub")
        val original = IllegalArgumentException("private server response: user@example.invalid", cause)

        val sanitized = sanitizeDiagnosticThrowable(original)

        val root = assertIs<SanitizedDiagnosticException>(sanitized)
        assertEquals("IllegalArgumentException", root.sourceType)
        assertFalse(sanitized.stackTraceToString().contains("user@example.invalid"))
        assertFalse(sanitized.stackTraceToString().contains("secret.epub"))
        val sanitizedCause = assertIs<SanitizedDiagnosticException>(root.cause)
        assertEquals("IllegalStateException", sanitizedCause.sourceType)
    }

    @Test
    fun diagnosticMessageNeverForwardsCallerText() {
        val sanitized = sanitizeDiagnosticMessage("private profile and /private/file.epub")

        assertEquals("Handled failure; free-form context omitted", sanitized)
        assertFalse(sanitized.orEmpty().contains("private profile"))
    }

    @Test
    fun retainsBoundedStructuredDiagnosticContextAndDropsUnsafeValues() {
        val context = sanitizeDiagnosticContext(
            DiagnosticContext(
                screen = "books_library",
                action = "refresh",
                operation = "load_books",
                stage = "remote_fetch",
                outcome = "failed",
                reasonCode = "connection_failed",
                serverType = "audiobookshelf",
                correlationId = "8b64e753-439e-4428-93c8-b39252d30a19",
            ),
        )

        assertEquals(
            "diagnostic_context screen=books_library action=refresh operation=load_books " +
                "stage=remote_fetch outcome=failed reason_code=connection_failed " +
                "server_type=audiobookshelf correlation_id=8b64e753-439e-4428-93c8-b39252d30a19",
            context,
        )
    }

    @Test
    fun diagnosticContextRejectsFreeFormFieldsAndUnknownKeys() {
        val sanitized = sanitizeDiagnosticMessage(
            "diagnostic_context screen=books_library reason_code=private/path profile_name=private",
        )

        assertEquals("diagnostic_context screen=books_library", sanitized)
        assertFalse(sanitized.orEmpty().contains("private"))
    }

    @Test
    fun settingChangesRetainOnlyApprovedValueBuckets() {
        val enumValue = sanitizeAnalyticsParameters(
            mapOf("setting_name" to "theme", "new_value" to "DARK"),
        )
        val numericValue = sanitizeAnalyticsParameters(
            mapOf("setting_name" to "font_size", "new_value" to "22.0"),
        )
        val booleanValue = sanitizeAnalyticsParameters(
            mapOf("setting_name" to "tts_enabled", "new_value" to "true"),
        )

        assertEquals(mapOf("setting_name" to "theme", "value_bucket" to "dark"), enumValue)
        assertEquals(mapOf("setting_name" to "font_size", "value_bucket" to "medium"), numericValue)
        assertEquals(mapOf("setting_name" to "tts_enabled", "value_bucket" to "enabled"), booleanValue)
    }

    @Test
    fun settingChangesNeverForwardRawCustomOrUnboundedValues() {
        val customFont = sanitizeAnalyticsParameters(
            mapOf("setting_name" to "font_family", "new_value" to "private-font-filename.ttf"),
        )
        val rawColor = sanitizeAnalyticsParameters(
            mapOf("setting_name" to "highlight_color_argb", "new_value" to "-2130771968"),
        )
        val invalidNumeric = sanitizeAnalyticsParameters(
            mapOf("setting_name" to "font_size", "new_value" to "999999"),
        )
        val unknownSetting = sanitizeAnalyticsParameters(
            mapOf("setting_name" to "private_setting", "new_value" to "private-value"),
        )

        assertEquals(mapOf("setting_name" to "font_family", "value_bucket" to "custom"), customFont)
        assertFalse(rawColor.containsKey("new_value"))
        assertEquals(mapOf("setting_name" to "highlight_color_argb", "value_bucket" to "mid"), rawColor)
        assertEquals(mapOf("setting_name" to "font_size"), invalidNumeric)
        assertEquals(emptyMap(), unknownSetting)
    }
}
