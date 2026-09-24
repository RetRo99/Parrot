package com.retro99.cloudaccount.domain.model

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CloudProfileLinkTest {
    @Test
    fun activeRequiresSignedInAccountMatchingTheProfileLink() {
        val link = CloudProfileLink(
            localProfileId = "profile-a",
            cloudUserId = "cloud-user-a",
            syncEnabled = false,
        )

        assertTrue(link.isActiveFor(CloudAuthState.SignedIn(CloudAccount("cloud-user-a", null))))
        assertFalse(link.isActiveFor(CloudAuthState.SignedIn(CloudAccount("cloud-user-b", null))))
        assertFalse(link.isActiveFor(CloudAuthState.SignedOut))
        val missingLink: CloudProfileLink? = null
        assertFalse(missingLink.isActiveFor(CloudAuthState.SignedIn(CloudAccount("cloud-user-a", null))))
    }
}
