package com.retro99.home.ui.appsettings

import com.retro99.cloudaccount.domain.model.CloudAccount
import com.retro99.cloudaccount.domain.model.CloudAuthState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CloudRecapsToggleTest {
    private val account = CloudAccount("user-1", "reader@example.com")

    @Test
    fun onlyALiveSessionCountsAsSignedIn() {
        assertEquals(CloudRecapsAccess.SignedIn, CloudAuthState.SignedIn(account).toCloudRecapsAccess())
        assertEquals(CloudRecapsAccess.Restoring, CloudAuthState.RestoringSession.toCloudRecapsAccess())
        listOf(
            CloudAuthState.SignedOut,
            CloudAuthState.AwaitingEmailVerification("reader@example.com"),
            CloudAuthState.ReauthenticationRequired(account),
            CloudAuthState.RefreshUnavailable(account),
        ).forEach { state ->
            assertEquals(CloudRecapsAccess.SignedOut, state.toCloudRecapsAccess(), state.toString())
        }
    }

    @Test
    fun signedInCanTurnOnAndOff() {
        assertEquals(
            CloudRecapsToggle(checked = false, enabled = true, showSignInHint = false),
            cloudRecapsToggle(consent = false, access = CloudRecapsAccess.SignedIn),
        )
        assertEquals(
            CloudRecapsToggle(checked = true, enabled = true, showSignInHint = false),
            cloudRecapsToggle(consent = true, access = CloudRecapsAccess.SignedIn),
        )
    }

    @Test
    fun signedOutIsDisabledWithHint() {
        assertEquals(
            CloudRecapsToggle(checked = false, enabled = false, showSignInHint = true),
            cloudRecapsToggle(consent = false, access = CloudRecapsAccess.SignedOut),
        )
    }

    @Test
    fun signedOutWithConsentCanStillTurnOff() {
        assertEquals(
            CloudRecapsToggle(checked = true, enabled = true, showSignInHint = true),
            cloudRecapsToggle(consent = true, access = CloudRecapsAccess.SignedOut),
        )
    }

    @Test
    fun restoringShowsNoHintAndCannotTurnOn() {
        assertEquals(
            CloudRecapsToggle(checked = false, enabled = false, showSignInHint = false),
            cloudRecapsToggle(consent = false, access = CloudRecapsAccess.Restoring),
        )
    }

    @Test
    fun enablingNeedsSignInButDisablingNeverDoes() {
        assertTrue(canSetCloudRecaps(enabled = true, access = CloudRecapsAccess.SignedIn))
        assertFalse(canSetCloudRecaps(enabled = true, access = CloudRecapsAccess.SignedOut))
        assertFalse(canSetCloudRecaps(enabled = true, access = CloudRecapsAccess.Restoring))
        CloudRecapsAccess.entries.forEach { access ->
            assertTrue(canSetCloudRecaps(enabled = false, access = access), access.name)
        }
    }

    @Test
    fun viewStateDefaultsToRestoringAndOff() {
        val toggle = AppSettingsViewState().cloudRecapsToggle
        assertFalse(toggle.checked)
        assertFalse(toggle.enabled)
        assertFalse(toggle.showSignInHint)
    }
}
