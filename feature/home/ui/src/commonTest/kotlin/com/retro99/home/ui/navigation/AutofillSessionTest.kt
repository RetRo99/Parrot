package com.retro99.home.ui.navigation

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AutofillSessionTest {
    @Test
    fun cloudAccountRouteOwnsAutofillSessionThatMustBeCancelledBeforeLeaving() {
        assertTrue(shouldCancelCloudAccountAutofill(HomeDestination.SyncAndBackup))
    }

    @Test
    fun unrelatedRoutesDoNotCancelCloudAccountAutofillSession() {
        assertFalse(shouldCancelCloudAccountAutofill(HomeDestination.BooksList))
        assertFalse(shouldCancelCloudAccountAutofill(null))
    }
}
