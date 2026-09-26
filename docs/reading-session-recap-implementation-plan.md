# Reading Session Recap — Implementation Plan

**Status:** Design approved. Backend spike artifacts implemented and verified as
far as is possible without an LLM API key; the rest is not yet implemented.
**Author:** —
**Last updated:** 2026-09-24
**Scope:** Android + iOS (shared Kotlin), with the AI tier backed by a Supabase Edge Function

> **Implemented so far** (the spike — see §18):
> `supabase/functions/generate-recap/index.ts`, the `[functions.generate-recap]`
> entry in `supabase/config.toml`, and the `supabase/spike/recap_spike.py`
> prompt/quality harness. HTTP wiring verified against both OpenAI and Gemini
> endpoints. **Not yet verified:** actual model output quality — needs an API key.

---

## 1. Summary

When a reader reopens a book, show a short "welcome back" recap of the **previous
reading session**: how long they read, how much ground they covered, which chapters
they were in, the exact sentence they stopped at, and — optionally — a 2–3 sentence
prose summary of what happened in that part of the book.

The feature is built in two tiers so it is useful on every device and on the first
run, with the AI as an optional upgrade:

| Tier | Content | Requires | Latency | Cost |
|---|---|---|---|---|
| **Tier 0 — Local recap** | Session duration, pages read, progression range, chapter titles, the sentence the reader stopped at | Nothing (all local) | Instant | $0 |
| **Tier 1 — AI recap** | 2–3 sentence prose summary of the chapters read in the last session | Network + Parrot Cloud sign-in | 1–3 s | ~$0.001/recap |

**Presentation:** a snackbar appears a couple of seconds after the book opens,
asking *"Want a recap of your last session?"* with a **Show recap** action. Tapping
it opens a bottom sheet with the full recap. The snackbar auto-dismisses and is
never shown twice for the same session.

---

## 2. Decisions and rationale

These were settled before writing this document.

### 2.1 AI tier is cloud-first, not on-device

**Decision:** implement the AI recap as a Supabase Edge Function calling a hosted
LLM API. Do **not** bundle an on-device model in v1.

**Rationale:** a recap is ~3,000 tokens in, ~100 tokens out. At current API prices
that is **$0.0002–$0.001 per recap**. Bundling a model to avoid that costs a
200–320 MB download, ~550 MB RAM at runtime, a model download/lifecycle manager,
and produces noticeably worse prose. The economics only favour on-device at very
large scale, and its genuine advantages are **privacy** and **offline**.

The design keeps a `RecapEngine` seam (§5.3) so an on-device implementation can be
added later without touching the feature. See §13.

### 2.2 Presentation is a snackbar, not a modal

**Decision:** prompt via snackbar 2–3 s after the book opens; recap lives in a
bottom sheet.

**Rationale:** a modal on every book open is hostile. A snackbar is dismissible,
unobtrusive, and matches the existing reader interaction patterns
(`BookmarkAddedSnackbar`, `NoAudioSnackbar`, …).

### 2.3 Recap data is a separate table, not new columns on `reading_session`

**Decision:** a new `book_recap` table with one row per book.

**Rationale:** `reading_session` is the statistics ledger — append-only, aggregated
by `feature/statistics`. The recap is a **latest-session snapshot** with prose
attached and different lifecycle (upsert, and the AI summary is invalidated when a
new session overwrites the row). Mixing the two would require threading new fields
through `StatisticsRepository` → `StatisticsLocalSource` → DAO → entity → mapper,
and would couple `feature/reader/domain` to `feature/statistics/domain`
(it currently does not depend on it).

A dedicated table gives one upsert and one read, and keeps the statistics pipeline
untouched.

### 2.4 Text for the AI is captured at reader close

**Decision:** capture the chapter text excerpt in `ReaderViewModel.close()` via the
existing `BookController.getChapterSentences()`.

**Rationale:** that API already exists in `commonMain`, works on Android and iOS
(`AndroidBookController` runs `ChapterSentenceExtractor`'s JS in the Readium
WebView; `IosBookController` runs the same script through
`ReadiumEpubReaderBridge.evaluateJavaScript`), and is already used by the
read-aloud/TTS path. No new WebView plumbing is required.

---

## 3. Current state — what already exists

Nothing in the repo is named "recap". The feature is assembled from existing
pieces.

### 3.1 Reading session tracking

`reading_session` table —
`lib/database/implementation/src/commonMain/sqldelight/com/retro99/database/implementation/ReadingSession.sq`

```sql
CREATE TABLE IF NOT EXISTS reading_session (
    id INTEGER NOT NULL PRIMARY KEY AUTOINCREMENT,
    book_uuid TEXT NOT NULL,
    book_title TEXT NOT NULL,
    book_type TEXT NOT NULL,
    start_time INTEGER NOT NULL,
    end_time INTEGER NOT NULL,
    duration_ms INTEGER NOT NULL,
    pages_read INTEGER,
    start_progression REAL,
    end_progression REAL,
    reading_speed_wpm INTEGER
);
```

Sessions are written from `ReaderViewModel.close()`
(`feature/reader/ui/src/commonMain/kotlin/com/retro99/reader/ui/reader/ReaderViewModel.kt`,
~line 1426) via `SaveReadingSessionUseCase`
(`feature/statistics/domain/.../usecase/SaveReadingSessionUseCase.kt`).
**Sessions are local-only** — they are never synced.

Note that `start_progression` is currently **not** populated on save (only
`endProgression` is passed). The recap needs both ends of the range; see §9.4.

### 3.2 Reading position

`PositionDomainModel`
(`feature/reader/domain/src/commonMain/kotlin/com/retro99/reader/domain/model/PositionDomainModel.kt`)
— a flattened Readium locator: `href`, `type`, `title`, `target`, `cssSelector`,
`chapterIndex`, `progression`, `totalProgression`, `audioTimestampMs`,
`totalDurationMs`. Persisted in `Position.sq` (`position` / `remote_position`).

### 3.3 Chapter text extraction

`ChapterSentenceExtractor`
(`feature/reader/ui/src/commonMain/kotlin/com/retro99/reader/ui/navigator/ChapterSentenceExtractor.kt`)
injects JavaScript into the Readium WebView to wrap every sentence in a
`<span class="parrot-sentence">` and return `{id, t}` pairs (URI-encoded).

Exposed on the common navigator interface
(`feature/reader/ui/src/commonMain/kotlin/com/retro99/reader/ui/navigator/BookController.kt`):

```kotlin
suspend fun getChapterSentences(): List<TtsSentence> = emptyList()   // line 170
suspend fun getVisibleSentenceId(): String?                          // line 157
```

`TtsSentence(index, elementId, text)`.

**This is the recap input source.** `getChapterSentences()` gives the chapter text;
`getVisibleSentenceId()` tells us which sentence was on screen at close.

### 3.4 Table of contents

`ReaderViewState.tableOfContents: List<TocItemUiModel>` —
`feature/reader/ui/src/commonMain/kotlin/com/retro99/reader/ui/model/TocItemUiModel.kt`

```kotlin
data class TocItemUiModel(
    val href: String,
    val title: String,
    val level: Int = 0,
    val children: List<TocItemUiModel> = emptyList(),
)
```

Used to turn `href`/`chapterIndex` into human-readable chapter titles.

### 3.5 Supabase

`SupabaseClientProvider`
(`lib/cloud/implementation/src/commonMain/kotlin/com/retro99/cloud/implementation/SupabaseClientProvider.kt`)
— multi-profile Supabase client with `Postgrest`, `Storage`, `Functions`, `Auth`
(PKCE) installed. Existing Edge Function invocation pattern in
`feature/cloud-account/data/.../SupabaseCloudAccountDataRepository.kt:132`:

```kotlin
client.functions("delete-cloud-account")
```

Function registration in `supabase/config.toml`:

```toml
[functions.delete-cloud-account]
verify_jwt = true
```

supabase-kt **3.4.1** (`gradle/libs.versions.toml`).

### 3.6 On-device ML precedent (for the future on-device tier)

`feature/reader/ui/src/androidMain/.../tts/TtsModelManager.kt` downloads Kokoro /
Supertonic `.int8.onnx` models from GitHub releases, extracts `tar.bz2`, verifies
completeness, caches and deletes them. This is the lifecycle to copy if an
on-device LLM is added later (§13).

### 3.7 Stack facts

| Concern | Value |
|---|---|
| UI | Compose Multiplatform, Navigation 3 |
| DI | Koin annotations (`@Single`, `@Factory`, `@Provided`, koin-compiler-plugin) |
| Results | kotlin-result — `AppResult<T>`, `CompletableResult` in `base` |
| Persistence | SQLDelight 2.0.2, `AppDatabase`, packageName `com.retro99.database.implementation` |
| Reader | Readium Kotlin Toolkit 3.2.0 (Android), Readium Swift (iOS) |
| Targets | Android (minSdk 29, compileSdk 36), iOS (iosArm64, iosSimulatorArm64) |
| Strings | Compose Resources — `translations/src/commonMain/composeResources/values/strings.xml` |

> **⚠️ Schema version caveat.** `lib/database/implementation/build.gradle.kts`
> currently declares `version = 32` on an **uncommitted** branch of work (the
> "unified library" feature, which added migrations `26.sqm`–`31.sqm`). `HEAD`
> still says `version = 25`. The recap migration number depends on which lands
> first — see §9.1.

---

## 4. Architecture and data flow

```
┌─────────────────────────────────────────────────────────────────┐
│ feature/reader/ui                                               │
│                                                                 │
│  ReaderViewModel ──close()──► SaveSessionRecapUseCase ──┐       │
│        │                                                │       │
│        └──open()──► GetLastSessionRecapUseCase ◄────────┤       │
│                         │                               │       │
│                         ▼                               ▼       │
│  ReaderScreen        SessionRecap               ┌─────────────┐ │
│   ├─ RecapPromptSnackbar                        │ book_recap  │ │
│   └─ RecapSheet                                 │  (SQLDelight│ │
│        │                                        │   local)    │ │
│        └─ GenerateSessionRecapUseCase ──────────┴─────────────┘ │
│                        │                                        │
└────────────────────────┼────────────────────────────────────────┘
                         │ RecapEngine (interface, domain)
                         ▼
        ┌────────────────────────────────────────┐
        │ feature/reader/data/recap              │
        │   CloudRecapEngine                     │
        │   (SupabaseClientProvider → Functions) │
        └────────────────┬───────────────────────┘
                         │ HTTPS POST (JWT)
                         ▼
        ┌────────────────────────────────────────┐
        │ supabase/functions/generate-recap      │
        │   validate → truncate → LLM API → JSON │
        └────────────────┬───────────────────────┘
                         │
                         ▼
                   Hosted LLM API
            (Gemini / OpenAI-compatible)
```

### 4.1 Write path (reader closes)

```
ReaderViewModel.close()
  ├─ SaveReadingSessionUseCase(...)              // existing, unchanged
  ├─ bookController.getChapterSentences()        // NEW — chapter text
  ├─ bookController.getVisibleSentenceId()       // NEW — stopping point
  └─ SaveSessionRecapUseCase(...)                // NEW — upsert book_recap
```

### 4.2 Read path (reader opens)

```
ReaderViewModel.openPublication() → success
  └─ GetLastSessionRecapUseCase(bookUuid)        // NEW
        │
        ├─ null  ──► no recap, nothing shown
        └─ recap ──► updateState { recapPrompt = recap.toUiModel() }
                       │
                       ▼ (2.5 s)
                  RecapPromptSnackbar  "Want a recap of your last session?"
                       │ tap "Show recap"
                       ▼
                  updateState { isRecapVisible = true }
                       │
                       ├─ Tier 0 rendered immediately from the row
                       └─ GenerateSessionRecapUseCase(recap)   // async
                              │
                              ├─ ok  ──► BookRecapDatabase.saveAiSummary()
                              │            updateState { recap.aiSummary = it }
                              └─ err ──► sheet shows Tier 0 only + retry
```

---

## 5. Data model

### 5.1 SQL schema

**New file:** `lib/database/implementation/src/commonMain/sqldelight/com/retro99/database/implementation/BookRecap.sq`

```sql
-- ============================================
-- BOOK_RECAP TABLE
-- Snapshot of the most recent reading session per book.
-- Used to build the "welcome back" recap shown when a book is reopened.
-- One row per book, overwritten on every qualifying reader close.
-- ============================================

CREATE TABLE IF NOT EXISTS book_recap (
    book_uuid TEXT NOT NULL PRIMARY KEY,
    book_title TEXT NOT NULL,
    session_start INTEGER NOT NULL,
    session_end INTEGER NOT NULL,
    duration_ms INTEGER NOT NULL,
    pages_read INTEGER,
    start_progression REAL,
    end_progression REAL,
    chapter_titles TEXT,      -- JSON array, e.g. ["Chapter 4","Chapter 5"]
    chapter_title TEXT,       -- title of the chapter the reader stopped in
    last_sentence TEXT,
    excerpt TEXT,
    ai_summary TEXT,
    updated_at INTEGER NOT NULL
);

upsertBookRecap:
INSERT OR REPLACE INTO book_recap(
    book_uuid, book_title, session_start, session_end, duration_ms,
    pages_read, start_progression, end_progression,
    chapter_titles, chapter_title, last_sentence, excerpt, ai_summary, updated_at
)
VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?);

getBookRecap:
SELECT * FROM book_recap WHERE book_uuid = ?;

saveAiSummary:
UPDATE book_recap
SET ai_summary = ?, updated_at = ?
WHERE book_uuid = ?;

deleteBookRecap:
DELETE FROM book_recap WHERE book_uuid = ?;

deleteAllBookRecaps:
DELETE FROM book_recap;
```

**Migration:** `32.sqm` (number depends on §9.1) — identical DDL, without
`IF NOT EXISTS`, matching the style of `31.sqm`.

**Bump** `version` in `lib/database/implementation/build.gradle.kts`.

> Generated SQLDelight types will be `Book_recap` (row) and `Book_recapQueries`.
> The DAO should map to `BookRecapSqlDelightEntity` and never leak `Book_recap`
> past the DAO, matching `ReadingSessionSqlDelightDao`.

**Storage sizing.** `excerpt` is capped at 8,000 chars (§5.4). One row per book;
1,000 books ≈ 8 MB worst case. `deleteAllBookRecaps` is wired into the
`DataClearable` list so it is cleared with the rest of user data.

### 5.2 Entity and DAO contracts

**New:** `lib/database/api/src/commonMain/kotlin/com/retro99/database/api/books/BookRecapEntity.kt`

```kotlin
package com.retro99.database.api.books

interface BookRecapEntity {
    val bookUuid: String
    val bookTitle: String
    val sessionStart: Long
    val sessionEnd: Long
    val durationMs: Long
    val pagesRead: Int?
    val startProgression: Double?
    val endProgression: Double?
    val chapterTitles: List<String>
    val chapterTitle: String?
    val lastSentence: String?
    val excerpt: String?
    val aiSummary: String?
    val updatedAt: Long
}
```

> `chapterTitles` is stored as a JSON array in a `TEXT` column and decoded in
> `BookRecapSqlDelightEntity`'s mapper (kotlinx-serialization is already a
> dependency of `lib/database/implementation`). Keeping `List<String>` on the
> interface means no SQL shape leaks into the domain.

**New:** `lib/database/api/src/commonMain/kotlin/com/retro99/database/api/books/BookRecapDatabase.kt`

```kotlin
package com.retro99.database.api.books

import com.retro99.database.api.DataClearable

interface BookRecapDatabase : DataClearable {
    suspend fun upsertBookRecap(recap: BookRecapEntity)
    suspend fun getBookRecap(bookUuid: String): BookRecapEntity?
    suspend fun saveAiSummary(bookUuid: String, aiSummary: String, updatedAt: Long)
    suspend fun deleteBookRecap(bookUuid: String)
    override suspend fun clearAllData()
}
```

**New implementation files** in
`lib/database/implementation/src/commonMain/kotlin/com/retro99/database/implementation/dao/books/`:

| File | Notes |
|---|---|
| `BookRecapSqlDelightEntity.kt` | `internal data class`, `: BookRecapEntity` |
| `BookRecapSqlDelightDao.kt` | Uses `databaseManager.getDatabase().bookRecapQueries`, `Dispatchers.IO`, mirrors `ReadingSessionSqlDelightDao` |
| `BookRecapDatabaseImpl.kt` | Thin delegation, `internal class`, mirrors `ReadingSessionDatabaseImpl` |

**DI** — `lib/database/implementation/src/commonMain/kotlin/com/retro99/database/implementation/di/DatabaseModule.kt`,
added next to the reading-session providers and included in `provideDataClearables`:

```kotlin
@Single
internal fun provideBookRecapSqlDelightDao(databaseManager: DatabaseManager): BookRecapSqlDelightDao =
    BookRecapSqlDelightDao(databaseManager)

@Single
internal fun provideBookRecapDatabase(dao: BookRecapSqlDelightDao): BookRecapDatabase =
    BookRecapDatabaseImpl(dao)
```

and add `bookRecapDatabase` to the `List<DataClearable>` returned by
`provideDataClearables`.

### 5.3 Domain model and the RecapEngine seam

**New package:** `feature/reader/domain/src/commonMain/kotlin/com/retro99/reader/domain/recap/`

`feature/reader/domain` already depends on `base`, `lib.database.api`,
`lib.server.api`, `feature.books.domain`, coroutines and Koin — everything needed.
It does **not** need `feature.statistics.domain`, which is the point of §2.3.

```kotlin
// SessionRecap.kt
data class SessionRecap(
    val bookUuid: String,
    val bookTitle: String,
    val sessionStart: Long,
    val sessionEnd: Long,
    val durationMs: Long,
    val pagesRead: Int?,
    val startProgression: Double?,
    val endProgression: Double?,
    val chapterTitles: List<String>,
    val currentChapterTitle: String?,
    val lastSentence: String?,
    val excerpt: String?,
    val aiSummary: String?,
)
```

```kotlin
// RecapRequest.kt
data class RecapRequest(
    val bookTitle: String,
    val chapterTitles: List<String>,
    val excerpt: String,
    val lastSentence: String?,
)
```

```kotlin
// RecapEngine.kt
/**
 * Seam for recap generation. The v1 implementation is [CloudRecapEngine];
 * an on-device implementation can be substituted without touching the feature.
 */
interface RecapEngine {
    suspend fun generateRecap(request: RecapRequest): AppResult<String>

    /** False when the engine cannot run (no network, not signed in, disabled). */
    suspend fun isAvailable(): Boolean
}
```

### 5.4 Use cases

All in `feature/reader/domain/.../usecase/`, following the existing `@Factory`
+ `@Provided` pattern.

#### `SaveSessionRecapUseCase`

```kotlin
@Factory
class SaveSessionRecapUseCase(
    @Provided private val bookRecapDatabase: BookRecapDatabase,
) {
    suspend operator fun invoke(
        bookUuid: String,
        bookTitle: String,
        sessionStart: Long,
        sessionEnd: Long,
        durationMs: Long,
        pagesRead: Int?,
        startProgression: Double?,
        endProgression: Double?,
        chapterTitles: List<String>,
        currentChapterTitle: String?,
        lastSentence: String?,
        excerpt: String?,
    ): CompletableResult
}
```

Responsibilities:
- Guard on minimum session length (§9.2) — short sessions must **not** clobber a
  better recap from a longer earlier session.
- Truncate `excerpt` to `MAX_EXCERPT_CHARS = 8_000`, trimming at the last sentence
  boundary so the LLM never receives a half-sentence.
- Set `aiSummary = null` — a new session invalidates any previously generated
  summary.

#### `GetLastSessionRecapUseCase`

```kotlin
@Factory
class GetLastSessionRecapUseCase(
    @Provided private val bookRecapDatabase: BookRecapDatabase,
) {
    suspend operator fun invoke(bookUuid: String): SessionRecap?
}
```

Returns `null` when no row exists (first read of the book) **or** when the row is
too stale to be worth prompting about (§9.3).

#### `GenerateSessionRecapUseCase`

```kotlin
@Factory
class GenerateSessionRecapUseCase(
    @Provided private val recapEngine: RecapEngine,
    @Provided private val bookRecapDatabase: BookRecapDatabase,
) {
    suspend operator fun invoke(recap: SessionRecap): AppResult<String>
}
```

Responsibilities:
- Return the cached `recap.aiSummary` immediately if present.
- Return `Err` if `recap.excerpt.isNullOrBlank()` — nothing to summarise.
- Call `RecapEngine.generateRecap`, then persist via
  `bookRecapDatabase.saveAiSummary(bookUuid, summary, nowMillis())` on success.
- Never throw; every failure is an `AppResult` failure so the UI degrades to Tier 0.

---

## 6. Data layer — `CloudRecapEngine`

**New:** `feature/reader/data/src/commonMain/kotlin/com/retro99/reader/data/recap/CloudRecapEngine.kt`

```kotlin
@Single(binds = [RecapEngine::class])
internal class CloudRecapEngine(
    @Provided private val clientProvider: SupabaseClientProvider,
    @Provided private val analytics: Analytics,
) : RecapEngine {

    private val json = Json { ignoreUnknownKeys = true }

    override suspend fun isAvailable(): Boolean {
        if (!clientProvider.isConfigured) return false
        return clientProvider.currentSessionState().status is SessionStatus.Authenticated
    }

    override suspend fun generateRecap(request: RecapRequest): AppResult<String> {
        if (!isAvailable()) return Err(AppError.Unauthorized())   // tune to real error types
        return runCatching {
            val response = clientProvider.client.functions(     // resolved per call, see below
                function = FUNCTION_NAME,
                region = FUNCTION_REGION,
                body = RecapRequestPayload(request),
                headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()),
            )
            val summary = json.decodeFromString<RecapResponsePayload>(response.bodyAsText()).summary
            if (summary.isBlank()) error("Empty recap returned") else summary
        }.toAppResult()
            .onFailure { analytics.logException(it, "CloudRecapEngine failed") }
    }

    private companion object {
        const val FUNCTION_NAME = "generate-recap"
        val FUNCTION_REGION = FunctionRegion.EU_CENTRAL_1   // match the project's region
    }
}

@Serializable
private data class RecapRequestPayload(
    val bookTitle: String,
    val chapterTitles: List<String>,
    val excerpt: String,
    val lastSentence: String? = null,
)

@Serializable
private data class RecapResponsePayload(val summary: String = "")
```

### 6.1 Verified supabase-kt 3.4.1 API

Signatures taken from the `functions-kt-android-3.4.1-sources.jar` in the Gradle
cache, not from memory.

```kotlin
// io.github.jan.supabase.functions — SupabaseClient extension
val SupabaseClient.functions: Functions

// The four overloads on Functions:
suspend inline operator fun invoke(
    function: String,
    region: FunctionRegion = config.defaultRegion,
    crossinline builder: HttpRequestBuilder.() -> Unit,
): HttpResponse

suspend inline operator fun <reified T : Any> invoke(
    function: String,
    body: T,
    region: FunctionRegion = config.defaultRegion,
    headers: Headers = Headers.Empty,
): HttpResponse                                            // ← use this one

suspend inline operator fun invoke(
    function: String,
    region: FunctionRegion = config.defaultRegion,
    headers: Headers = Headers.Empty,
): HttpResponse                                            // ← what deleteAccount uses

fun buildEdgeFunction(function, region, headers): EdgeFunction   // reusable
```

**Five facts that will otherwise cost you debugging time:**

1. **It returns a raw Ktor `HttpResponse`.** Not a decoded object. Read it with
   `io.ktor.client.statement.bodyAsText()` and decode yourself.
2. **You must set `Content-Type` manually.** The KDoc states it: *"if you want to
   serialize [body] to json, you need to add the `ContentType` header yourself."*
   The body overload runs `setBody(serializer.encode(body))` — a bare string.
   Without the header, `req.json()` on the Deno side receives garbage.
3. **Errors are thrown, never returned.** Non-2xx raises
   `io.github.jan.supabase.exceptions.RestException` subclasses —
   `UnauthorizedRestException`, `NotFoundRestException`,
   `BadRequestRestException`, `UnknownRestException` — plus
   `HttpRequestTimeoutException` and `HttpRequestException`. Wrap in
   `runCatching`/try-catch; never inspect a status code.
4. **Auth is automatic.** The plugin uses `authenticatedSupabaseApi`, which
   injects the active session's JWT. No token handling in client code.
5. **`verify_jwt = true` is not an auth check.** See §7.2.

> **Correction to an earlier draft of this document.** It claimed
> `client.functions("delete-cloud-account")` only *resolves* a `Function`
> reference and does not execute. That was wrong — `SupabaseClient.functions`
> returns the `Functions` plugin and `("name")` hits its `operator fun invoke`,
> which **does** POST. The existing `SupabaseCloudAccountDataRepository.deleteAccount()`
> is already invoking its Edge Function correctly, and is a working reference.

### 6.2 Two project-specific gotchas

**Do not cache the `SupabaseClient`.** `SupabaseClientProvider.client` throws if
the client's profile does not match the active profile — it is a multi-profile
guard. Resolve `clientProvider.client` *at call time* inside `generateRecap()`,
never in the `CloudRecapEngine` constructor.

**Pin the region.** The Supabase project lives in one region. Set it once rather
than paying `ANY` routing on every call — either per call as above, or globally:

```kotlin
// SupabaseClientProvider.createClient()
install(Functions) {
    defaultRegion = FunctionRegion.EU_CENTRAL_1
}
```

This also applies to `delete-cloud-account`, which is desirable.

**Gradle changes** — `feature/reader/data/build.gradle.kts`, `commonMain`:

```kotlin
implementation(libs.supabase.functions)
implementation(projects.lib.cloud.implementation)
```

**DI** — `feature/reader/data/src/commonMain/kotlin/com/retro99/reader/data/di/ReaderDataModule.kt`.
`@Single(binds = [RecapEngine::class])` on the class is enough given
`@ComponentScan("com.retro99.reader.data")` — verify the scan covers it.

> **Why `feature/reader/data`?** It is the only module that already sits on both
> the reader domain and the network stack. Putting `CloudRecapEngine` in
> `lib/server-parrot-cloud` would wrongly couple recap to the server abstraction
> (recap must work for local files and Storyteller/Audiobookshelf sources too).

---

## 7. Supabase backend

### 7.1 Edge Function

**New:** `supabase/functions/generate-recap/index.ts`

```ts
import { createClient } from 'npm:@supabase/supabase-js@2'

const MAX_EXCERPT_CHARS = 8_000
const MAX_OUTPUT_TOKENS = 160
const MODEL = Deno.env.get('RECAP_MODEL') ?? 'gemini-3.1-flash-lite'
const API_KEY = Deno.env.get('RECAP_API_KEY')!

const corsHeaders = {
  'Access-Control-Allow-Origin': '*',
  'Access-Control-Allow-Headers': 'authorization, x-client-info, apikey, content-type',
}

function json(body: unknown, status = 200): Response {
  return new Response(JSON.stringify(body), {
    status,
    headers: { ...corsHeaders, 'Content-Type': 'application/json' },
  })
}

Deno.serve(async (req) => {
  if (req.method === 'OPTIONS') return new Response('ok', { headers: corsHeaders })

  try {
    const authHeader = req.headers.get('Authorization')
    if (!authHeader) return json({ error: 'Missing Authorization' }, 401)

    // verify_jwt = true already rejects anonymous calls; this gives us the
    // subject for abuse accounting and rate limiting.
    const admin = createClient(
      Deno.env.get('SUPABASE_URL')!,
      Deno.env.get('SUPABASE_SERVICE_ROLE_KEY')!,
    )
    const { data: userData, error: userError } = await admin.auth.getUser(
      authHeader.replace('Bearer ', ''),
    )
    if (userError || !userData?.user) return json({ error: 'Unauthorized' }, 401)

    const payload = await req.json()
    const excerpt = String(payload?.excerpt ?? '').slice(0, MAX_EXCERPT_CHARS)
    if (excerpt.trim().length < 80) {
      return json({ error: 'Excerpt too short to summarise' }, 422)
    }

    const prompt = buildPrompt(payload, excerpt)
    const summary = await generate(prompt)
    return json({ summary })
  } catch (e) {
    console.error('generate-recap failed', e)
    return json({ error: 'Recap generation failed' }, 500)
  }
})

function buildPrompt(payload: any, excerpt: string): string {
  const chapters = Array.isArray(payload.chapterTitles) ? payload.chapterTitles : []
  return [
    'You are helping a reader resume a book. Summarise ONLY the passage below.',
    'Write 2-3 sentences in plain prose, present tense, second person ("you").',
    'No headings, no bullet points, no preamble such as "In this passage".',
    'Do not invent anything that is not in the passage.',
    '',
    `Book: ${String(payload.bookTitle ?? 'Untitled')}`,
    chapters.length ? `Chapters read: ${chapters.join(', ')}` : '',
    payload.lastSentence ? `Reader stopped at: "${payload.lastSentence}"` : '',
    '',
    'Passage:',
    excerpt,
  ].filter(Boolean).join('\n')
}

async function generate(prompt: string): Promise<string> {
  const res = await fetch(
    `https://generativelanguage.googleapis.com/v1beta/models/${MODEL}:generateContent`,
    {
      method: 'POST',
      headers: {
        'Content-Type': 'application/json',
        'x-goog-api-key': API_KEY,
      },
      body: JSON.stringify({
        contents: [{ role: 'user', parts: [{ text: prompt }] }],
        generationConfig: {
          maxOutputTokens: MAX_OUTPUT_TOKENS,
          temperature: 0.4,
        },
      }),
    },
  )
  if (!res.ok) throw new Error(`LLM ${res.status}: ${await res.text()}`)
  const data = await res.json()
  return data?.candidates?.[0]?.content?.parts?.[0]?.text?.trim() ?? ''
}
```

**Design notes:**
- **No model is bundled or stored.** The function is a thin, auditable proxy.
- `maxOutputTokens = 160` hard-caps cost. A recap is 2–3 sentences.
- `temperature = 0.4` — descriptive but not inventive.
- The prompt explicitly forbids inventing content. Small models hallucinate
  plot details readily; grounding them on the passage and forbidding invention
  is the single most effective mitigation.
- The `422` on a too-short excerpt prevents paid calls on junk input.

### 7.2 Configuration and deployment

**`supabase/config.toml`:**

```toml
[functions.generate-recap]
verify_jwt = true
```

> **⚠️ `verify_jwt = true` is not a signed-in-user check.** The anon key *is* a
> valid JWT, so it passes gateway verification and anonymous callers get through.
> To require an authenticated user the function must resolve the caller itself —
> `admin.auth.getUser(token)` as in §7.1. **Do not delete that block as
> redundant.**

**Secrets** — set via CLI, never committed, never in `config.toml`:

```bash
supabase secrets set RECAP_API_KEY=... RECAP_MODEL=gemini-3.1-flash-lite
supabase secrets list          # verify
```

Read them in the function with `Deno.env.get('RECAP_API_KEY')`.

**Local development:**

```bash
supabase functions serve generate-recap --env-file ./supabase/.env.local
# local endpoint: http://127.0.0.1:54321/functions/v1/generate-recap
```

**Smoke test without building any app code:**

```bash
curl -i -X POST 'http://127.0.0.1:54321/functions/v1/generate-recap' \
  -H "Authorization: Bearer $ACCESS_TOKEN" \
  -H 'Content-Type: application/json' \
  -d '{"bookTitle":"Dune","chapterTitles":["Ch. 4"],"excerpt":"...at least 80 chars..."}'
```

**Deploy:**

```bash
supabase functions deploy generate-recap            # add --project-ref <ref> if not linked
supabase functions list
```

**Logs:** `supabase functions logs generate-recap` locally, or the dashboard's
Edge Functions → Logs remotely. Remember §11 — logs must never contain the
excerpt or the summary.

### 7.3 Provider flexibility

The function is a plain `fetch`, so swapping providers is a one-function change.
Prices verified September 2026; re-check before committing to one.

| Provider | Model | $/M in → out | Note |
|---|---|---|---|
| Alibaba Qwen | `qwen3.7-flash` | **$0.03 / $0.13** | cheapest paid option |
| OpenAI | `gpt-5-nano` | $0.05 / $0.40 | cheap + well-known ecosystem |
| Zhipu | `glm-4.7-flash` | free outright | privacy review required |
| DeepSeek | `deepseek-v4-flash` | $0.22 / $0.66 off-peak | peak is 2×; cache hits ~3% of miss |
| Google | `gemini-3.1-flash-lite` | $0.25 / $1.50 | 1M context; prices rise 2027-01-01 |
| OpenAI | `gpt-5-mini` | $0.25 / $2.00 | better prose, still cheap |

**Free tiers (development only — see the §11 warning):**

| Provider | Allowance | ≈ recaps/month |
|---|---|---|
| Google Gemini (3.x Flash) | 1,500 req/day, 10–30 RPM | ~45,000 |
| OpenRouter `:free` models | 50/day → 1,000/day after $10 credit | 1,500–30,000 |
| Groq free plan | 250–1,000/day per model | 7,500–30,000 |
| Cloudflare Workers AI | 10,000 Neurons/day | varies |

Both Google and OpenAI also issue free development credits, so this costs
**$0** until there is real usage.

### 7.4 Implementing the Edge Function — walkthrough

The project already has a working Edge Function
(`supabase/functions/delete-cloud-account/index.ts`), a linked CLI, migrations
and tests. The scaffolding exists; this is the delta.

**Step 1 — Create the directory.** Supabase expects one folder per function with
an `index.ts` entry point. The folder name *is* the function name:

```
supabase/functions/generate-recap/index.ts
```

**Step 2 — Write the handler.** Use `Deno.serve`. Return a `Response` with a JSON
body and `Content-Type: application/json` or the Kotlin decode will fail.

```ts
Deno.serve(async (req) => {
  if (req.method === 'OPTIONS') return new Response('ok', { headers: corsHeaders })
  try {
    const payload = await req.json()      // matches the Kotlin request body
    // ... validate, call LLM ...
    return json({ summary })
  } catch (e) {
    console.error('generate-recap failed', e)   // never log excerpt/summary
    return json({ error: 'Recap generation failed' }, 500)
  }
})
```

Full source in §7.1.

**Step 3 — Register it.** Add the `[functions.generate-recap]` block to
`supabase/config.toml` (§7.2).

**Step 4 — Add secrets.** `supabase secrets set` (§7.2). Locally, use
`--env-file ./supabase/.env.local` with the same keys.

**Step 5 — Serve and curl it.** Confirm the LLM key works and the response shape
is `{ "summary": "..." }` *before* writing a line of Kotlin. This is the
10-minute spike that de-risks the whole integration.

**Step 6 — Deploy.** `supabase functions deploy generate-recap`.

**Step 7 — Wire the Kotlin client.** §6, ~15 lines.

**Runtime notes:**
- **Deno, not Node.** Imports use URL/npm specifiers — `import { createClient }
  from 'npm:@supabase/supabase-js@2'`. No `package.json`, no `node_modules`.
- **No build step.** TypeScript is transpiled on deploy.
- **Timeouts.** The gateway allows ~60 s (free) / ~150 s (pro). A single LLM
  call is 1–3 s — comfortable. The retry/timeout settings are in
  `config.toml` under `[functions.<name>]` if needed.
- **Cold starts.** Deno functions typically cold-start in ~1 s. Irrelevant here
  since the user is looking at a shimmer.
- **Stateless.** Nothing persists between invocations. Anything you want to keep
  goes in Postgres — which the recap deliberately does not do (§11).
- **Idempotency.** The recap is cached client-side in `ai_summary`, so the
  function is called at most once per session. No dedup needed server-side.

---

## 8. UI / UX specification

### 8.1 Copy

Strings added to
`translations/src/commonMain/composeResources/values/strings.xml`, following the
existing `reader_*` naming:

| Key | Value |
|---|---|
| `reader_recap_prompt` | `Want a recap of your last session?` |
| `reader_recap_prompt_action` | `Show recap` |
| `reader_recap_sheet_title` | `Last session` |
| `reader_recap_stopped_here` | `You stopped here` |
| `reader_recap_chapters_read` | `Chapters read` |
| `reader_recap_ai_heading` | `What happened` |
| `reader_recap_generate` | `Generate recap` |
| `reader_recap_generating` | `Writing your recap…` |
| `reader_recap_generation_failed` | `Couldn't write a recap` |
| `reader_recap_retry` | `Retry` |
| `reader_recap_resume` | `Resume reading` |
| `reader_recap_duration_minutes` | `%1$d min` |
| `reader_recap_pages_read` | `%1$d pages` |
| `reader_recap_progress_percent` | `%1$d%% through the book` |

All with `tools:ignore="MissingTranslation"` to match the surrounding entries.

### 8.2 Snackbar

Modelled exactly on `BookmarkAddedSnackbar`
(`ReaderScreen.kt:407`), which already handles action + dismissal:

```kotlin
@Composable
private fun RecapPromptSnackbar(
    showMessage: Boolean,
    onShowRecap: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val snackbarHostState = remember { SnackbarHostState() }
    val message = stringResource(StringRes.reader_recap_prompt)
    val actionLabel = stringResource(StringRes.reader_recap_prompt_action)

    LaunchedEffect(showMessage) {
        if (showMessage) {
            val result = snackbarHostState.showSnackbar(
                message = message,
                actionLabel = actionLabel,
                duration = SnackbarDuration.Long,
            )
            when (result) {
                SnackbarResult.ActionPerformed -> onShowRecap()
                SnackbarResult.Dismissed -> onDismiss()
            }
        }
    }

    SnackbarHost(hostState = snackbarHostState, modifier = modifier)
}
```

Placed in the existing `Box` alongside the other snackbars
(`Modifier.align(Alignment.BottomCenter)`).

**Timing.** The ViewModel sets `recapPrompt` 2.5 s after `openPublication()`
succeeds (a `delay(RECAP_PROMPT_DELAY_MS)` inside a `viewModelScope.launch`
guarded by the eligibility rules in §9.3). The delay lets the reader render first.

### 8.3 Recap sheet

A `ModalBottomSheet` (matching `BookmarksSheet.kt` / `TableOfContentsSheet.kt`).

```
┌──────────────────────────────────────────────┐
│  Last session                            ✕   │
│                                              │
│  38 min · 22 pages · 34% through the book    │
│                                              │
│  ── Chapters read ─────────────────────      │
│  Chapter 4 · Chapter 5 · Chapter 6           │
│                                              │
│  ── You stopped here ──────────────────      │
│  “the door had been open the whole           │
│   time, and she had walked past it           │
│   twice without seeing.”                     │
│                                              │
│  ── What happened ─────────────────────      │
│  ▪ shimmering placeholder                    │
│  ▪ shimmering placeholder                    │
│                                              │
│  ┌────────────────────────────────────┐      │
│  │          Resume reading            │      │
│  └────────────────────────────────────┘      │
└──────────────────────────────────────────────┘
```

**States in `ReaderViewState`:**

```kotlin
val recapPrompt: SessionRecapUiModel? = null,   // drives the snackbar
val isRecapVisible: Boolean = false,            // drives the sheet
val recapAiState: RecapAiState = RecapAiState.Idle,
```

```kotlin
sealed interface RecapAiState {
    data object Idle : RecapAiState
    data object Loading : RecapAiState
    data class Ready(val summary: String) : RecapAiState
    data object Failed : RecapAiState
}
```

**`ReaderIntent` additions:**

```kotlin
data object ShowRecap : ReaderIntent
data object DismissRecapPrompt : ReaderIntent
data object DismissRecap : ReaderIntent
data object GenerateRecap : ReaderIntent
data object RetryRecap : ReaderIntent
```

**Render rules.**
- Tier 0 content renders instantly — the sheet is never blocked on the LLM.
- The "What happened" section shows a shimmer while `Loading`, the summary when
  `Ready`, and a **Retry** button when `Failed`.
- If `isAvailable()` is false, the AI section is hidden entirely — Tier 0 only.
- "Resume reading" just closes the sheet (the reader is already at the saved
  position).

---

## 9. Behavioural rules

### 9.1 Migration numbering

Migrations `26.sqm`–`31.sqm` are currently **uncommitted** (unified-library work)
and `build.gradle.kts` says `version = 32` on that branch, while `HEAD` says
`version = 25`. Confirm which lands first:

- If unified-library lands first: recap uses `32.sqm`, `version = 33`.
- If recap lands first: recap uses `26.sqm`, `version = 26`, and the unified-library
  work is rebased on top.

**Do not guess.** SQLDelight schema verification will fail the build if the
migration sequence and `version` disagree.

### 9.2 Minimum session length

Write the `book_recap` row only when:

```
durationMs >= 60_000                        // matches MINIMUM_READING_DURATION_MS
|| |endProgression - startProgression| >= 0.01   // or meaningful progress
```

Otherwise leave the previous recap untouched. A 5-second open/close must not
overwrite a 40-minute session's recap.

### 9.3 Recap eligibility on open

Show the prompt only when **all** hold:

1. A `book_recap` row exists (not the first read).
2. `now - sessionEnd >= RECAP_MIN_GAP_MS` (4 h) — you don't need a recap of
   something you read an hour ago.
3. `now - sessionEnd <= RECAP_MAX_AGE_MS` (30 days) — older recaps are stale.
4. The prompt has not already been dismissed for this session id
   (`recapPrompt` is cleared after dismissal so it never re-fires in the same
   reader visit).

### 9.4 Populate `start_progression`

`SaveReadingSessionUseCase` currently receives only `endProgression`. The recap
needs the range. `ReaderViewModel` tracks `bookOpenedTimestamp`; the equivalent
initial progression is available from the position restored in
`openPublication()` (`data.progressResult.toUiData()`), or from
`sessionStartProgression` captured at open. Capture it in a field next to
`bookOpenedTimestamp` and pass it to `SaveReadingSessionUseCase` as well —
this is a small additive change to the statistics path and benefits the
statistics feature too.

### 9.5 Multiple chapters

A session may span several chapters. Two options:

- **v1 (recommended):** capture the excerpt from the chapter the reader is in at
  close (`getChapterSentences()`), and record the *titles* of chapters visited
  during the session (tracked in a `visitedChapterHrefs: MutableSet<String>` in
  the ViewModel as the location observer fires). The prompt gets full text for
  one chapter plus the list of titles. Cheap, and the stopping chapter is the
  most relevant material for a recap.
- **v2:** accumulate excerpts per chapter as the reader navigates, into a rolling
  buffer capped at 8,000 chars. Better recaps for long sessions; more writes and
  more memory.

Start with v1; measure whether recaps feel thin.

---

## 10. Cost model

### 10.1 Per-recap

Input ≈ 3,000 tokens (curated excerpt + prompt), output ≈ 100 tokens (2–3
sentences, hard-capped at 160). **Input is ~97% of the cost** at this ratio.

| Model | $/M in → out | **Per recap** | 30k/mo | 300k/mo |
|---|---|---|---|---|
| Qwen3.7 Flash | $0.03 / $0.13 | **$0.00010** | $3 | $31 |
| GPT-5 nano | $0.05 / $0.40 | **$0.00019** | $6 | $57 |
| DeepSeek V4 Flash (off-peak) | $0.22 / $0.66 | **$0.00073** | $22 | $73 |
| Gemini 3.1 Flash-Lite | $0.25 / $1.50 | **$0.00090** | $27 | $90 |
| GPT-5 mini | $0.25 / $2.00 | **$0.0010** | $29 | $100 |

At the cheapest paid rate this is **about one hundredth of a cent per recap.**
Cost is not a design constraint at any realistic scale.

**Free tiers make it literally $0** for the first ~45,000 recaps/month — but see
the §11 warning before using them in production.

### 10.2 Levers that matter more than model choice

1. **Cut the input.** It is ~97% of the bill. Halving the excerpt from 8,000 to
   4,000 chars halves the cost — a bigger saving than dropping from Gemini
   Flash-Lite to Qwen3.7 Flash. If recaps still read well at 4k, cap it there.
   `MAX_EXCERPT_CHARS` is the single most effective cost control in the design.

2. **Pre-generate at session close instead of on tap.** OpenAI, Anthropic and
   Google all give a **flat 50% batch discount** (24 h completion window).
   Batch is unusable for tap-and-wait — but if the recap is generated when the
   reader *closes* the book rather than when they tap "Show recap", latency
   stops mattering and the tap becomes instant, which is a *better* UX than a
   1–3 s shimmer. Tradeoffs: you pay for recaps nobody opens (irrelevant at
   $0.0001) and you need somewhere to park the result between close and open —
   either call synchronously at close and store locally (simple, full price), or
   batch to the server and fetch on open (50% off, but the summary then lives
   server-side and needs a Postgres table — a §11 consideration).

   **Recommendation:** synchronous at close in v1. It removes perceived latency
   and keeps everything local. Batch is a later optimisation if volume ever
   justifies it.

3. **Prompt caching will not help.** The instruction prefix is only ~200 tokens
   and the excerpt is unique per request, so there is almost nothing to cache.
   Do not bother.

4. **Peak/off-peak matters for DeepSeek** (2× swing, 01:00–10:00 UTC weekdays are
   the cheap window) but not for the others.

### 10.3 Supabase platform

Incremental cost is effectively **$0** — the recap rides on the existing Parrot
Cloud project.

| Item | Free | Pro ($25/mo) | Recap usage at 300k/mo |
|---|---|---|---|
| Edge Function invocations | 500k/mo | 2M/mo, then $2/M | 300k — 15% of Pro quota |
| Egress | 5 GB | 250 GB | ~0.6 GB (2 KB response × 300k) |
| Ingress (excerpt upload) | — | — | Not charged |

The recap is not reached until ~1.3M recaps/month on Pro.

### 10.4 Guardrails

- `maxOutputTokens = 160` caps output cost regardless of input size.
- `MAX_EXCERPT_CHARS = 8_000` caps input cost.
- The `422` short-excerpt guard avoids paying for unusable input.
- A summary is generated **once** and cached in `ai_summary` — re-opening the
  sheet never re-bills.
- Consider a per-user daily cap in the Edge Function if abuse becomes a concern;
  `docs/parrot-cloud-abuse-runbook.md` is the existing precedent.

---

## 11. Privacy and security

This is the sensitive part of the feature and it must be stated plainly to users.

**What leaves the device.** Only when the user taps **Show recap** *and* is signed
into Parrot Cloud *and* has cloud recaps enabled:
- the book title,
- the titles of chapters read,
- up to 8,000 characters of the passage being read,
- the sentence the reader stopped at.

That is **reading content**. It is the reason this feature has an off switch.

> **⚠️ Never use a free LLM tier in production for this feature.** Google's
> pricing page carries a *"content used to improve our products"* clause on the
> free tier versus *"not used"* on paid. Sending a reader's book passage to a
> tier that may be used for training directly contradicts everything below.
> Free tiers are fine for development and for the curl spike in §7.2 — they are
> not fine for user content. The same scrutiny applies to free offerings from
> Zhipu, MiniMax and aggregator `:free` endpoints. **Use a paid tier, and say
> which provider you use in the in-app notice.**

**Required controls:**
1. **Opt-out setting** — `ReaderSettings` gains `cloudRecapEnabled: Boolean`,
   default **false** on first run with a one-time explanation, or default true
   with a clear notice in the sheet. **Recommendation: default false**, and let
   the sheet show a one-line "Recaps are generated on our server from the passage
   you just read. [Learn more] [Disable]".
2. **No logging of content.** The Edge Function logs errors only — never the
   excerpt, the summary, or the prompt. Existing rule in the abuse runbook.
3. **Auth required** — `verify_jwt = true`; the function additionally resolves
   the user from the bearer token.
4. **RLS / no persistence** — the excerpt is never written to Postgres or Storage.
   It exists in the request body and in memory for the duration of the call.
5. **Transport** — HTTPS only, via the Supabase client.
6. **Account deletion** — `book_recap` is local-only and must be included in
   `DataClearable` so it is wiped with the rest of user data; nothing to do on
   the server side since nothing is retained.

**Tier 0 is entirely local** — no network, no analytics containing content.

---

## 12. Error handling and degradation

| Scenario | Behaviour |
|---|---|
| First read of a book (no row) | No snackbar, no sheet |
| Session shorter than threshold | Previous recap preserved; if none, no snackbar |
| Reader opened offline | Tier 0 works; AI section hidden (`isAvailable()` false) |
| Not signed into Parrot Cloud | Tier 0 only |
| `cloudRecapEnabled = false` | Tier 0 only |
| Edge Function 4xx/5xx | Sheet shows Tier 0 + **Retry**; error logged to analytics (no content) |
| LLM returns empty / too long | Treated as failure → **Retry** |
| Excerpt missing or < 80 chars | AI section hidden entirely (nothing worth summarising) |
| `getChapterSentences()` returns empty (e.g. DRM/unusual markup) | `excerpt` and `lastSentence` null; Tier 0 still shows stats + chapters + position |
| Recap prompt dismissed | Never shown again for that session |
| App killed mid-close | Recap row missing → no prompt next time (graceful) |

**Non-negotiable:** the reader is never blocked, delayed or made unusable by any
part of this feature. Recap generation runs in `viewModelScope` and its result is
pure state.

---

## 13. Future work — on-device tier

If privacy or scale later justifies it, implement `RecapEngine` on-device behind
the same seam.

**Model:** Gemma 3 270M IT, 4-bit. ~175–200 MB, ~550 MB RAM at runtime, 32k
context. Below 270M params the prose stops being worth showing.

**Distribution:** **do not put it in the APK.** Reuse
`TtsModelManager.kt`'s lifecycle — download from a release bucket on demand,
extract, verify completeness, cache, delete. A Community `.task` bundle for the
MediaPipe LLM Inference API exists (`litert-community/gemma-3-270m-it-q8`,
~320 MB); a 4-bit variant is smaller.

**Runtime:** MediaPipe LLM Inference API / LiteRT-LM (Android). iOS would need a
separate path (CoreML / Apple Foundation Models) — out of scope.

**Expected trade-off:** better privacy and offline, worse prose quality, and
200 MB+ of download plus a real memory footprint on low-end devices. Only worth
it if the cloud tier's privacy surface becomes a product problem.

**Even smaller alternative:** a model-free extractive recap (pick the first
sentence of each chapter read + the stopping sentence) is zero-cost and instant,
but reads like an outline. Worth considering as a middle tier if cloud recaps
are disabled and the user still wants something.

---

## 14. Testing strategy

### 14.1 Unit (commonTest)

| Area | Tests |
|---|---|
| `SaveSessionRecapUseCase` | short-session guard preserves previous row; excerpt truncation at sentence boundary; `aiSummary` reset; null handling |
| `GetLastSessionRecapUseCase` | no row → null; stale row → null; fresh row → mapped; gap too small → null |
| `GenerateSessionRecapUseCase` | cached summary short-circuits; blank excerpt → `Err`; engine failure → `Err`; success persists via DAO |
| `BookRecapSqlDelightDao` | upsert/read round-trip; `saveAiSummary` update; `clearAllData` — model on `LibraryGroupsSqlDelightDaoTest` (androidHostTest + sqlite-driver) |
| `ChapterSentenceExtractor` | existing tests already cover parsing; add cases for excerpt assembly |

### 14.2 Integration

- Edge Function: a `supabase/tests/` style harness or a manual runbook —
  request with no auth → 401; short excerpt → 422; happy path → non-empty summary.
- Round-trip: open book → read → close → reopen → recap row present and correct.

### 14.3 UI

- Snackbar appears once after the delay and never twice in a session.
- Dismiss vs. action routing.
- Sheet renders Tier 0 immediately; AI section state machine (Idle → Loading →
  Ready / Failed → Retry).

### 14.4 Manual / QA matrix

| Device state | Expected |
|---|---|
| Airplane mode | Tier 0 works, no AI section |
| Signed out | Tier 0 works, no AI section |
| Low-end device, slow network | Reader stays responsive; sheet shows shimmer |
| Book with unusual markup (no sentence spans) | Tier 0 minus "stopped here" |
| 1st open / 2nd open / reopen after 1 week | none / prompt / none (stale) |

---

## 15. Implementation milestones

| # | Deliverable | Depends on | Size |
|---|---|---|---|
| **M1** | DB layer: `BookRecap.sq`, migration, entity, DAO, impl, DI, tests | §9.1 resolved | S |
| **M2** | Domain: `SessionRecap`, `RecapEngine`, 3 use cases + tests | M1 | S |
| **M3** | Capture: wire `close()` to `SaveSessionRecapUseCase` via `getChapterSentences()` + `getVisibleSentenceId()`; populate `startProgression` | M2 | M |
| **M4** | Tier 0 UI: snackbar + sheet, all local content, states/intents/copy | M3 | M |
| **M5** | Edge Function + config + secrets + `CloudRecapEngine` + gradle wiring | M2 | M |
| **M6** | AI section in the sheet + caching + retry + opt-out setting | M4, M5 | M |
| **M7** | Analytics, QA matrix, abuse guardrails, docs | M6 | S |

**M1–M4 ship a complete, useful feature with zero cost and zero network.**
M5–M6 add the AI tier. This ordering means the AI tier can be cut at any point
without leaving dead code.

---

## 16. File-by-file change manifest

### New files

| Path | Section |
|---|---|
| `lib/database/implementation/src/commonMain/sqldelight/com/retro99/database/implementation/BookRecap.sq` | §5.1 |
| `lib/database/implementation/src/commonMain/sqldelight/com/retro99/database/implementation/32.sqm` | §5.1 / §9.1 |
| `lib/database/api/src/commonMain/kotlin/com/retro99/database/api/books/BookRecapEntity.kt` | §5.2 |
| `lib/database/api/src/commonMain/kotlin/com/retro99/database/api/books/BookRecapDatabase.kt` | §5.2 |
| `lib/database/implementation/.../dao/books/BookRecapSqlDelightEntity.kt` | §5.2 |
| `lib/database/implementation/.../dao/books/BookRecapSqlDelightDao.kt` | §5.2 |
| `lib/database/implementation/.../dao/books/BookRecapDatabaseImpl.kt` | §5.2 |
| `feature/reader/domain/.../recap/SessionRecap.kt` | §5.3 |
| `feature/reader/domain/.../recap/RecapRequest.kt` | §5.3 |
| `feature/reader/domain/.../recap/RecapEngine.kt` | §5.3 |
| `feature/reader/domain/.../usecase/SaveSessionRecapUseCase.kt` | §5.4 |
| `feature/reader/domain/.../usecase/GetLastSessionRecapUseCase.kt` | §5.4 |
| `feature/reader/domain/.../usecase/GenerateSessionRecapUseCase.kt` | §5.4 |
| `feature/reader/data/.../recap/CloudRecapEngine.kt` | §6 |
| `feature/reader/ui/.../model/SessionRecapUiModel.kt` | §8 |
| `supabase/functions/generate-recap/index.ts` | §7.1 | ✅ created |
| `supabase/spike/recap_spike.py` | §18 | ✅ created |
| `supabase/.env.local` (gitignored) | §7.2 — local mirror of `RECAP_API_KEY`, `RECAP_MODEL` |

### Modified files

| Path | Change | Section |
|---|---|---|
| `lib/database/implementation/build.gradle.kts` | `version = 33` (see §9.1) | §5.1 |
| `lib/database/implementation/.../di/DatabaseModule.kt` | 2 providers + `DataClearable` | §5.2 |
| `feature/reader/data/build.gradle.kts` | + `libs.supabase.functions`, + `lib.cloud.implementation` | §6 |
| `feature/reader/domain/.../model/PositionDomainModel.kt` | no change expected | — |
| `feature/reader/ui/.../reader/ReaderViewModel.kt` | capture on `close()`, load on open, 5 intents | §4, §8 |
| `feature/reader/ui/.../reader/ReaderViewState.kt` | `recapPrompt`, `isRecapVisible`, `recapAiState` | §8.3 |
| `feature/reader/ui/.../reader/ReaderIntent.kt` | 5 new intents | §8.3 |
| `feature/reader/ui/.../reader/ReaderScreen.kt` | `RecapPromptSnackbar`, `RecapSheet` | §8 |
| `translations/src/commonMain/composeResources/values/strings.xml` | 13 new strings | §8.1 |
| `supabase/config.toml` | `[functions.generate-recap]` | §7.2 | ✅ done |
| `lib/cloud/implementation/.../SupabaseClientProvider.kt` | optional: `install(Functions) { defaultRegion = ... }` | §6.2 |
| `lib/preferences/api/.../Preferences.kt` + reader settings | `cloudRecapEnabled` | §11 |

Reference (do not modify): `SupabaseCloudAccountDataRepository.deleteAccount()` is
a **working** Edge Function invocation in this codebase — `client.functions("delete-cloud-account")`
hits the `operator fun invoke` overload and does POST (§6.1).

### Explicitly unchanged

- `feature/statistics/**` — the statistics pipeline is untouched (§2.3).
- Sync engine and all server adapters — recap data is local-only.
- `ChapterSentenceExtractor.kt` — reused as-is.

---

## 17. Open questions

1. **Opt-out default.** `cloudRecapEnabled` default `false` (privacy-first, more
   friction) or `true` (more users see the feature)? **Recommend `false`.**
2. **Staleness window.** 4 h / 30 d (§9.3) is a guess. Tune after usage data.
3. **Audiobooks.** The recap is written from `ReaderViewModel.close()`, which
   also handles audiobook and read-aloud sessions. Should an audiobook session
   produce a text recap? `getChapterSentences()` is a no-op there, so Tier 0
   would show stats + position but no "stopped here". Probably yes for Tier 0,
   and the AI section hidden.
4. **Should the recap survive a book finishing?** If `endProgression >= 0.99`,
   maybe swap the recap for a "You finished this book" card instead.
5. **Multi-device.** `book_recap` is local-only. If progress syncs across
   devices, the recap will describe a session from another device. Acceptable
   for v1; flag if users notice.

---

## 18. Spike — status and how to run it

The spike exists to answer one question: **does a cheap model produce a recap
worth showing a reader?** Everything else in the AI tier is mechanical — the
Edge Function has a working precedent in this repo (`delete-cloud-account`), and
the supabase-kt client call is verified against the library source (§6.1). If
the prose is bad, none of that matters.

### 18.1 What is implemented

| Artifact | Status |
|---|---|
| `supabase/functions/generate-recap/index.ts` | ✅ written |
| `supabase/config.toml` → `[functions.generate-recap]` | ✅ written |
| `supabase/spike/recap_spike.py` | ✅ written, syntax verified |

The spike harness deliberately does **not** exercise the Edge Function. It calls
the LLM API directly with the **same prompt** as `index.ts` (kept in sync), so
what you judge is exactly what would ship.

### 18.2 What is verified vs. not

**Verified in this environment:**

- `recap_spike.py` compiles and runs (stdlib only, no dependencies).
- Missing-key path exits with a clear message.
- **OpenAI-compatible path**: request shape, auth header and response parsing
  confirmed — a bogus key returns a clean HTTP 401 from OpenAI, meaning the
  request reached the API correctly.
- **Gemini path**: same — Google returns `API key not valid`, confirming the
  URL, `x-goog-api-key` header and body shape.
- Prompt builder output confirmed for both long and short excerpts.

**Not verified — and this is the actual spike:**

- Model output quality. Needs a real key.
- Whether the prompt's "do not invent" instruction survives a 270M–1B-class
  model. This is the single biggest quality risk in the design.
- Real latency from your network/region.

### 18.3 How to run it

No CLI, Docker, Deno or Node required — just `python3`.

```bash
export RECAP_API_KEY=sk-...
python3 supabase/spike/recap_spike.py              # all 4 cases
python3 supabase/spike/recap_spike.py --case 2     # one case
```

Other providers:

```bash
# Gemini
RECAP_PROVIDER=gemini RECAP_API_KEY=AIza... python3 supabase/spike/recap_spike.py

# Cheapest paid option via an OpenAI-compatible endpoint
RECAP_MODEL=qwen3.7-flash RECAP_BASE_URL=https://openrouter.ai/api/v1 \
  RECAP_API_KEY=... python3 supabase/spike/recap_spike.py

# Compare two models back to back
RECAP_MODEL=gpt-5-nano   RECAP_API_KEY=... python3 supabase/spike/recap_spike.py
RECAP_MODEL=qwen3.7-flash RECAP_BASE_URL=https://openrouter.ai/api/v1 \
  RECAP_API_KEY=... python3 supabase/spike/recap_spike.py
```

The script prints each summary alongside its token counts, latency and
per-recap cost, plus the projected monthly figure at 300k recaps.

### 18.4 The four test cases

Each is chosen to break a different failure mode, using public-domain text:

| # | Passage | What it stresses |
|---|---|---|
| 1 | *Pride and Prejudice* — dialogue, simple narrative | Baseline. Should be easy. |
| 2 | *Moby-Dick* — dense descriptive prose | Compression. Does it find the point? |
| 3 | *Frankenstein* — introspective, dialogue-light | Does it invent emotion/motivation? |
| 4 | *Dracula* — mid-scene fragment, no context | Robustness. Does it hallucinate a plot? |

### 18.5 Pass / fail

Fail the spike if **any** of these hold across the four cases:

- [ ] It invents plot points not present in the passage — **the critical one**
- [ ] Output is generic filler that would fit any book
- [ ] It opens with "In this passage" / "The passage describes" (the script flags this)
- [ ] It is materially longer than 3 sentences
- [ ] "You" reads as forced or it drifts to third person

If it fails on invention, **tighten the prompt before writing any Kotlin.**
Likely fixes: shorten the excerpt (invention scales with input noise), state
"if unsure, omit it", or move to the next model up (`gpt-5-mini`, +$0.0008 per
recap — still negligible per §10.1).

### 18.6 Decision gate

Only after the spike passes:

1. Record the winning model + prompt in §7.3 and §7.1.
2. Proceed to **M1** (database layer, §15) — which is independent of the AI tier
   and could be started in parallel at any time.
3. Re-confirm the §11 free-tier warning holds for whatever provider you chose.
