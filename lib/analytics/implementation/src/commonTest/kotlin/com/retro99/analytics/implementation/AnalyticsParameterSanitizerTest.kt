package com.retro99.analytics.implementation

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue
import com.retro99.analytics.api.DiagnosticContext
import com.retro99.analytics.api.AuthAnalyticsEvent
import com.retro99.analytics.api.NavigationAnalyticsEvent
import com.retro99.analytics.api.AppSettingsAnalyticsEvent
import com.retro99.analytics.api.ReaderAnalyticsEvent
import com.retro99.analytics.api.ReaderSettingsScreenViewed
import com.retro99.analytics.api.ServerManagementAnalyticsEvent
import com.retro99.analytics.api.StatisticsAnalyticsEvent
import com.retro99.analytics.api.CloudAccountAnalyticsEvent
import com.retro99.analytics.api.CloudAccountOperation
import com.retro99.analytics.api.CloudAccountConsentKind
import com.retro99.analytics.api.CloudAccountObservation

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
    fun cloudAccountOperationEventsKeepBoundedFieldsAndStripUnregisteredPayloads() {
        val event = CloudAccountAnalyticsEvent.OperationFailed(
            operation = CloudAccountOperation.Authentication,
            entryPoint = "email_submit",
            isRetry = true,
            reasonCode = "authentication_failed",
            durationMs = 1_200,
            authMethod = "email",
            mode = "sign_in",
        )
        val sanitized = sanitizeAnalyticsParameters(
            event.parameters + mapOf(
                "email" to "private@example.invalid",
                "password" to "private-password",
                "cloud_user_id" to "private-account-id",
                "error_message" to "private server response",
            ),
        )

        assertEquals("cloud_account_operation_failed", event.name)
        assertEquals(
            mapOf(
                "screen" to "sync_and_backup",
                "action" to "authenticate_account",
                "operation" to "cloud_authentication",
                "entry_point" to "email_submit",
                "stage" to "terminal",
                "outcome" to "failed",
                "is_retry" to true,
                "auth_method" to "email",
                "mode" to "sign_in",
                "reason_code" to "authentication_failed",
                "duration_ms" to 1_200L,
            ),
            sanitized,
        )
    }

    @Test
    fun cloudEmailValidationEventKeepsOnlyBoundedFieldAndDropsEmailPayload() {
        val event = CloudAccountAnalyticsEvent.EmailValidationResult(
            isValid = false,
            mode = "sign_in",
        )
        val sanitized = sanitizeAnalyticsParameters(
            event.parameters + ("email" to "private@example.invalid"),
        )

        assertEquals("cloud_account_email_validation_result", event.name)
        assertEquals(
            mapOf(
                "screen" to "sync_and_backup",
                "action" to "validate_email",
                "operation" to "cloud_email_validation",
                "field" to "email",
                "stage" to "validation",
                "outcome" to "failed",
                "mode" to "sign_in",
                "reason_code" to "invalid_format",
            ),
            sanitized,
        )

        val recovered = sanitizeAnalyticsParameters(
            CloudAccountAnalyticsEvent.EmailValidationResult(isValid = true, mode = "sign_in").parameters,
        )
        assertEquals("succeeded", recovered["outcome"])
        assertFalse("reason_code" in recovered)
    }

    @Test
    fun cloudConsentEventsUseBoundedConsentKinds() {
        val event = CloudAccountAnalyticsEvent.ConsentChanged(
            kind = CloudAccountConsentKind.UploadRights,
            isAccepted = true,
        )

        assertEquals(
            mapOf(
                "screen" to "sync_and_backup",
                "action" to "change_consent",
                "operation" to "cloud_account_consent",
                "consent_kind" to "upload_rights",
                "is_enabled" to true,
            ),
            sanitizeAnalyticsParameters(event.parameters),
        )
        val sanitizedWithUnregisteredConsent = sanitizeAnalyticsParameters(
            event.parameters + ("consent_kind" to "private_checkbox_value"),
        )
        assertFalse("consent_kind" in sanitizedWithUnregisteredConsent)
        assertFalse(sanitizedWithUnregisteredConsent.values.contains("private_checkbox_value"))
    }

    @Test
    fun cloudObservationEventsKeepOnlyRegisteredObservationKinds() {
        val event = CloudAccountAnalyticsEvent.ObservationFailed(
            observation = CloudAccountObservation.AuthState,
            reasonCode = "auth_state_observation_failed",
        )

        assertEquals("auth_state", sanitizeAnalyticsParameters(event.parameters)["observation"])
        assertFalse("private_observation" in sanitizeAnalyticsParameters(
            event.parameters + ("observation" to "private_observation"),
        ))
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
    fun tabNavigationDistinguishesAttemptSuccessFailureAndReselection() {
        val attempted = sanitizeAnalyticsParameters(
            NavigationAnalyticsEvent.TabSwitchAttempted(
                sourceTab = "statistics",
                destinationTab = "settings",
            ).parameters + ("profile_name" to "private profile"),
        )
        val succeeded = sanitizeAnalyticsParameters(
            NavigationAnalyticsEvent.TabSwitched(
                sourceTab = "statistics",
                destinationTab = "settings",
            ).parameters,
        )
        val failed = sanitizeAnalyticsParameters(
            NavigationAnalyticsEvent.TabSwitched(
                sourceTab = "books",
                destinationTab = "settings",
                outcome = NavigationAnalyticsEvent.TabSwitchOutcome.Failed,
            ).parameters,
        )
        val reselected = sanitizeAnalyticsParameters(
            NavigationAnalyticsEvent.TabReselected(tabName = "settings").parameters,
        )

        assertEquals(
            mapOf(
                "screen" to "home",
                "source_tab" to "statistics",
                "destination_tab" to "settings",
                "entry_point" to "bottom_navigation",
                "action" to "switch_tab",
                "operation" to "tab_navigation",
                "stage" to "navigation",
                "outcome" to "started",
            ),
            attempted,
        )
        assertEquals("statistics", succeeded["source_tab"])
        assertEquals("settings", succeeded["destination_tab"])
        assertEquals("bottom_navigation", succeeded["entry_point"])
        assertEquals("succeeded", succeeded["outcome"])
        assertEquals("settings", succeeded["tab_name"])
        assertEquals("failed", failed["outcome"])
        assertEquals("books", failed["source_tab"])
        assertEquals("settings", reselected["source_tab"])
        assertEquals("settings", reselected["destination_tab"])
        assertEquals("reselect_tab", reselected["action"])
        assertEquals("unchanged", reselected["outcome"])
    }

    @Test
    fun bubblePositionEventsRetainOnlyTheBoundedSideAndOutcome() {
        val attempted = sanitizeAnalyticsParameters(
            NavigationAnalyticsEvent.BubblePositionSaveAttempted(
                bubbleSide = "end",
                isRetry = false,
            ).parameters + mapOf(
                "y_fraction" to 0.37f,
                "profile_id" to "private-profile-id",
            ),
        )
        val failed = sanitizeAnalyticsParameters(
            NavigationAnalyticsEvent.BubblePositionSaveCompleted(
                bubbleSide = "start",
                outcome = NavigationAnalyticsEvent.BubblePositionSaveOutcome.Failed,
                isRetry = true,
            ).parameters + mapOf("book_title" to "private title"),
        )
        val unsafeSide = sanitizeAnalyticsParameters(
            NavigationAnalyticsEvent.BubblePositionSaveAttempted(
                bubbleSide = "private-side-value",
                isRetry = false,
            ).parameters,
        )

        assertEquals("end", attempted["bubble_side"])
        assertEquals("started", attempted["outcome"])
        assertFalse("y_fraction" in attempted)
        assertFalse("profile_id" in attempted)
        assertEquals("start", failed["bubble_side"])
        assertEquals("failed", failed["outcome"])
        assertEquals("preference_write_failed", failed["reason_code"])
        assertEquals(true, failed["is_retry"])
        assertFalse("book_title" in failed)
        assertFalse("bubble_side" in unsafeSide)
    }

    @Test
    fun continueReadingOpenEventsRetainBoundedSourceAndMediaOnly() {
        val attempted = sanitizeAnalyticsParameters(
            NavigationAnalyticsEvent.ContinueReadingOpenAttempted(
                entryPoint = com.retro99.analytics.api.ContinueReadingEntryPoint.FloatingBubble,
                mediaType = "readaloud",
                isRetry = false,
            ).parameters + mapOf(
                "book_uuid" to "private-book-id",
                "book_title" to "private title",
                "profile_id" to "private-profile-id",
            ),
        )
        val completed = sanitizeAnalyticsParameters(
            NavigationAnalyticsEvent.ContinueReadingOpenCompleted(
                entryPoint = com.retro99.analytics.api.ContinueReadingEntryPoint.Shelf,
                mediaType = "audiobook",
                outcome = NavigationAnalyticsEvent.ContinueReadingOpenOutcome.Failed,
                reasonCode = NavigationAnalyticsEvent.ContinueReadingOpenReasonCode.ServerNotAuthenticated,
            ).parameters,
        )
        val unsafeMedia = sanitizeAnalyticsParameters(
            NavigationAnalyticsEvent.ContinueReadingOpenAttempted(
                entryPoint = com.retro99.analytics.api.ContinueReadingEntryPoint.Shelf,
                mediaType = "private_type",
                isRetry = false,
            ).parameters,
        )

        assertEquals("floating_bubble", attempted["entry_point"])
        assertEquals("readaloud", attempted["media_type"])
        assertEquals("started", attempted["outcome"])
        assertFalse("book_uuid" in attempted)
        assertFalse("book_title" in attempted)
        assertFalse("profile_id" in attempted)
        assertEquals("continue_reading_shelf", completed["entry_point"])
        assertEquals("audiobook", completed["media_type"])
        assertEquals("failed", completed["outcome"])
        assertEquals("server_not_authenticated", completed["reason_code"])
        assertFalse("media_type" in unsafeMedia)
    }

    @Test
    fun continueReadingProfileResolutionUsesOnlyBoundedAvailability() {
        val event = NavigationAnalyticsEvent.ContinueReadingProfileStateResolved(
            isAvailable = false,
            isProfileSwitch = true,
        )
        val profileSwitch = sanitizeAnalyticsParameters(
            event.parameters + mapOf(
                "profile_id" to "private-profile-id",
                "book_uuid" to "private-book-id",
            ),
        )
        val initialLoad = sanitizeAnalyticsParameters(
            NavigationAnalyticsEvent.ContinueReadingProfileStateResolved(
                isAvailable = true,
                isProfileSwitch = false,
            ).parameters,
        )

        assertEquals("continue_reading_profile_state_resolved", event.name)
        assertEquals("unavailable", profileSwitch["outcome"])
        assertEquals("profile_switch", profileSwitch["reason_code"])
        assertFalse("profile_id" in profileSwitch)
        assertFalse("book_uuid" in profileSwitch)
        assertEquals("available", initialLoad["outcome"])
        assertEquals("initial_load", initialLoad["reason_code"])
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
    fun serverManagementExposureRetainsOnlyBoundedSourceAndEntryPoint() {
        val sanitized = sanitizeAnalyticsParameters(
            ServerManagementAnalyticsEvent.ScreenViewed(
                sourceScreen = "app_settings",
                entryPoint = "servers_row",
            ).parameters + ("server_url" to "https://private.example/account"),
        )

        assertEquals(
            mapOf(
                "screen" to "server_management",
                "source_screen" to "app_settings",
                "entry_point" to "servers_row",
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
    fun nestedBackEventsRetainBoundedRouteAndTerminalOutcome() {
        val attempted = sanitizeAnalyticsParameters(
            NavigationAnalyticsEvent.BackNavigationAttempted(
                sourceScreen = "server_management",
                destinationScreen = "app_settings",
                entryPoint = "toolbar_back",
            ).parameters + ("server_id" to "private-id"),
        )
        val succeeded = sanitizeAnalyticsParameters(
            NavigationAnalyticsEvent.BackNavigationCompleted(
                sourceScreen = "server_management",
                destinationScreen = "app_settings",
                entryPoint = "toolbar_back",
                outcome = NavigationAnalyticsEvent.BackNavigationOutcome.Succeeded,
            ).parameters,
        )
        val failed = sanitizeAnalyticsParameters(
            NavigationAnalyticsEvent.BackNavigationCompleted(
                sourceScreen = "server_management",
                destinationScreen = "app_settings",
                entryPoint = "system_back",
                outcome = NavigationAnalyticsEvent.BackNavigationOutcome.Failed,
            ).parameters,
        )

        assertEquals("server_management", attempted["screen"])
        assertEquals("app_settings", attempted["destination_screen"])
        assertEquals("toolbar_back", attempted["entry_point"])
        assertEquals("started", attempted["outcome"])
        assertFalse(attempted.containsKey("server_id"))
        assertEquals("succeeded", succeeded["outcome"])
        assertEquals("system_back", failed["entry_point"])
        assertEquals("failed", failed["outcome"])
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
        val restored = sanitizeAnalyticsParameters(
            NavigationAnalyticsEvent.LastBookLaunchCompleted(
                screen = "reader",
                outcome = NavigationAnalyticsEvent.LastBookLaunchOutcome.Skipped,
                reasonCode = "reader_route_restored",
                bookType = "ebook",
            ).parameters,
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
        assertEquals(
            mapOf(
                "screen" to "reader",
                "source_screen" to "home",
                "entry_point" to "app_launch",
                "action" to "open_last_book",
                "operation" to "reader_open",
                "stage" to "terminal",
                "outcome" to "skipped",
                "reason_code" to "reader_route_restored",
                "book_type" to "ebook",
            ),
            restored,
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
                isRetry = true,
            ).parameters,
        )
        val failed = sanitizeAnalyticsParameters(
            AuthAnalyticsEvent.LoginFailed(
                serverType = "audiobookshelf",
                authMethod = "credentials",
                errorType = "network_unavailable",
                durationMs = 250,
                isRetry = true,
            ).parameters,
        )
        val cancelled = sanitizeAnalyticsParameters(
            AuthAnalyticsEvent.LoginCancelled(
                serverType = "storyteller",
                authMethod = "oauth",
                reasonCode = "oauth_cancelled",
                durationMs = 4_000,
                isRetry = true,
            ).parameters,
        )
        val abandoned = sanitizeAnalyticsParameters(
            AuthAnalyticsEvent.LoginAbandoned(
                serverType = "storyteller",
                authMethod = "oauth",
                reasonCode = "left_login_screen",
                durationMs = 2_500,
                isRetry = true,
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
                "is_retry" to true,
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
                "is_retry" to true,
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
                "is_retry" to true,
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
                "is_retry" to true,
                "outcome" to "abandoned",
                "duration_ms" to 2_500L,
            ),
            abandoned,
        )
    }

    @Test
    fun serverTypePickerEventsRetainBoundedAttemptSelectionAndCancellation() {
        val attempted = sanitizeAnalyticsParameters(
            AuthAnalyticsEvent.ServerTypePickerAttempted("storyteller").parameters +
                mapOf("server_url" to "https://private.example", "username" to "private-user"),
        )
        val selected = sanitizeAnalyticsParameters(
            AuthAnalyticsEvent.ServerTypeSelected(
                previousServerType = "storyteller",
                serverType = "audiobookshelf",
            ).parameters,
        )
        val cancelled = sanitizeAnalyticsParameters(
            AuthAnalyticsEvent.ServerTypePickerCancelled(
                serverType = "audiobookshelf",
                reasonCode = "dismiss_request",
            ).parameters,
        )
        val unsafeServerType = sanitizeAnalyticsParameters(
            mapOf("server_type" to "private server name", "previous_server_type" to "custom-host"),
        )

        assertEquals(
            mapOf(
                "screen" to "login",
                "action" to "select_server_type",
                "operation" to "server_type_picker",
                "stage" to "menu_open",
                "outcome" to "started",
                "server_type" to "storyteller",
            ),
            attempted,
        )
        assertEquals(
            mapOf(
                "screen" to "login",
                "action" to "select_server_type",
                "operation" to "server_type_picker",
                "stage" to "terminal",
                "outcome" to "succeeded",
                "previous_server_type" to "storyteller",
                "server_type" to "audiobookshelf",
            ),
            selected,
        )
        assertEquals(
            mapOf(
                "screen" to "login",
                "action" to "select_server_type",
                "operation" to "server_type_picker",
                "stage" to "terminal",
                "outcome" to "cancelled",
                "server_type" to "audiobookshelf",
                "reason_code" to "dismiss_request",
            ),
            cancelled,
        )
        assertEquals(emptyMap(), unsafeServerType)
    }

    @Test
    fun loginUrlHelpEventsRetainBoundedOperationAndDismissalDimensions() {
        val attempted = sanitizeAnalyticsParameters(
            AuthAnalyticsEvent.LoginUrlHelpAttempted("storyteller").parameters +
                mapOf("url" to "https://private.example", "tooltip_text" to "private content"),
        )
        val opened = sanitizeAnalyticsParameters(
            AuthAnalyticsEvent.LoginUrlHelpOpened("audiobookshelf").parameters,
        )
        val dismissed = sanitizeAnalyticsParameters(
            AuthAnalyticsEvent.LoginUrlHelpDismissed(
                serverType = "storyteller",
                reasonCode = "got_it",
            ).parameters,
        )

        assertEquals(
            mapOf(
                "screen" to "login",
                "action" to "view_url_help",
                "operation" to "url_help_tooltip",
                "stage" to "started",
                "outcome" to "started",
                "server_type" to "storyteller",
            ),
            attempted,
        )
        assertEquals(
            mapOf(
                "screen" to "login",
                "action" to "view_url_help",
                "operation" to "url_help_tooltip",
                "stage" to "visible",
                "outcome" to "succeeded",
                "server_type" to "audiobookshelf",
            ),
            opened,
        )
        assertEquals(
            mapOf(
                "screen" to "login",
                "action" to "view_url_help",
                "operation" to "url_help_tooltip",
                "stage" to "terminal",
                "outcome" to "cancelled",
                "server_type" to "storyteller",
                "reason_code" to "got_it",
            ),
            dismissed,
        )
    }

    @Test
    fun loginValidationEventAllowsOnlyKnownFieldAndSafeReason() {
        val validation = sanitizeAnalyticsParameters(
            AuthAnalyticsEvent.LoginValidationFailed(
                serverType = "storyteller",
                field = "server_url",
                reasonCode = "invalid_url",
            ).parameters + mapOf(
                "url" to "not a url",
                "server_url" to "https://private.example",
                "username" to "private-user",
                "custom_field" to "private_form_value",
            ),
        )
        val unsafeField = sanitizeAnalyticsParameters(
            mapOf("field" to "private_form_value", "screen" to "login"),
        )
        val requiredFields = sanitizeAnalyticsParameters(
            AuthAnalyticsEvent.LoginValidationFailed(
                serverType = "storyteller",
                field = "required_fields",
                reasonCode = "required_fields_missing",
            ).parameters,
        )

        assertEquals(
            mapOf(
                "screen" to "login",
                "action" to "validate_form",
                "operation" to "login_validation",
                "stage" to "terminal",
                "outcome" to "failed",
                "server_type" to "storyteller",
                "field" to "server_url",
                "reason_code" to "invalid_url",
            ),
            validation,
        )
        assertEquals(mapOf("screen" to "login"), unsafeField)
        assertEquals("required_fields", requiredFields["field"])
        assertEquals("required_fields_missing", requiredFields["reason_code"])
    }

    @Test
    fun loginPasswordVisibilityEventKeepsOnlyBooleanVisibilityState() {
        val sanitized = sanitizeAnalyticsParameters(
            AuthAnalyticsEvent.LoginPasswordVisibilityChanged(isVisible = true).parameters,
        )

        assertEquals("login", sanitized["screen"])
        assertEquals("toggle_password_visibility", sanitized["action"])
        assertEquals(true, sanitized["is_visible"])
        assertFalse(sanitized.containsKey("password"))
    }

    @Test
    fun serverAddEventIsAnAttemptWithBoundedNavigationContextNotACompletedAddition() {
        val event = ServerManagementAnalyticsEvent.ServerAddAttempted
        val sanitized = sanitizeAnalyticsParameters(event.parameters)

        assertEquals("server_add_attempted", event.name)
        assertEquals(
            mapOf(
                "screen" to "server_management",
                "source_screen" to "server_management",
                "destination_screen" to "login",
                "entry_point" to "add_server_button",
                "action" to "add_server",
                "operation" to "server_add",
                "stage" to "navigation",
                "outcome" to "started",
            ),
            sanitized,
        )
        assertFalse("server_type" in event.parameters)
    }

    @Test
    fun serverMutationAttemptFailureAndSuccessUseBoundedOutcomes() {
        val operation = ServerManagementAnalyticsEvent.Operation.Logout
        val events = listOf(
            ServerManagementAnalyticsEvent.OperationAttempted(operation, "storyteller", isRetry = true),
            ServerManagementAnalyticsEvent.OperationFailed(operation, "storyteller", isRetry = true),
            ServerManagementAnalyticsEvent.ServerLoggedOut("storyteller", isRetry = true),
        )

        assertEquals(
            listOf("server_logout_attempted", "server_logout_failed", "server_logged_out"),
            events.map { it.name },
        )
        val sanitized = events.map { sanitizeAnalyticsParameters(it.parameters) }
        assertEquals("started", sanitized[0]["outcome"])
        assertEquals("failed", sanitized[1]["outcome"])
        assertEquals("server_logout_failed", sanitized[1]["reason_code"])
        assertEquals("succeeded", sanitized[2]["outcome"])
        assertEquals(true, sanitized[2]["is_retry"])
        assertTrue(sanitized.all { it["server_type"] == "storyteller" })
        assertFalse(sanitized.any { "server_id" in it || "server_url" in it || "username" in it })
    }

    @Test
    fun serverListLoadAndObservationEventsUseBoundedAttemptRetryAndFailureContext() {
        val events = listOf(
            ServerManagementAnalyticsEvent.ServerListLoadAttempted(isRetry = true),
            ServerManagementAnalyticsEvent.ServerListLoadCompleted(
                outcome = ServerManagementAnalyticsEvent.ServerListLoadOutcome.Failed,
                isRetry = true,
            ),
            ServerManagementAnalyticsEvent.ServerListObservationFailed(isRetry = false),
        )
        val sanitized = events.map { event ->
            sanitizeAnalyticsParameters(
                event.parameters + mapOf(
                    "server_id" to "private-id",
                    "server_url" to "https://private.example/path",
                    "error_message" to "private response text",
                ),
            )
        }

        assertEquals(
            listOf("server_list_load_attempted", "server_list_load_completed", "server_list_observation_failed"),
            events.map { it.name },
        )
        assertEquals("started", sanitized[0]["outcome"])
        assertEquals(true, sanitized[0]["is_retry"])
        assertEquals("failed", sanitized[1]["outcome"])
        assertEquals("server_list_load_failed", sanitized[1]["reason_code"])
        assertEquals("server_list_observation_failed", sanitized[2]["reason_code"])
        assertTrue(sanitized.all { it["screen"] == "server_management" })
        assertFalse(sanitized.any { "server_id" in it || "server_url" in it || "error_message" in it })
    }

    @Test
    fun existingServerLoginAttemptContainsBoundedNavigationRetryAndServerType() {
        val event = ServerManagementAnalyticsEvent.ServerLoginAttempted("storyteller")
        val sanitized = sanitizeAnalyticsParameters(event.parameters)

        assertEquals("server_login_attempted", event.name)
        assertEquals("server_management", sanitized["source_screen"])
        assertEquals("login", sanitized["destination_screen"])
        assertEquals("server_card_login", sanitized["entry_point"])
        assertEquals("started", sanitized["outcome"])
        assertEquals("storyteller", sanitized["server_type"])
        assertEquals(false, sanitized["is_retry"])
        assertFalse("server_id" in sanitized || "server_url" in sanitized)
    }

    @Test
    fun existingServerRetryNavigationMarksRetryWithoutAddingIdentifiers() {
        val event = ServerManagementAnalyticsEvent.ServerLoginAttempted(
            serverType = "storyteller",
            isRetry = true,
        )
        val sanitized = sanitizeAnalyticsParameters(event.parameters)

        assertEquals(true, sanitized["is_retry"])
        assertFalse("server_id" in sanitized || "server_url" in sanitized)
    }

    @Test
    fun profileEventsKeepBoundedRouteOutcomeAndRetryDimensionsOnly() {
        val viewed = sanitizeAnalyticsParameters(
            AppSettingsAnalyticsEvent.ScreenViewed(
                sourceScreen = "books",
                entryPoint = "bottom_navigation",
            ).parameters,
        )
        val attempted = sanitizeAnalyticsParameters(
            AppSettingsAnalyticsEvent.ProfileOperationAttempted(
                profileOperation = AppSettingsAnalyticsEvent.ProfileOperation.Create,
                isRetry = true,
            ).parameters + mapOf(
                "profile_name" to "private profile",
                "profile_id" to "private-id",
            ),
        )

        assertEquals("app_settings", viewed["screen"])
        assertEquals("books", viewed["source_screen"])
        assertEquals("bottom_navigation", viewed["entry_point"])
        assertEquals("started", attempted["outcome"])
        assertEquals("profile_create", attempted["operation"])
        assertEquals(true, attempted["is_retry"])
        assertFalse("profile_name" in attempted || "profile_id" in attempted)
    }

    @Test
    fun showContinueReadingToggleEventRetainsOnlyCommittedBooleanState() {
        val disabled = AppSettingsAnalyticsEvent.ShowContinueReadingToggled(isEnabled = false)
        val enabled = AppSettingsAnalyticsEvent.ShowContinueReadingToggled(isEnabled = true, isRetry = true)

        assertEquals("show_continue_reading_toggled", disabled.name)
        assertEquals(
            mapOf("is_enabled" to false, "is_retry" to false),
            sanitizeAnalyticsParameters(disabled.parameters),
        )
        assertEquals(
            mapOf("is_enabled" to true, "is_retry" to true),
            sanitizeAnalyticsParameters(enabled.parameters),
        )
        assertEquals(
            mapOf("is_enabled" to true, "is_retry" to true),
            sanitizeAnalyticsParameters(enabled.parameters + ("profile_id" to "private-profile-id")),
        )
    }

    @Test
    fun appSettingToggleAttemptFailureAndCancellationUseBoundedDimensions() {
        val setting = AppSettingsAnalyticsEvent.SettingToggle.OpenLastBookOnLaunch
        val attempted = sanitizeAnalyticsParameters(
            AppSettingsAnalyticsEvent.SettingToggleAttempted(
                setting = setting,
                isEnabled = false,
                isRetry = true,
            ).parameters + mapOf("profile_id" to "private-profile-id"),
        )
        val failed = sanitizeAnalyticsParameters(
            AppSettingsAnalyticsEvent.SettingToggleFailed(
                setting = setting,
                isEnabled = false,
                isRetry = true,
            ).parameters + mapOf("error_message" to "private storage path"),
        )
        val cancelled = sanitizeAnalyticsParameters(
            AppSettingsAnalyticsEvent.SettingToggleCancelled(
                setting = setting,
                isEnabled = false,
                isRetry = true,
            ).parameters,
        )

        assertEquals("app_setting_toggle_attempted", AppSettingsAnalyticsEvent.SettingToggleAttempted(setting, false, true).name)
        assertEquals("app_setting_toggle_failed", AppSettingsAnalyticsEvent.SettingToggleFailed(setting, false, true).name)
        assertEquals("app_setting_toggle_cancelled", AppSettingsAnalyticsEvent.SettingToggleCancelled(setting, false, true).name)
        assertEquals("open_last_book_on_launch", attempted["setting_name"])
        assertEquals("open_last_book_on_launch", attempted["operation"])
        assertEquals("started", attempted["outcome"])
        assertEquals(false, attempted["is_enabled"])
        assertEquals(true, attempted["is_retry"])
        assertEquals("failed", failed["outcome"])
        assertEquals("preference_write_failed", failed["reason_code"])
        assertEquals(true, failed["is_retry"])
        assertEquals("cancelled", cancelled["outcome"])
        assertEquals("operation_cancelled", cancelled["reason_code"])
        assertFalse("profile_id" in attempted || "error_message" in failed)
    }

    @Test
    fun duplicateProfileNameValidationEventUsesOnlyBoundedOperationAndReason() {
        val event = AppSettingsAnalyticsEvent.ProfileNameValidationFailed(
            profileOperation = AppSettingsAnalyticsEvent.ProfileOperation.Rename,
            reason = AppSettingsAnalyticsEvent.ProfileNameValidationReason.DuplicateName,
        )

        assertEquals("profile_name_validation_failed", event.name)
        assertEquals(
            mapOf(
                "screen" to "app_settings",
                "action" to "rename_profile",
                "operation" to "profile_rename",
                "stage" to "validation",
                "outcome" to "rejected",
                "reason_code" to "duplicate_name",
            ),
            sanitizeAnalyticsParameters(
                event.parameters + mapOf(
                    "profile_name" to "private name",
                    "profile_id" to "private-profile-id",
                ),
            ),
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
                sourceScreen = "books",
                destinationScreen = "book_detail",
                entryPoint = "toolbar_back",
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
            "diagnostic_context screen=books_library source_screen=books destination_screen=book_detail " +
                "entry_point=toolbar_back action=refresh operation=load_books " +
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

    @Test
    fun bookListSortAndViewParametersRetainOnlyKnownEnumValues() {
        val selectedValues = sanitizeAnalyticsParameters(
            mapOf(
                "sort_config" to "date_added_descending",
                "view_mode" to "grid",
            ),
        )
        val typeNames = sanitizeAnalyticsParameters(
            mapOf(
                "sort_config" to "BookSortConfig",
                "view_mode" to "BookListViewMode",
            ),
        )
        val unknownValues = sanitizeAnalyticsParameters(
            mapOf(
                "sort_config" to "private_value",
                "view_mode" to "unknown_layout",
            ),
        )

        assertEquals(
            mapOf("sort_config" to "date_added_descending", "view_mode" to "grid"),
            selectedValues,
        )
        assertEquals(emptyMap(), typeNames)
        assertEquals(emptyMap(), unknownValues)
    }

    @Test
    fun readerSettingsScreenExposureRetainsOnlyBoundedAttribution() {
        val screen = sanitizeAnalyticsParameters(
            ReaderSettingsScreenViewed(
                sourceScreen = "app_settings",
                entryPoint = "reader_settings_row",
            ).parameters,
        )

        assertEquals(
            mapOf(
                "screen" to "reader_settings",
                "source_screen" to "app_settings",
                "entry_point" to "reader_settings_row",
            ),
            screen,
        )
    }

    @Test
    fun readerSettingSaveOutcomesRetainOnlyBoundedOperationDimensions() {
        val attempt = sanitizeAnalyticsParameters(
            ReaderAnalyticsEvent.ReaderSettingSaveAttempted(
                settingName = "theme",
                isRetry = true,
                isUndo = true,
            ).parameters,
        )
        val failure = sanitizeAnalyticsParameters(
            ReaderAnalyticsEvent.ReaderSettingSaveFailed(
                settingName = "theme",
                reasonCode = "database_error",
                isRetry = true,
                isUndo = true,
            ).parameters,
        )
        val privateSetting = sanitizeAnalyticsParameters(
            ReaderAnalyticsEvent.ReaderSettingSaveFailed(
                settingName = "private_setting",
                reasonCode = "database_error",
                isRetry = false,
            ).parameters,
        )

        assertEquals(
            mapOf(
                "screen" to "reader_settings",
                "action" to "undo_setting_change",
                "operation" to "reader_setting_undo",
                "stage" to "started",
                "outcome" to "started",
                "setting_name" to "theme",
                "is_retry" to true,
            ),
            attempt,
        )
        assertEquals(
            mapOf(
                "screen" to "reader_settings",
                "action" to "undo_setting_change",
                "operation" to "reader_setting_undo",
                "stage" to "terminal",
                "outcome" to "failed",
                "reason_code" to "database_error",
                "setting_name" to "theme",
                "is_retry" to true,
            ),
            failure,
        )
        assertFalse("setting_name" in privateSetting)
    }

    @Test
    fun statisticsLoadOutcomesRetainAttemptRetryAndSafeFailureDimensions() {
        val attempt = sanitizeAnalyticsParameters(
            StatisticsAnalyticsEvent.StatisticsLoadAttempted(
                action = "retry_statistics",
                isRetry = true,
            ).parameters + mapOf(
                "profile_id" to "private-profile-id",
            ),
        )
        val success = sanitizeAnalyticsParameters(
            StatisticsAnalyticsEvent.StatisticsLoadSucceeded(
                action = "retry_statistics",
                isRetry = true,
            ).parameters,
        )
        val failure = sanitizeAnalyticsParameters(
            StatisticsAnalyticsEvent.StatisticsLoadFailed(
                action = "retry_statistics",
                isRetry = true,
                reasonCode = "database_error",
            ).parameters + mapOf(
                "error_message" to "private database detail",
                "book_title" to "private title",
            ),
        )

        assertEquals(
            mapOf(
                "screen" to "statistics",
                "action" to "retry_statistics",
                "operation" to "statistics_load",
                "stage" to "started",
                "outcome" to "started",
                "is_retry" to true,
            ),
            attempt,
        )
        assertEquals("succeeded", success["outcome"])
        assertEquals("database_error", failure["reason_code"])
        assertEquals(true, failure["is_retry"])
        assertFalse("profile_id" in attempt)
        assertFalse("error_message" in failure)
        assertFalse("book_title" in failure)
    }

    @Test
    fun statisticsExposureRetainsBoundedRouteAttribution() {
        val parameters = sanitizeAnalyticsParameters(
            StatisticsAnalyticsEvent.StatisticsViewed(
                sourceScreen = "app_settings",
                entryPoint = "statistics_row",
            ).parameters + mapOf(
                "profile_id" to "private-profile-id",
            ),
        )

        assertEquals(
            mapOf(
                "screen" to "statistics",
                "source_screen" to "app_settings",
                "entry_point" to "statistics_row",
            ),
            parameters,
        )
        assertFalse("profile_id" in parameters)
    }

    @Test
    fun statisticsDetailOutcomesDistinguishAttemptSuccessFailureAndCancellation() {
        val attempt = sanitizeAnalyticsParameters(
            StatisticsAnalyticsEvent.StatisticsDetailLoadAttempted(
                action = "load_period_books",
                detailType = "period_books",
                period = "WEEK",
                isRetry = false,
            ).parameters + mapOf("book_uuid" to "private-book-id"),
        )
        val success = sanitizeAnalyticsParameters(
            StatisticsAnalyticsEvent.StatisticsDetailLoadSucceeded(
                action = "load_books_read",
                detailType = "books_read",
                isRetry = true,
            ).parameters,
        )
        val failure = sanitizeAnalyticsParameters(
            StatisticsAnalyticsEvent.StatisticsDetailLoadFailed(
                action = "load_recent_sessions",
                detailType = "recent_sessions",
                isRetry = false,
                reasonCode = "database_error",
            ).parameters + mapOf("book_title" to "private title"),
        )
        val cancelled = sanitizeAnalyticsParameters(
            StatisticsAnalyticsEvent.StatisticsDetailLoadCancelled(
                action = "load_period_books",
                detailType = "period_books",
                period = "TODAY",
                isRetry = true,
                reasonCode = "detail_dismissed",
            ).parameters,
        )

        assertEquals("started", attempt["outcome"])
        assertEquals("statistics_detail_load", attempt["operation"])
        assertEquals("WEEK", attempt["period"])
        assertEquals(false, attempt["is_retry"])
        assertFalse("book_uuid" in attempt)
        assertEquals("succeeded", success["outcome"])
        assertEquals(true, success["is_retry"])
        assertEquals("failed", failure["outcome"])
        assertEquals("database_error", failure["reason_code"])
        assertFalse("book_title" in failure)
        assertEquals("cancelled", cancelled["outcome"])
        assertEquals("detail_dismissed", cancelled["reason_code"])
        assertEquals(true, cancelled["is_retry"])
    }
}
