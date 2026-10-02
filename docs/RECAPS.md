# Session recaps

One AI recap per **reading session**, kept as history per book. Read text is captured
while the user reads, a recap is generated in the background after the session ends,
and the result shows up in the reader (on reopen) and in Statistics (per session).
Nothing is captured or sent unless the user turns on **Cloud recaps** (default off).

## Architecture

```
reader/ui     ReaderViewModel ── ReaderRecapCapture ── BookController / TtsController
                    │ open/close        │ appendReadText (at the event)
reader/domain       ▼                   ▼
              RecapSessionRecorder ──► session_recap row (SQLDelight)
                                              │ PENDING
reader/data   RecapJobRunner ── RecapEngineSelector ── RecapEngine (CloudRecapEngine)
                                              │ SUCCEEDED / NOT_ENOUGH / FAILED_*
UI            RecapRepository.observe* / retry
              ├─ reader:     ReaderRecapViewModel + ReaderRecapBannerHost (chip)
              ├─ statistics: session detail (SessionRecapUiState)
              └─ settings:   App settings → Reading → Cloud recaps (RecapSettings)
```

| Layer | Module / file | Role |
|---|---|---|
| Storage | `lib/database` `SessionRecap.sq`, `SessionRecapSqlDelightDao` | `session_recap` table, one row per session |
| Link | `ReadingSession.sq`, `33.sqm` | local-only `reading_session.recap_session_id` |
| Domain | `feature/reader/domain/.../recap/` | models, policies (capture, eligibility, job, limits), `RecapEngine`, `RecapRepository`, `RecapSettings`, `RecapSessionRecorder` |
| Data | `feature/reader/data/.../recap/` | recorder impl, job runner, cloud engine, selector, mappers, startup, settings and dismissals in preferences |
| Capture | `feature/reader/ui/.../reader/ReaderRecapCapture.kt`, `navigator/VisibleTextRangeDetector.kt` | turns page dwell and finished TTS sentences into appends |
| Triggers | `composeApp` `RecapTriggerBridge`, `androidApp` `RecapWorker` | wake the runner |

Everything is wired through Koin annotations (`@Single` in reader data; the recorder
and `RecapSettings` are `@Provided` into `ReaderViewModel`).

## Session lifecycle

```
ReaderViewModel open  -> RecapSessionRecorder.onSessionStarted   (row CAPTURING)
ReaderRecapCapture    -> RecapSessionRecorder.appendReadText     (persisted per append)
ReaderViewModel close -> RecapSessionRecorder.onSessionEnded     (PENDING | SKIPPED_INELIGIBLE)
                      -> SaveReadingSessionUseCase(recapSessionId = …)
RecapJobRunner        -> RecapEngineSelector.select() -> RecapEngine.generate()
```

`close()` keeps the recap session id for the statistics row before
`endRecapSession()` clears it. A row left `CAPTURING` by an app kill is finished at the
next start with the text persisted so far.

## Capture rules (`ReaderRecapCapture`, `RecapCapturePolicy`, `RecapReadTracker`)

`ReaderViewModel` creates one `ReaderRecapCapture` per session, and only when
`isCloudRecapsEnabled()` is true at open (a read failure counts as off). Turning consent
off mid-session stops capture for that session; the row still ends with what was
captured so far. With consent off no script runs and nothing is appended. Text is
appended **at the event**, never at close, because `close()` tears down the WebView.

| Source | When it counts | What is appended |
|---|---|---|
| Manual reading (`PAGE`) | the page has been visible ≥ `PAGE_DWELL_MS` (5 s) in the foreground, with no startup prompt open and device TTS not speaking | `BookController.getVisibleTextRange()`: first to last visible word, with chapter offsets |
| Device TTS (`TTS_SENTENCE`, Android) | `TtsController.finishedSentences`: the sentence's audio played to its end. Skips, seeks, stops and unsynthesised sentences don't count | that sentence |
| Narration (media overlays) | through the pages it turns, foreground only | as `PAGE` |

- **No chapter-end fallback.** `VisibleTextRangeDetector` returns null when no text is
  visible (image page, blank page). It reads the DOM without changing it. Offsets count
  all text outside script and style, so read-aloud sentence spans don't move them.
- **Dedupe:** pages by chapter character range (rereading or going back adds nothing; a
  relaid-out page adds only the new part); sentences by chapter href plus sentence
  index. A sentence heard on a page already captured as text can appear twice; the two
  sources don't share coordinates.
- **Skipped pages:** a page left before the dwell time is never read. A page that
  changes while its text is being read is dropped.
- **Chapters:** each chunk carries its chapter (index, title) and position (href,
  progression, totalProgression). A heard sentence from another chapter goes without.
- **Foreground:** `ReaderScreen` forwards ON_START/ON_STOP as
  `ReaderIntent.ReaderVisibilityChanged`.
- **Session fields at end:**
  - `lastSentence`: last sentence of the last chunk (≤ 300 chars).
  - `activeReadingMs` (`RecapActiveReadingClock`): counts while the reader is started,
    or while narration/TTS plays in the background; stops 3 min after the last page
    turn or heard sentence.
  - `language` (`RecapLanguages.resolve`): book metadata language if supported, else
    the system locale, else null. Allow-list: en, sl, de, fr, es, it, hr.
- **Recorder buffer:** appends are non-blocking and queued in order. It keeps the most
  recent 8,000 chars (`RecapLimits.MAX_EXCERPT_CHARS`) and ignores a segment equal to
  the previous one. A forward `PAGE` counts as a page advance; each `TTS_SENTENCE` as
  one sentence.
- **iOS:** pages are captured as on Android. iOS device TTS is a stub with no sentence
  callbacks, so there is no TTS capture. Locator callbacks run one at a time so pages
  arrive in order.

## Eligibility (`RecapEligibility`, at session end)

A session gets a recap only if all of these hold, otherwise `SKIPPED_INELIGIBLE`:
- activity: ≥ 3 min active reading, **or** ≥ 2 forward page advances, **or** ≥ 20 TTS
  sentences
- excerpt ≥ 400 chars after trim
- not a reread only: it didn't end behind the start without ever passing it
- not a repeat: < 80 % of its word trigrams are in the previous session's excerpt, and
  its fingerprint differs

## Job lifecycle (`RecapJobRunner`, `RecapJobPolicy`)

```
CAPTURING ─► SKIPPED_INELIGIBLE
    │
    ▼
 PENDING ─► RUNNING ─► SUCCEEDED | NOT_ENOUGH | FAILED_PERMANENT
    ▲          │
    │          ├─► FAILED_RETRYABLE ─(due)─► RUNNING
    └──────────┴─► PENDING (AuthRequired, error AUTH_REQUIRED)
```

- App-scoped, one row at a time, oldest first. Each row is claimed by a conditional
  `UPDATE … WHERE status IN (PENDING, FAILED_RETRYABLE)` before any request.
- `RUNNING` rows untouched for 3 min are reset to `PENDING` (process death), or to
  `FAILED_PERMANENT/MAX_ATTEMPTS` once they have used all attempts.
- Backoff 1, 2, 4, 8 min … capped at 6 h; `Retry-After` wins when longer. After 5
  attempts → `FAILED_PERMANENT`. A retryable error or `AuthRequired` ends the pass.
- No engine available (consent off or signed out) → rows stay `PENDING`.
- Triggers: app start, sign-in or consent turned on, session end, foreground and
  connectivity (`SyncTriggerBridge` / `RecapTriggerBridge`), user retry, scheduled
  retry. Android also runs `RecapWorker` (periodic 60 min plus one-off on background).
  iOS uses foreground triggers only.
- **User retry** (`RecapRepository.retry`): a `FAILED_*` row that still has its excerpt
  and wasn't rejected for its input goes back to `PENDING` with attempts reset.
  `SessionRecap.canRetry` mirrors these rules, so the UI only offers Retry when it works.

## UI contracts

| Contract | Use |
|---|---|
| `RecapRepository.observeRecap(sessionId)` | one session |
| `RecapRepository.observeLatestForBook(bookId)` | latest `SUCCEEDED`, else latest non-skipped ended session |
| `RecapRepository.observeHistory(bookId)` | history, newest first |
| `RecapRepository.retry(sessionId): RecapRetryResult` | explicit user retry |
| `RecapSettings.observeCloudRecapsEnabled()` / `setCloudRecapsEnabled()` | consent toggle |
| `RecapEngineSelector.observeAvailable()` | consent on **and** signed in |

Observing never triggers generation. `SessionRecap` exposes no excerpt text.

- **Reader chip** (`ReaderRecapBannerHost`): the newest `SUCCEEDED` recap of the book,
  only while Cloud recaps is on and no startup prompt is open. Titled "Recap of an
  earlier session" when newer sessions have no recap. Pending and failed recaps are
  never shown. Dismissal is stored per recap (`RecapBannerDismissals`, last 200).
- **Statistics detail:** tapping a session opens time, speed, progress and its recap
  state (`toSessionRecapUiState(cloudRecapsEnabled, engineAvailable)`): none, not
  eligible, waiting for opt-in, generating, done (summary, engine, model), not enough
  read, failed with Retry, failed for good, sign-in required.
- **Settings:** App settings → Reading → Cloud recaps.

## Offline engine extension point

`RecapEngine` is provider-neutral; each row stores the `engineId` and `model` that
produced it. To add an on-device engine:

1. Implement `RecapEngine` in reader data with its own `id` (e.g. `"local"`). It gets a
   bounded `RecapInput` (excerpt, language, lastSentence) and maps its own failures to
   `RecapResult` (`Retryable`/`Permanent`; never `AuthRequired`).
2. Add a separate setting for it in `RecapSettings` (do not reuse cloud consent).
3. Choose it in `DefaultRecapEngineSelector.select()` when cloud isn't usable, and
   include it in `observeAvailable()`.
4. Capture is gated on cloud consent in `ReaderViewModel`; widen that gate to "any
   recap engine enabled".

The runner, retention, eligibility and UI need no changes.

## Retention (`RecapStartupInitializer`, at app start)

- The excerpt and last sentence are dropped as soon as a result is stored, when the
  server rejects the input, or at skip time (only a hash stays for the repeat check).
- Excerpts and last sentences older than 14 days are nulled; pending rows then become
  `FAILED_PERMANENT/EXCERPT_EXPIRED`.
- Rows older than 180 days are deleted, as are rows whose book is in neither `books`
  nor live `library_books`.
- Deleting a statistics session, or all of them, deletes the linked recaps in the same
  transaction. Recaps with no statistics row are kept until the rules above.
- Removing a book from the device deletes its recaps unless a server copy (`books`)
  still has the id. Library-book merges move rows; profile data clear deletes them.
- Banner dismissals live in preferences, capped at 200 ids.

## Privacy

The API receives only `excerpt`, `language` and `lastSentence`: no titles, ids or
chapter names. Summaries and excerpts are stored only on the device. Logs carry status
and error codes only (`RecapDiagnostics`); `toString()` of inputs, results and capture
types omits the text. `recap_session_id` is not part of the synced statistics payload.
