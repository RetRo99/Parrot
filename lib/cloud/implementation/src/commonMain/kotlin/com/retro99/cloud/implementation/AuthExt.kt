package com.retro99.cloud.implementation

import io.github.jan.supabase.annotations.SupabaseInternal
import io.github.jan.supabase.auth.Auth
import io.github.jan.supabase.auth.status.SessionStatus
import io.github.jan.supabase.auth.user.UserSession

@OptIn(SupabaseInternal::class)
internal suspend fun Auth.restoreCloudSession(session: UserSession?) {
    setSessionStatus(SessionStatus.Initializing)
    if (session == null) {
        setSessionStatus(SessionStatus.NotAuthenticated())
    } else {
        importSession(session)
    }
}
