package com.retro99.login.ui.login

import com.retro99.analytics.api.Analytics
import com.retro99.analytics.api.AnalyticsEvent
import com.retro99.analytics.api.DiagnosticContext
import kotlin.test.Test
import kotlin.test.assertEquals

class LoginAnalyticsIdentityTest {

    @Test
    fun clearsProviderIdentityWithoutDerivingAccountOrServerIdentifiers() {
        val analytics = RecordingAnalytics()

        clearLoginAnalyticsIdentity(analytics)

        assertEquals(listOf<String?>(null), analytics.userIds)
    }

    private class RecordingAnalytics : Analytics {
        val userIds = mutableListOf<String?>()

        override fun logException(throwable: Throwable, message: String?) = Unit
        override fun logBreadcrumb(context: DiagnosticContext) = Unit
        override fun logEvent(event: AnalyticsEvent) = Unit
        override fun setUserId(userId: String?) {
            userIds += userId
        }
    }
}
