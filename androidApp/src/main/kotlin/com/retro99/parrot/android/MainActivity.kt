package com.retro99.parrot.android

import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.Composable
import androidx.compose.ui.tooling.preview.Preview
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.lifecycleScope
import com.retro99.home.ui.deeplink.DeepLinkHandler
import com.retro99.login.data.oauth.StorytellerOAuthCallbackRegistry
import com.retro99.parrot.App
import com.retro99.parrot.CloudOAuthCallbackBridge
import com.retro99.reader.ui.fragment.EpubFragmentFactoryHelper
import com.retro99.reader.ui.playback.NotificationPermissionHandler
import com.retro99.sync.domain.usecase.SyncNowUseCase
import kotlinx.coroutines.launch
import org.koin.android.ext.android.inject

class MainActivity : FragmentActivity() {

    private val notificationPermissionHandler: NotificationPermissionHandler by inject()
    private val deepLinkHandler: DeepLinkHandler by inject()
    private val syncNowUseCase: SyncNowUseCase by inject()
    private val mainHandler = Handler(Looper.getMainLooper())
    private val cancelOAuthRunnable = Runnable {
        if (wasBackgrounded) {
            CloudOAuthCallbackBridge.cancelPending("Google sign-in was cancelled")
            wasBackgrounded = false
        }
    }
    private var wasBackgrounded = false

    override fun onCreate(savedInstanceState: Bundle?) {
        // Set a dummy fragment factory BEFORE super.onCreate() to prevent crashes when
        // Android tries to restore EpubNavigatorFragment after process death.
        // EpubNavigatorFragment requires factory instantiation (no default constructor),
        // so without this, the app would crash with Fragment$InstantiationException.
        // This is the official Readium approach used in their test app.
        supportFragmentManager.fragmentFactory = EpubFragmentFactoryHelper.createDummyFactory()

        enableEdgeToEdge()
        super.onCreate(savedInstanceState)

        // Remove any restored EpubNavigatorFragment BEFORE onResume is called.
        // The dummy fragment throws RestorationNotSupportedException in onResume,
        // so we must remove it immediately after restoration.
        EpubFragmentFactoryHelper.removeRestoredFragment(supportFragmentManager)

        // Register permission handler before setContent
        notificationPermissionHandler.register(this)

        setContent {
            App()
        }

        // Handle deep link if activity was started with one
        handleDeepLinkIntent(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        // Handle deep link when activity is already running (singleTask launch mode)
        handleDeepLinkIntent(intent)
    }

    override fun onPause() {
        super.onPause()
        wasBackgrounded = true
    }

    override fun onResume() {
        super.onResume()
        lifecycleScope.launch {
            syncNowUseCase()
        }
        if (wasBackgrounded) {
            mainHandler.removeCallbacks(cancelOAuthRunnable)
            mainHandler.postDelayed(cancelOAuthRunnable, OAUTH_RETURN_CANCEL_DELAY_MS)
        }
    }

    /**
     * Handles deep link intents from notification clicks or external links.
     * Extracts the URI data and passes it to the DeepLinkHandler for navigation.
     */
    private fun handleDeepLinkIntent(intent: Intent?) {
        val uri = intent?.data?.toString()
        if (uri != null) {
            val handledByStorytellerOAuth = StorytellerOAuthCallbackRegistry.handleRedirect(uri)
            val handledByCloudOAuth = CloudOAuthCallbackBridge.handleRedirect(uri)
            if (handledByCloudOAuth) {
                wasBackgrounded = false
                mainHandler.removeCallbacks(cancelOAuthRunnable)
            }
            if (!handledByStorytellerOAuth && !handledByCloudOAuth) {
                deepLinkHandler.handleDeepLink(uri)
            }
        }
    }

    override fun onDestroy() {
        mainHandler.removeCallbacks(cancelOAuthRunnable)
        super.onDestroy()
        // Only unregister when truly finishing, not during config changes
        // The handler survives config changes and will be re-registered in onCreate
        if (isFinishing) {
            notificationPermissionHandler.unregister()
        }
    }

    private companion object {
        private const val OAUTH_RETURN_CANCEL_DELAY_MS = 500L
    }
}

@Preview
@Composable
fun AppAndroidPreview() {
    App()
}
