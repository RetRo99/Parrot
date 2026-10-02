# Session recaps — Stage 1 contracts

One AI recap per **reading session**, kept as history. Stage 1 is the foundation
(storage, engine, job runner, consent, session bookkeeping). Stage 2 feeds read text;
Stage 3 builds the UI. Nothing is captured or sent unless `cloudRecapsEnabled` is on.

## Flow

```
ReaderViewModel open  -> RecapSessionRecorder.onSessionStarted      (row CAPTURING)
Stage 2 adapters      -> RecapSessionRecorder.appendReadText        (bounded excerpt)
ReaderViewModel close -> RecapSessionRecorder.onSessionEnded        (PENDING | SKIPPED_INELIGIBLE)
RecapJobRunner (app scope) -> RecapEngineSelector -> CloudRecapEngine -> generate-recap
UI                    -> RecapRepository.observe* / retry
```

Status: `CAPTURING → PENDING → RUNNING → SUCCEEDED | NOT_ENOUGH | FAILED_RETRYABLE |
FAILED_PERMANENT`, or `CAPTURING → SKIPPED_INELIGIBLE`. `FAILED_RETRYABLE` goes back to
`RUNNING` when due. `AuthRequired` returns the row to `PENDING` (error `AUTH_REQUIRED`).

## Stage 2 — capture (`feature/reader/domain/.../recap/RecapSessionRecorder.kt`)

Inject `RecapSessionRecorder` (Koin). The session id is `ReaderViewModel.recapSessionId`
(random UUID minted when the publication opens); start and end are already wired.

```kotlin
fun appendReadText(
    sessionId: String,
    text: String,                 // only text demonstrably read or heard
    chapter: RecapChapter?,       // index + title; titles never leave the device
    position: RecapPosition?,     // href, progression, totalProgression
    source: RecapTextSource = PAGE, // PAGE per settled page, TTS_SENTENCE per heard sentence
)
```

- Calls are non-blocking and applied in order on an app-scoped queue.
- The buffer keeps the most recent 8,000 chars and drops the oldest at a sentence or word
  boundary. A segment equal to the previous one is ignored.
- `PAGE` appends whose `totalProgression` moves forward count as page advances.
  Each new `TTS_SENTENCE` counts as one sentence.
- `onSessionEnded(sessionId, endPosition, lastSentence, activeReadingMs)`: the VM passes
  `lastSentence = null` and wall-clock time today. Stage 2 should pass the last confirmed
  sentence and foreground reading time.
- `onSessionStarted(..., language)`: the VM passes no language yet, so the server writes
  English. Stage 2/3 should pass the book language or the UI locale (`sl`, `en`, ...).

## Stage 3 — UI (`feature/reader/domain/.../recap/`)

| Contract | Use |
|---|---|
| `RecapRepository.observeRecap(sessionId): Flow<SessionRecap?>` | one session |
| `RecapRepository.observeLatestForBook(bookId): Flow<SessionRecap?>` | latest `SUCCEEDED`, else latest non-skipped ended session |
| `RecapRepository.observeHistory(bookId): Flow<List<SessionRecap>>` | history, newest first |
| `RecapRepository.retry(sessionId): RecapRetryResult` | explicit user retry |
| `RecapSettings.observeCloudRecapsEnabled()` / `setCloudRecapsEnabled()` | consent toggle (default off) |
| `RecapEngineSelector.observeAvailable()` | consent on **and** signed in |

Observing never triggers generation. `SessionRecap` exposes no excerpt text. Show
`summary` for `SUCCEEDED`; use `status`, `lastError` and `isInProgress` for the rest.

### Stage 3 — what's built

- **Statistics link.** `reading_session.recap_session_id` (33.sqm, local only; the synced
  payload maps fields explicitly and doesn't carry it). The reader passes its recap session
  id to `SaveReadingSessionUseCase` at close. Deleting a statistics session, or all of them,
  deletes the linked recaps. Removing a book from the device deletes its recaps unless a
  server copy (`books`) still has the id.
- **Settings.** App settings → Reading → "Cloud recaps" (`RecapSettings`, default off).
- **Reader.** `ReaderRecapBannerHost` (reader/ui `recap/`): a chip that expands into the
  newest `SUCCEEDED` recap of the book, only while cloud recaps are on. Pending and failed
  recaps aren't shown. Dismissal is stored per recap (`RecapBannerDismissals`) and
  triggers nothing else.
- **Statistics.** Tapping a session in the sessions sheet opens its detail. The recap state
  comes from `toSessionRecapUiState(cloudRecapsEnabled, engineAvailable)`. Retry is shown
  only when `SessionRecap.canRetry` is true, and it calls `RecapRepository.retry`.

## Eligibility (`RecapEligibility`)

A session gets a recap only if all of these hold:
- activity: ≥ 3 min active reading, **or** ≥ 2 forward page advances, **or** ≥ 20 TTS sentences
- excerpt ≥ 400 chars after trim
- not a reread only: it didn't end behind the start without ever passing it
- not a repeat: < 80 % of its word trigrams are in the previous session's excerpt,
  and its fingerprint differs

## Job runner (`feature/reader/data/.../recap/RecapJobRunner.kt`)

- App-scoped, one row at a time, oldest first. Each row is claimed by a conditional
  `UPDATE … WHERE status IN (PENDING, FAILED_RETRYABLE)` before any request.
- RUNNING rows untouched for 3 min are reset to PENDING (process death).
- Backoff: 1, 2, 4, 8 min … capped at 6 h; `Retry-After` wins when longer. After 5
  attempts → `FAILED_PERMANENT`. A retryable error or `AuthRequired` ends the pass.
- Triggers: app start, sign-in or consent turned on, session end, foreground and
  connectivity (`SyncTriggerBridge` / `RecapTriggerBridge`), user retry, scheduled retry.
  Android also runs `RecapWorker` (periodic 60 min plus one-off on background). iOS uses
  foreground triggers only.

## Retention (run at app start)

- The excerpt is dropped as soon as a result is stored, or at skip time (only a hash stays).
- Excerpts older than 14 days are nulled; pending rows then become
  `FAILED_PERMANENT/EXCERPT_EXPIRED`.
- Rows older than 180 days are deleted, as are rows whose book is in neither `books` nor
  live `library_books`. Library-book merges move rows; profile data clear deletes them.

## Privacy

The API receives only `excerpt`, `language` and `lastSentence` — no titles, ids or
chapter names. Logs carry status and error codes only (`RecapDiagnostics`).
`toString()` of inputs and results omits the text.
