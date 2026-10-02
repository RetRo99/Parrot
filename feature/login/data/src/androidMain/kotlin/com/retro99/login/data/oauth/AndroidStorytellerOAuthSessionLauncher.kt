package com.retro99.login.data.oauth

import android.app.Activity
import android.app.Application
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import androidx.browser.customtabs.CustomTabsIntent
import com.retro99.base.result.AppResult

class AndroidStorytellerOAuthSessionLauncher(
    private val context: Context,
) : StorytellerOAuthSessionLauncher {

    override suspend fun requestAppToken(serverUrl: String): AppResult<String> {
        val tokenUrl = Uri.parse(serverUrl.trimEnd('/') + "/api/v2/token/app")
        val application = context.applicationContext as? Application
        val mainHandler = Handler(Looper.getMainLooper())
        var browserWasOpened = false
        var appWasBackgrounded = false
        var attemptId: Long? = null

        val callbacks = object : Application.ActivityLifecycleCallbacks {
            override fun onActivityPaused(activity: Activity) {
                if (browserWasOpened) {
                    appWasBackgrounded = true
                }
            }

            override fun onActivityResumed(activity: Activity) {
                if (!browserWasOpened || !appWasBackgrounded) return

                mainHandler.postDelayed(
                    {
                        // Only cancel our own attempt, never a newer one.
                        StorytellerOAuthCallbackRegistry.cancelPending(
                            "OAuth sign-in was cancelled",
                            attemptId = attemptId ?: return@postDelayed,
                        )
                    },
                    OAUTH_RETURN_CANCEL_DELAY_MS,
                )
            }

            override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) = Unit
            override fun onActivityStarted(activity: Activity) = Unit
            override fun onActivityStopped(activity: Activity) = Unit
            override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit
            override fun onActivityDestroyed(activity: Activity) = Unit
        }

        application?.registerActivityLifecycleCallbacks(callbacks)
        return try {
            StorytellerOAuthCallbackRegistry.awaitToken { id ->
                attemptId = id
                browserWasOpened = true
                // Custom Tabs fall back to a plain ACTION_VIEW without a provider.
                val customTab = CustomTabsIntent.Builder().build().apply {
                    intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                customTab.launchUrl(context, tokenUrl)
            }
        } finally {
            application?.unregisterActivityLifecycleCallbacks(callbacks)
        }
    }

    private companion object {
        private const val OAUTH_RETURN_CANCEL_DELAY_MS = 500L
    }
}
