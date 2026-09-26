package com.retro99.login.ui.login

import com.retro99.analytics.api.Analytics
import com.retro99.analytics.api.clearUserIdentity

/** Login must not derive a stable analytics identity from an account name or server URL. */
internal fun clearLoginAnalyticsIdentity(analytics: Analytics) {
    analytics.clearUserIdentity()
}
