package com.retro99.cloudaccount.ui

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CloudAccountFormEligibilityTest {
    @Test
    fun switchingFromEligibleSignInToCreateAccountRequiresTermsConsent() {
        val email = "qa@example.com"
        val password = "synthetic-password"

        assertTrue(
            canSubmitCloudAccountForm(
                mode = CloudAccountMode.SignIn,
                email = email,
                password = password,
                tosAccepted = false,
                isLoading = false,
            ),
        )
        assertFalse(
            canSubmitCloudAccountForm(
                mode = CloudAccountMode.CreateAccount,
                email = email,
                password = password,
                tosAccepted = false,
                isLoading = false,
            ),
        )
    }

    @Test
    fun acceptedTermsEnableCreateAccountAndSwitchingBackToSignInDoesNotRequireConsent() {
        val email = "qa@example.com"
        val password = "synthetic-password"

        assertTrue(
            canSubmitCloudAccountForm(
                mode = CloudAccountMode.CreateAccount,
                email = email,
                password = password,
                tosAccepted = true,
                isLoading = false,
            ),
        )
        assertTrue(
            canSubmitCloudAccountForm(
                mode = CloudAccountMode.SignIn,
                email = email,
                password = password,
                tosAccepted = false,
                isLoading = false,
            ),
        )
    }

    @Test
    fun invalidFieldsAndLoadingAlwaysBlockSubmission() {
        assertFalse(
            canSubmitCloudAccountForm(
                mode = CloudAccountMode.SignIn,
                email = "not-an-email",
                password = "synthetic-password",
                tosAccepted = true,
                isLoading = false,
            ),
        )
        assertFalse(
            canSubmitCloudAccountForm(
                mode = CloudAccountMode.SignIn,
                email = "qa@example.com",
                password = " ",
                tosAccepted = true,
                isLoading = false,
            ),
        )
        assertFalse(
            canSubmitCloudAccountForm(
                mode = CloudAccountMode.SignIn,
                email = "qa@example.com",
                password = "synthetic-password",
                tosAccepted = true,
                isLoading = true,
            ),
        )
    }
}
