package com.retro99.login.data.oauth

import com.github.michaelbull.result.Err
import com.retro99.base.result.AppError
import com.retro99.base.result.AppResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import platform.AuthenticationServices.ASPresentationAnchor
import platform.AuthenticationServices.ASWebAuthenticationPresentationContextProvidingProtocol
import platform.AuthenticationServices.ASWebAuthenticationSession
import platform.AuthenticationServices.ASWebAuthenticationSessionErrorCodeCanceledLogin
import platform.AuthenticationServices.ASWebAuthenticationSessionErrorDomain
import platform.Foundation.NSURL
import platform.UIKit.UIApplication
import platform.UIKit.UISceneActivationStateForegroundActive
import platform.UIKit.UIWindow
import platform.UIKit.UIWindowScene
import platform.darwin.NSObject

private const val CALLBACK_SCHEME = "storyteller"

class IosStorytellerOAuthSessionLauncher : StorytellerOAuthSessionLauncher {

    override suspend fun requestAppToken(serverUrl: String): AppResult<String> {
        val tokenUrl = NSURL.URLWithString(serverUrl.trimEnd('/') + "/api/v2/token/app")
            ?: return Err(AppError.AuthError("Invalid Storyteller URL"))

        // The session hands the callback only to us, not to any app that
        // registers the storyteller:// scheme.
        var session: ASWebAuthenticationSession? = null
        val anchorProvider = PresentationAnchorProvider()
        return try {
            withContext(Dispatchers.Main) {
                StorytellerOAuthCallbackRegistry.awaitToken { attemptId ->
                    session = startSession(tokenUrl, attemptId, anchorProvider)
                }
            }
        } finally {
            withContext(NonCancellable + Dispatchers.Main) {
                // The session holds the provider weakly; keep ours alive until here.
                session?.let { anchorProvider.detachFrom(it) }
                session?.cancel()
            }
        }
    }

    private fun startSession(
        tokenUrl: NSURL,
        attemptId: Long,
        anchorProvider: PresentationAnchorProvider,
    ): ASWebAuthenticationSession {
        val session = ASWebAuthenticationSession(
            uRL = tokenUrl,
            callbackURLScheme = CALLBACK_SCHEME,
        ) { callbackUrl, error ->
            val uri = callbackUrl?.absoluteString
            val cancelledByUser = error?.domain == ASWebAuthenticationSessionErrorDomain &&
                error?.code == ASWebAuthenticationSessionErrorCodeCanceledLogin
            if (uri != null) {
                StorytellerOAuthCallbackRegistry.handleSessionRedirect(uri, attemptId)
            }
            // The sheet is gone either way; end the attempt if nothing did,
            // e.g. a storyteller:// URL other than the settings callback.
            StorytellerOAuthCallbackRegistry.cancelPending(
                if (cancelledByUser) "OAuth sign-in was cancelled" else "OAuth sign-in failed",
                attemptId = attemptId,
            )
        }
        // Shared cookies keep existing SSO sessions working.
        session.prefersEphemeralWebBrowserSession = false
        session.presentationContextProvider = anchorProvider
        if (!session.start()) {
            StorytellerOAuthCallbackRegistry.cancelPending(
                "OAuth sign-in could not be started",
                attemptId = attemptId,
            )
        }
        return session
    }
}

private class PresentationAnchorProvider :
    NSObject(),
    ASWebAuthenticationPresentationContextProvidingProtocol {

    override fun presentationAnchorForWebAuthenticationSession(
        session: ASWebAuthenticationSession,
    ): ASPresentationAnchor {
        // keyWindow is deprecated and can be nil under the scene lifecycle.
        val scenes = UIApplication.sharedApplication.connectedScenes
            .filterIsInstance<UIWindowScene>()
        val scene = scenes.firstOrNull {
            it.activationState == UISceneActivationStateForegroundActive
        } ?: scenes.firstOrNull()
        scene?.keyWindow?.let { return it }
        scene?.windows?.filterIsInstance<UIWindow>()?.firstOrNull()?.let { return it }
        return scene?.let { UIWindow(windowScene = it) } ?: UIWindow()
    }

    fun detachFrom(session: ASWebAuthenticationSession) {
        if (session.presentationContextProvider === this) {
            session.presentationContextProvider = null
        }
    }
}
