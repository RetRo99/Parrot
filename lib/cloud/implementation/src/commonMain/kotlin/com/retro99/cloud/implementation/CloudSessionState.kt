package com.retro99.cloud.implementation

import io.github.jan.supabase.auth.status.SessionStatus

data class CloudSessionState(
    val status: SessionStatus,
    val accountId: String? = null,
) {
    fun isAuthenticatedAs(cloudUserId: String): Boolean {
        val authenticatedSession = status as? SessionStatus.Authenticated ?: return false
        return authenticatedSession.session.user?.id == cloudUserId
    }
}
