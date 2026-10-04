package com.retro99.reader.data.recap

import com.retro99.reader.domain.recap.RecapRequestResult
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.toByteArray
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class RecapCloudSyncTest {
    private val db = FakeSessionRecapDatabase()
    private val settings = FakeRecapSettings()
    private val clock = TestClock()
    private val auth = object : RecapAuthTokens {
        val account = MutableStateFlow<String?>("account-a")
        override fun accountId() = account.value
        override fun observeAccountId() = account
        override fun isSignedIn() = account.value != null
        override fun observeSignedIn() = flowOf(true)
        override suspend fun accessToken() = "test-token"
        override suspend fun refreshedAccessToken() = "test-token"
    }
    private val identity = object : RecapIdentity {
        override suspend fun cloudId(bookId: String) = "cloud-book"
        override suspend fun cloudBooks() = listOf("cloud-book")
        override fun observeProgression(bookId: String) = flowOf(0.5)
    }
    private val requests = mutableListOf<JsonObject>()
    private var handler: suspend (JsonObject) -> CloudRecapPage = { CloudRecapPage(emptyList(), 0, false) }
    private val cloud = CloudRecapEngine(RecapEndpoint("https://fixture.invalid", "fixture-key"), auth,
        HttpClient(MockEngine { req ->
            val body = Json.parseToJsonElement(req.body.toByteArray().decodeToString()).jsonObject
            requests += body
            val text = if (body["operation"]?.jsonPrimitive?.content == "fetch") Json.encodeToString(handler(body)) else "{\"ok\":true}"
            respond(text, HttpStatusCode.OK)
        }), clock)
    private val sync = RecapCloudSync(db, cloud, auth, settings, identity, { "profile" }, clock)
    private fun record(state: String = "completed", change: Long = 1, cloudBook: String? = "cloud-book") = CloudRecapRecord(
        "origin-session", cloudBook, state, "en", clock.nowMs, CloudRecapPosition(totalProgression = 0.5),
        if (state == "completed") "Saved recap." else null, "hy3", changeId = change, expiresAt = clock.nowMs + 180_000_000)

    @Test
    fun crossDeviceFetchUsesCanonicalIdentityNotDifferentLocalUUID() = runTest {
        handler = { body -> if (body["cloudBookId"]?.jsonPrimitive?.content == "cloud-book")
            CloudRecapPage(listOf(record()), 1, false) else CloudRecapPage(emptyList(), 0, false) }
        assertFalse(sync.sync())
        val runner = RecapJobRunner(db, FakeRecapSelector(null), RecapDiagnostics(RecordingAnalytics()), clock, StandardTestDispatcher())
        val repository = SessionRecapDataRepository(db, runner, clock, auth, identity)
        val recap = repository.observeHistory("different-local-uuid").first().single()
        assertEquals("origin-session", recap.sessionId)
        assertEquals("Saved recap.", recap.summary)
        assertNull(db["origin-session"]!!.excerpt)
        assertEquals(1, db.getCloudCursor("account-a", "cloud-book"))
    }

    @Test
    fun unlinkedOriginRecoveryNeedsNoReadingSessionOrBookMatch() = runTest {
        db.put(pendingRow("origin-session", status = "CLOUD_QUEUED", excerpt = null).copy(cloudAccountId = "account-a"))
        handler = { body -> if (body["sessionId"]?.jsonPrimitive?.content == "origin-session")
            CloudRecapPage(listOf(record(cloudBook = null)), 1, false) else CloudRecapPage(emptyList(), 0, false) }
        sync.sync()
        assertEquals("book", db["origin-session"]!!.bookUuid)
        assertEquals("SUCCEEDED", db["origin-session"]!!.status)
        assertNull(db["origin-session"]!!.cloudBookId)
    }

    @Test
    fun resolvedFailuresDoNotStarveLaterUnlinkedJobs() = runTest {
        repeat(20) { index ->
            db.put(pendingRow("failed-$index", status = "FAILED_PERMANENT", excerpt = null)
                .copy(cloudAccountId = "account-a", cloudChangeId = 1))
        }
        db.put(pendingRow("origin-session", status = "CLOUD_QUEUED", excerpt = null)
            .copy(cloudAccountId = "account-a"))
        handler = { body -> if (body["sessionId"]?.jsonPrimitive?.content == "origin-session")
            CloudRecapPage(listOf(record(cloudBook = null)), 1, false) else CloudRecapPage(emptyList(), 0, false) }

        sync.sync()

        assertEquals("SUCCEEDED", db["origin-session"]!!.status)
        assertFalse(requests.any { it["sessionId"]?.jsonPrimitive?.content?.startsWith("failed-") == true })
    }

    @Test
    fun emptyLookupsRotateInsteadOfStarvingLaterUnlinkedJobs() = runTest {
        repeat(20) { index ->
            db.put(pendingRow("missing-$index", status = "FAILED_PERMANENT", excerpt = null)
                .copy(cloudAccountId = "account-a"))
        }
        db.put(pendingRow("origin-session", status = "CLOUD_QUEUED", excerpt = null)
            .copy(cloudAccountId = "account-a"))
        handler = { body -> if (body["sessionId"]?.jsonPrimitive?.content == "origin-session")
            CloudRecapPage(listOf(record(cloudBook = null)), 1, false) else CloudRecapPage(emptyList(), 0, false) }

        assertTrue(sync.sync())
        sync.sync()

        assertEquals("SUCCEEDED", db["origin-session"]!!.status)
    }

    @Test
    fun locallyExhaustedAttemptCanStillRecoverAnAcceptedJob() = runTest {
        db.put(pendingRow("origin-session", status = "FAILED_PERMANENT")
            .copy(cloudAccountId = "account-a", lastError = "MAX_ATTEMPTS"))
        handler = { body -> if (body["sessionId"]?.jsonPrimitive?.content == "origin-session")
            CloudRecapPage(listOf(record(cloudBook = null)), 1, false) else CloudRecapPage(emptyList(), 0, false) }

        sync.sync()

        assertEquals("SUCCEEDED", db["origin-session"]!!.status)
        assertNull(db["origin-session"]!!.excerpt)
    }

    @Test
    fun paginationAppliesEveryPageBeforeAdvancingCursor() = runTest {
        handler = { body -> if (body["cloudBookId"]?.jsonPrimitive?.content == "cloud-book") {
            val cursor = body["cursor"]!!.jsonPrimitive.content.toLong()
            if (cursor == 0L) CloudRecapPage(listOf(record().copy(sessionId = "first")), 1, true)
            else CloudRecapPage(listOf(record(change = 2).copy(sessionId = "second")), 2, false)
        } else CloudRecapPage(emptyList(), 0, false) }
        assertTrue(sync.sync())
        assertEquals(1L, db.getCloudCursor("account-a", "cloud-book"))
        sync.sync()
        assertEquals(2, db.getCloudRows("account-a").size)
        assertEquals(2L, db.getCloudCursor("account-a", "cloud-book"))
    }

    @Test
    fun switchWhileFetchingCannotCacheAnotherAccountsResult() = runTest {
        handler = { auth.account.value = "account-b"; CloudRecapPage(listOf(record()), 1, false) }
        assertFailsWith<IllegalStateException> { sync.sync() }
        assertTrue(db.rows.value.isEmpty())
    }

    @Test
    fun offlineDeletionIsNotResurrectedByAConcurrentFetch() = runTest {
        handler = { body ->
            if (body["cloudBookId"]?.jsonPrimitive?.content == "cloud-book") {
                db.queueCloudDeletion("account-a", "origin-session")
                CloudRecapPage(listOf(record()), 1, false)
            } else CloudRecapPage(emptyList(), 0, false)
        }
        sync.sync()
        assertNull(db["origin-session"])
        assertEquals(listOf("origin-session"), db.getCloudDeletions("account-a"))
    }

    @Test
    fun withdrawalFlushesEvenWhenGenerationIsDisabled() = runTest {
        settings.enabled.value = false
        db.queueCloudWithdrawal("account-a", clock.nowMs)
        sync.sync()
        assertFalse(db.hasCloudWithdrawal("account-a"))
        assertEquals("consent", requests.single()["operation"]!!.jsonPrimitive.content)
    }

    @Test
    fun cloudTombstoneDeletesLocalCache() = runTest {
        db.put(pendingRow("origin-session", status = "SUCCEEDED", excerpt = null).copy(cloudAccountId = "account-a"))
        handler = { body -> if (body["cloudBookId"]?.jsonPrimitive?.content == "cloud-book")
            CloudRecapPage(listOf(record("deleted", 2)), 2, false) else CloudRecapPage(emptyList(), 0, false) }
        sync.sync()
        assertNull(db["origin-session"])
    }

    @Test
    fun buttonOnCompletedRecapOnlyReturnsExistingState() = runTest {
        db.put(pendingRow("origin-session", status = "SUCCEEDED", excerpt = null).copy(cloudAccountId = "account-a"))
        val runner = RecapJobRunner(db, FakeRecapSelector(null), RecapDiagnostics(RecordingAnalytics()), clock, StandardTestDispatcher())
        val repo = SessionRecapDataRepository(db, runner, clock, auth, identity)
        assertEquals(RecapRequestResult.ALREADY_GENERATED, repo.request("origin-session"))
        assertEquals(RecapRequestResult.TEXT_UNAVAILABLE, repo.request("historical-no-excerpt"))
        auth.account.value = "account-b"
        assertNull(repo.observeRecap("origin-session").first())
        assertEquals(RecapRequestResult.ACCOUNT_REQUIRED, repo.request("origin-session"))
        assertTrue(requests.isEmpty())
    }
}
