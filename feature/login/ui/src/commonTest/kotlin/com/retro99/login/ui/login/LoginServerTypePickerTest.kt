package com.retro99.login.ui.login

import com.retro99.analytics.api.Analytics
import com.retro99.analytics.api.AnalyticsEvent
import com.retro99.analytics.api.DiagnosticContext
import com.retro99.base.result.CompletableResult
import com.retro99.base.server.ServerType
import com.retro99.login.domain.LoginRepository
import com.retro99.login.domain.usecase.LoginUseCase
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull

class LoginServerTypePickerTest {

    @Test
    fun selectionCompletesOnePickerAttemptWithBoundedDimensionsAndCorrelatedBreadcrumbs() {
        val analytics = RecordingAnalytics()
        val viewModel = createViewModel(analytics)

        viewModel.onIntent(LoginIntent.OnServerTypePickerOpened)
        viewModel.onIntent(LoginIntent.OnServerTypeSelected(ServerType.Audiobookshelf))

        assertEquals(ServerType.Audiobookshelf, viewModel.currentViewState().selectedServerType)
        assertEquals(
            listOf("login_server_type_picker_attempted", "login_server_type_selected"),
            analytics.events.map { it.name },
        )
        assertEquals("started", analytics.events.first().parameters["outcome"])
        assertEquals("storyteller", analytics.events.first().parameters["server_type"])
        assertEquals("succeeded", analytics.events.last().parameters["outcome"])
        assertEquals("storyteller", analytics.events.last().parameters["previous_server_type"])
        assertEquals("audiobookshelf", analytics.events.last().parameters["server_type"])
        assertEquals(2, analytics.breadcrumbs.size)
        assertEquals("started", analytics.breadcrumbs.first().outcome)
        assertEquals("succeeded", analytics.breadcrumbs.last().outcome)
        assertNotNull(analytics.breadcrumbs.first().correlationId)
        assertEquals(
            analytics.breadcrumbs.first().correlationId,
            analytics.breadcrumbs.last().correlationId,
        )
        assertFalse(analytics.events.any { "username" in it.parameters || "password" in it.parameters })
        assertEquals(0, analytics.exceptions.size)
    }

    @Test
    fun dismissalIsCancelledOnceAndDoesNotEmitSelectionOrException() {
        val analytics = RecordingAnalytics()
        val viewModel = createViewModel(analytics)

        viewModel.onIntent(LoginIntent.OnServerTypePickerOpened)
        viewModel.onIntent(
            LoginIntent.OnServerTypePickerDismissed(ServerTypePickerDismissalReason.DismissRequest),
        )
        viewModel.onIntent(
            LoginIntent.OnServerTypePickerDismissed(ServerTypePickerDismissalReason.DismissRequest),
        )

        assertEquals(ServerType.Storyteller, viewModel.currentViewState().selectedServerType)
        assertEquals(
            listOf("login_server_type_picker_attempted", "login_server_type_picker_cancelled"),
            analytics.events.map { it.name },
        )
        assertEquals("cancelled", analytics.events.last().parameters["outcome"])
        assertEquals("dismiss_request", analytics.events.last().parameters["reason_code"])
        assertEquals(2, analytics.breadcrumbs.size)
        assertEquals("cancelled", analytics.breadcrumbs.last().outcome)
        assertEquals("dismiss_request", analytics.breadcrumbs.last().reasonCode)
        assertEquals(
            analytics.breadcrumbs.first().correlationId,
            analytics.breadcrumbs.last().correlationId,
        )
        assertEquals(0, analytics.exceptions.size)
    }

    @Test
    fun reopeningAfterCancellationStartsANewCorrelatedAttempt() {
        val analytics = RecordingAnalytics()
        val viewModel = createViewModel(analytics)

        viewModel.onIntent(LoginIntent.OnServerTypePickerOpened)
        viewModel.onIntent(
            LoginIntent.OnServerTypePickerDismissed(ServerTypePickerDismissalReason.AnchorToggle),
        )
        viewModel.onIntent(LoginIntent.OnServerTypePickerOpened)
        viewModel.onIntent(LoginIntent.OnServerTypeSelected(ServerType.Storyteller))

        val starts = analytics.breadcrumbs.filter { it.outcome == "started" }
        val terminals = analytics.breadcrumbs.filter { it.outcome != "started" }
        assertEquals(2, starts.size)
        assertEquals(2, terminals.size)
        assertNotEquals(starts.first().correlationId, starts.last().correlationId)
        assertEquals(starts.last().correlationId, terminals.last().correlationId)
        assertEquals(
            listOf("started", "cancelled", "started", "succeeded"),
            analytics.events.map { it.parameters["outcome"] },
        )
    }

    private fun createViewModel(analytics: RecordingAnalytics) = LoginViewModel(
        loginUseCase = LoginUseCase(StubLoginRepository),
        analytics = analytics,
        onSignInSuccess = {},
        onBackClick = {},
    )

    private object StubLoginRepository : LoginRepository {
        override suspend fun login(
            serverType: ServerType,
            serverUrl: String,
            username: String,
            password: String,
        ): CompletableResult = error("Login is outside the picker test scope")

        override suspend fun loginWithOAuth(
            serverType: ServerType,
            serverUrl: String,
        ): CompletableResult = error("OAuth is outside the picker test scope")
    }

    private class RecordingAnalytics : Analytics {
        val events = mutableListOf<AnalyticsEvent>()
        val breadcrumbs = mutableListOf<DiagnosticContext>()
        val exceptions = mutableListOf<Pair<Throwable, DiagnosticContext>>()

        override fun logEvent(event: AnalyticsEvent) {
            events += event
        }

        override fun logBreadcrumb(context: DiagnosticContext) {
            breadcrumbs += context
        }

        override fun logException(throwable: Throwable, message: String?) = Unit

        override fun logException(throwable: Throwable, context: DiagnosticContext) {
            exceptions += throwable to context
        }

        override fun setUserId(userId: String?) = Unit
    }
}
