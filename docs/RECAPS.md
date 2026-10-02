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

## Stage 2 — capture (`feature/reader/ui/.../reader/ReaderRecapCapture.kt`)

`ReaderViewModel` creates one `ReaderRecapCapture` per session, and only when
`isCloudRecapsEnabled()` is true at open. Withdrawing consent stops it for good. With
consent off, no script runs and nothing is appended. Text is appended **at the event**,
never at close, because `close()` tears down the WebView. The recorder persists each
append, so an app kill still leaves the text read so far.

| Source | When it counts | What is appended |
|---|---|---|
| Manual reading (`PAGE`) | the page has been visible ≥ `RecapCapturePolicy.PAGE_DWELL_MS` (5 s) in the foreground, with no startup prompt open and device TTS not speaking | `BookController.getVisibleTextRange()`: first to last visible word |
| Device TTS (`TTS_SENTENCE`) | `TtsController.finishedSentences`: the sentence's audio played to its end (ExoPlayer AUTO transition or end of playlist). Skips, seeks and stops don't count | that sentence |

- **No chapter-end fallback.** `VisibleTextRangeDetector` returns null when no text is
  visible (image page, blank page), so nothing is captured. It reads the DOM and never
  changes it. Offsets count all text outside script and style, so the read-aloud
  sentence spans don't move them.
- **Dedupe** (`RecapReadTracker`): pages by chapter character range (rereading or going
  back adds nothing; a relaid-out page adds only the new part); sentences by chapter
  href plus sentence index. A sentence heard on a page that was already captured as
  text can still appear twice. The two sources don't share coordinates.
- **Skipped pages:** a page left before the dwell time is never read. A page that changes
  while its text is being read is dropped.
- **Chapters:** each chunk carries the chapter (index, title) and position (href,
  progression, totalProgression) of the page it came from. A heard sentence from another
  chapter goes without them.
- **Session end:** `lastSentence` is the last sentence of the last appended chunk (≤ 300
  chars; a fragment if reading stopped mid-sentence). `activeReadingMs` comes from
  `RecapActiveReadingClock`. It counts while the reader is started, or while narration or
  TTS plays in the background, and stops 3 min after the last page turn or heard sentence.
- **Language** (`RecapLanguages.resolve`): the book's metadata language when the API
  supports it, else the system locale, else null. Allow-list: en, sl, de, fr, es, it, hr.
- **Recorder** (`RecapSessionRecorder.appendReadText`): calls are non-blocking and
  queued in order. The buffer keeps the most recent 8,000 chars and ignores a segment
  equal to the previous one. A `PAGE` append whose `totalProgression` moves forward
  counts as a page advance, and each new `TTS_SENTENCE` as one sentence.
- **iOS:** pages are captured as on Android. iOS device TTS is a stub with no sentence
  callbacks, so there's no TTS capture there. Narration (media overlays) is captured
  through the pages it turns on both platforms.

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
