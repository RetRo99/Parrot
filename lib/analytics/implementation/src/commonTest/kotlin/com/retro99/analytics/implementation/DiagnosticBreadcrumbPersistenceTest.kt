package com.retro99.analytics.implementation

import com.retro99.analytics.api.DiagnosticContext
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DiagnosticBreadcrumbPersistenceTest {
    @Test
    fun clearLogsOutcomeBreadcrumbDoesNotRecreateTheFilesBeingCleared() {
        assertFalse(
            shouldPersistDiagnosticBreadcrumbToFile(
                DiagnosticContext(screen = "app_settings", action = "clear_logs", outcome = "succeeded"),
            ),
        )
        assertTrue(
            shouldPersistDiagnosticBreadcrumbToFile(
                DiagnosticContext(screen = "app_settings", action = "share_logs", outcome = "succeeded"),
            ),
        )
    }
}
