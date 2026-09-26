package com.retro99.login.ui.login

import com.retro99.analytics.api.Analytics
import com.retro99.analytics.api.AnalyticsEvent
import com.retro99.analytics.api.DiagnosticContext
import com.retro99.base.result.AppError
import com.retro99.base.server.ServerType
import kotlin.test.Test
import kotlin.test.assertEquals

class LoginFailureDiagnosticsTest {

    @Test
    fun expectedAuthenticationRejectionIsNotReported() {
        val analytics = RecordingAnalytics()

        reportUnexpectedLoginFailure(
            analytics = analytics,
            error = AppError.AuthError("rejected"),
            serverType = ServerType.Storyteller,
            authMethod = "credentials",
        )

        assertEquals(0, analytics.exceptions.size)
    }

    @Test
    fun expectedNetworkFailureIsNotReported() {
        val analytics = RecordingAnalytics()

        reportUnexpectedLoginFailure(
            analytics = analytics,
            error = AppError.NetworkError(
                throwable = IllegalStateException("private transport detail"),
                isConnectivity = true,
            ),
            serverType = ServerType.Storyteller,
            authMethod = "credentials",
        )

        assertEquals(0, analytics.exceptions.size)
    }

    @Test
    fun unexpectedFailureIsReportedOnceWithBoundedContext() {
        val analytics = RecordingAnalytics()

        reportUnexpectedLoginFailure(
            analytics = analytics,
            error = AppError.UnknownError(IllegalStateException("private response details")),
            serverType = ServerType.Storyteller,
            authMethod = "credentials",
        )

        assertEquals(1, analytics.exceptions.size)
        assertEquals(
            DiagnosticContext(
                screen = "login",
                action = "sign_in",
                operation = "credentials_login",
                stage = "authentication",
                outcome = "failed",
                reasonCode = "unexpected_failure",
                serverType = "storyteller",
            ),
            analytics.exceptions.single().second,
        )
    }

    private class RecordingAnalytics : Analytics {
        val exceptions = mutableListOf<Pair<Throwable, DiagnosticContext>>()

        override fun logException(throwable: Throwable, message: String?) = Unit
        override fun logException(throwable: Throwable, context: DiagnosticContext) {
            exceptions += throwable to context
        }
        override fun logEvent(event: AnalyticsEvent) = Unit
        override fun setUserId(userId: String?) = Unit
    }
}
