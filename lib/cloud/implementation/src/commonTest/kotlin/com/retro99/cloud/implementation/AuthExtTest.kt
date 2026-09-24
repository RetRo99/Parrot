package com.retro99.cloud.implementation

import io.github.jan.supabase.annotations.SupabaseInternal
import io.github.jan.supabase.auth.Auth
import io.github.jan.supabase.auth.auth
import io.github.jan.supabase.auth.status.SessionStatus
import io.github.jan.supabase.auth.user.UserInfo
import io.github.jan.supabase.auth.user.UserSession
import io.github.jan.supabase.createSupabaseClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.runCurrent
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.time.Clock
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes

@OptIn(ExperimentalCoroutinesApi::class, SupabaseInternal::class)
class AuthExtTest {
    @Test
    fun `valid session is available while proactive refresh is pending`() = runTest {
        val manager = CloudSessionManager(ProviderFakePreferences())
        val profileManager = manager.forProfile("profile-a")
        val validSession = UserSession(
            accessToken = "old-access-token",
            refreshToken = "old-refresh-token",
            expiresIn = 3600,
            expiresAt = Clock.System.now() + 5.minutes,
            tokenType = "bearer",
            user = UserInfo(aud = "authenticated", id = "account-a"),
        )
        profileManager.saveSession(validSession)
        val requestStarted = CompletableDeferred<Unit>()
        val releaseResponse = CompletableDeferred<Unit>()
        val refreshedSession = validSession.copy(
            accessToken = "new-access-token",
            refreshToken = "new-refresh-token",
            expiresAt = Clock.System.now() + 1.hours,
        )
        val engine = MockEngine {
            requestStarted.complete(Unit)
            releaseResponse.await()
            respond(
                content = Json.encodeToString(UserSession.serializer(), refreshedSession),
                status = HttpStatusCode.OK,
                headers = headersOf(HttpHeaders.ContentType, "application/json"),
            )
        }
        val client = createSupabaseClient("https://example.supabase.co", "test-key") {
            httpEngine = engine
            coroutineDispatcher = StandardTestDispatcher(testScheduler)
            install(Auth) {
                autoLoadFromStorage = false
                autoSetupPlatform = false
                sessionManager = profileManager
            }
        }
        try {
            client.auth.restoreCloudSession(validSession)
            runCurrent()
            requestStarted.await()

            assertIs<SessionStatus.Authenticated>(client.auth.sessionStatus.value)
            assertEquals("old-refresh-token", profileManager.loadSession()?.refreshToken)
        } finally {
            releaseResponse.complete(Unit)
            client.close()
        }
    }

    @Test
    fun `expired stored session stays initializing until refresh succeeds`() = runTest {
        verifyRestoration(HttpStatusCode.OK) { status, manager ->
            assertIs<SessionStatus.Authenticated>(status)
            assertEquals(
                "new-refresh-token",
                manager.forProfile("profile-a").loadSession()?.refreshToken,
            )
            assertNull(manager.reauthenticationAccountId("profile-a"))
        }
    }

    @Test
    fun `server outage retains credentials for retry`() = runTest {
        verifyRestoration(HttpStatusCode.InternalServerError) { status, manager ->
            assertIs<SessionStatus.RefreshFailure>(status)
            assertEquals(
                "old-refresh-token",
                manager.forProfile("profile-a").loadSession()?.refreshToken,
            )
            assertNull(manager.reauthenticationAccountId("profile-a"))
        }
    }

    @Test
    fun `revoked token retains only the reauthentication account`() = runTest {
        verifyRestoration(HttpStatusCode.BadRequest) { status, manager ->
            assertIs<SessionStatus.NotAuthenticated>(status)
            assertNull(manager.forProfile("profile-a").loadSession())
            assertEquals("account-a", manager.reauthenticationAccountId("profile-a"))
        }
    }

    private suspend fun TestScope.verifyRestoration(
        responseStatus: HttpStatusCode,
        verify: suspend (SessionStatus, CloudSessionManager) -> Unit,
    ) {
        val manager = CloudSessionManager(ProviderFakePreferences())
        val profileManager = manager.forProfile("profile-a")
        val expiredSession = UserSession(
            accessToken = "old-access-token",
            refreshToken = "old-refresh-token",
            expiresIn = 3600,
            expiresAt = Clock.System.now() - 1.hours,
            tokenType = "bearer",
            user = UserInfo(aud = "authenticated", id = "account-a"),
        )
        profileManager.saveSession(expiredSession)
        val requestStarted = CompletableDeferred<Unit>()
        val releaseResponse = CompletableDeferred<Unit>()
        val refreshedSession = expiredSession.copy(
            accessToken = "new-access-token",
            refreshToken = "new-refresh-token",
            expiresAt = Clock.System.now() + 1.hours,
        )
        val engine = MockEngine {
            requestStarted.complete(Unit)
            releaseResponse.await()
            respond(
                content = if (responseStatus == HttpStatusCode.OK) {
                    Json.encodeToString(UserSession.serializer(), refreshedSession)
                } else {
                    """{"error":"invalid_grant","error_description":"Refresh failed"}"""
                },
                status = responseStatus,
                headers = headersOf(HttpHeaders.ContentType, "application/json"),
            )
        }
        val client = createSupabaseClient("https://example.supabase.co", "test-key") {
            httpEngine = engine
            coroutineDispatcher = StandardTestDispatcher(testScheduler)
            install(Auth) {
                autoLoadFromStorage = false
                autoSetupPlatform = false
                sessionManager = profileManager
            }
        }
        try {
            client.auth.restoreCloudSession(profileManager.loadSession())
            val restoration = async { client.auth.awaitInitialization() }
            requestStarted.await()

            assertIs<SessionStatus.Initializing>(client.auth.sessionStatus.value)
            assertFalse(restoration.isCompleted)

            releaseResponse.complete(Unit)
            restoration.await()
            verify(client.auth.sessionStatus.value, manager)
        } finally {
            client.close()
        }
    }
}
