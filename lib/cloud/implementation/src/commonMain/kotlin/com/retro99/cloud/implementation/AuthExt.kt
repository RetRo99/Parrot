package com.retro99.cloud.implementation

import io.github.jan.supabase.annotations.SupabaseInternal
import io.github.jan.supabase.auth.Auth
import io.github.jan.supabase.auth.status.SessionStatus
import io.github.jan.supabase.auth.user.UserSession
import kotlin.time.Clock

@OptIn(SupabaseInternal::class)
internal suspend fun Auth.restoreCloudSession(session: UserSession?) {
    setSessionStatus(SessionStatus.Initializing)
    if (session == null) {
        setSessionStatus(SessionStatus.NotAuthenticated())
    } else if (session.expiresAt > Clock.System.now()) {
        // A still-valid access token can be used while the SDK refreshes it in
        // the background. Publish it first so an unavailable refresh endpoint
        // cannot leave account restoration stuck in Initializing indefinitely.
        importSession(session, autoRefresh = false)
        importSession(session)
    } else {
        importSession(session)
    }
}
