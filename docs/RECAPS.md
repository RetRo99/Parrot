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
              ├─ reader:     ReaderRecapViewModel + Last time toolbar pill / bottom sheet
              ├─ statistics: session detail (SessionRecapUiState)
              └─ settings:   App settings → Reading → Recaps (RecapSettings)
```

| Layer | Module / file | Role |
|---|---|---|
| Storage | `lib/database` `SessionRecap.sq`, `SessionRecapSqlDelightDao` | `session_recap` table, one row per session |
| Link | `ReadingSession.sq`, `33.sqm` | local-only `reading_session.recap_session_id` |
| Domain | `feature/reader/domain/.../recap/` | models, policies (capture, eligibility, job, limits), `RecapEngine`, `RecapRepository`, `RecapSettings`, `RecapSessionRecorder` |
| Data | `feature/reader/data/.../recap/` | recorder impl, job runner, cloud engine, selector, mappers, startup, settings and dismissals in preferences |
| Capture | `feature/reader/ui/.../reader/ReaderRecapCapture.kt`, `navigator/VisibleTextRangeDetector.kt` | commits completed pages/scroll ranges and fully-heard TTS sentences |
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
off mid-session stops capture for that session, and the session ends
`SKIPPED_INELIGIBLE/CONSENT_WITHDRAWN` with no text, even if consent is back on by
then. With consent off no script runs and nothing is appended.

**Withdrawal:** turning Cloud recaps off (`PreferencesRecapSettings`) runs
`withdrawText`: every queued row (`PENDING`, `FAILED_*`) loses its excerpt and last
sentence and becomes `FAILED_PERMANENT/CONSENT_WITHDRAWN`; a `RUNNING` row loses its
text but keeps its status; a `CAPTURING` row is marked so later appends are ignored.
Turning consent on again sends nothing captured before. If the purge fails, the next
app start repeats it while consent is off; abandoned sessions are then dropped too. Text is
appended **at the event**, never at close, because `close()` tears down the WebView.

| Source | When it counts | What is appended |
|---|---|---|
| Manual reading (`PAGE`) | normal adjacent forward turn, or text fully scrolled above view; foreground only, no startup prompt/audio; navigation jumps do not count | previously observed DOM ranges confirmed behind the new viewport |
| Device TTS (`TTS_SENTENCE`, Android) | `TtsController.finishedSentences`: the sentence's audio played from its start to its end (`TtsHeardSentenceTracker` follows the item the player really plays, not the engine's seek or skip target). Skips, seeks into a sentence, stops and unsynthesised sentences don't count | that sentence |
| Narration (media overlays) | automatic audio page turns do not count as manual reading | no page capture during playback; fully-heard device TTS has a separate source |

- **No chapter-end fallback.** `VisibleTextRangeDetector` returns null when no text is
  visible (image page, blank page). It reads the DOM without changing it. Offsets count
  all text outside script and style, so read-aloud sentence spans don't move them.
- **Dedupe:** manual pages and extracted TTS chunks share chapter character ranges.
  Legacy/controller sentences without coordinates retain the chapter+index fallback.
- **Unfinished text:** observing a page does not commit it. Closing discards visible
  candidates; asynchronous DOM results and locator/page mismatches are dropped.
  A normal quick forward turn counts without a five-second dwell. Navigation jumps
  discard candidates. Scroll capture commits only ranges fully above the viewport.
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
- **Recorder buffer:** appends are non-blocking and queued in order. New captures are
  bounded to 8,000 characters: a brief opening (up to 1,000) and the latest text
  verbatim, cut at word boundaries. Existing submitted payloads are not rewritten.
  A forward `PAGE` counts as an advance; each new `TTS_SENTENCE` as one sentence.
- **Saving:** bounded captures are persisted on every append. Session end saves only
  confirmed text, never the remaining visible page.
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
- A pass stops if the active profile changes after the claim (the database and token
  follow it): nothing is sent with the other profile's token, and a result that
  arrives after a switch is dropped; the row is recovered as stale later.
- `RUNNING` rows untouched for 3 min are reset to `PENDING` (process death), or to
  `FAILED_PERMANENT/MAX_ATTEMPTS` once they have used all attempts. The runner
  schedules a wake-up for that moment. A cancelled request (e.g. WorkManager stopped
  the worker) is stored as a retryable `NETWORK` failure with backoff.
- Backoff 1, 2, 4, 8 min … capped at 6 h; `Retry-After` wins when longer. After 5
  attempts → `FAILED_PERMANENT`, except that `RATE_LIMITED` and `SERVICE_UNAVAILABLE`
  never use up attempts (backoff and excerpt expiry still bound them).
- A retryable error or `AuthRequired` ends the pass and pauses the whole runner: no
  row is sent until that row's next attempt time (retryable) or an auth backoff of
  1, 2, 4 … min (`AuthRequired`). Sign-in or consent turned on lifts the auth pause.
  A 403 is a retryable `UNKNOWN`, not `AuthRequired`. The pause lives in memory.
- No engine available → rows stay `PENDING` (signed out). With consent off there
  are no queued rows: withdrawal dropped them.
- Triggers: app start, sign-in or consent turned on, session end, foreground and
  connectivity (`SyncTriggerBridge` / `RecapTriggerBridge`), user retry, scheduled
  retry. Android also runs `RecapWorker` (periodic 60 min plus one-off on background).
  iOS uses foreground triggers only.
- **User retry** (`RecapRepository.retry`): a `FAILED_PERMANENT` row that still has
  its excerpt and wasn't rejected for its input goes back to `PENDING` with attempts
  reset. A next attempt time still in the future (e.g. `Retry-After`) is kept, and the
  runner-wide pause still applies. `FAILED_RETRYABLE` rows retry on their own and
  can't be retried by hand. `SessionRecap.canRetry` mirrors these rules, so the UI
  only offers Retry when it works.

## UI contracts

| Contract | Use |
|---|---|
| `RecapRepository.observeRecap(sessionId)` | one session |
| `RecapRepository.observeLatestForBook(bookId)` | latest `SUCCEEDED`, else latest non-skipped ended session |
| `RecapRepository.observeHistory(bookId)` | history, newest first |
| `RecapRepository.retry(sessionId): RecapRetryResult` | explicit user retry |
| `RecapSettings.observeCloudRecapsEnabled()` / `setCloudRecapsEnabled()` | consent toggle |
| `RecapEngineSelector.observeAvailable()` | consent on **and** signed in; emits nothing while the session is still loading |

Observing never triggers generation. `SessionRecap` exposes no excerpt text.

- **Reader:** Last time lives in the toolbar and opens an Ember bottom sheet. The
  position gate remains `end ≤ current ≤ end + 0.02`. The latest unseen ready recap
  may open automatically after more than an hour (default), every entry, or never.
  Audio/startup prompts suppress automatic opening; writing recaps are manual only.
  Closing marks that session's recap seen using the existing pruned 1,000-entry FIFO, but
  keeps the manual pill available. No recap card sits over the text.
- **Statistics:** time/pages/end-position tiles, no WPM/model labels. States are
  static accessible messages, with only actionable write/retry/sign-in/consent
  buttons. Historical sessions without captured text cannot generate a recap.
- **Settings:** Reading → Recaps opens presentation/privacy settings. Enabling
  requires confirmed V2 consent and a signed-in allowlisted account. The full
  disclosure remains reachable unchanged. Withdrawal remains durable and possible
  while signed out. No recap surfaces appear for accounts without feature access.
- **Two-part output:** the provider returns `summary` and localized `stoppedAt`.
  Validated fields are stored/synced as two plain paragraphs, ≤600 characters total
  including their separator. Ordinary prose falls back to one paragraph. The
  existing schema and job fingerprints are unchanged.

## Long sessions (`generate-recap`)

Request bodies of ~1 MB+ hang or fail at the Edge gateway, so `CloudRecapEngine`
sends an excerpt whose JSON is over 180 KB as upload parts (one random upload id,
parts in order); the server stores all but the last in `recap_upload_parts`, and
the last part's request joins them and generates. A 409 (a part went missing)
is retryable and re-uploads. Only past 64 parts (~11M chars) are the oldest
parts left out, which changes nothing: the server reads at most 2M.
The function makes one model call for up to 250k chars. Longer excerpts are split
at line or sentence breaks into parts of up to 250k chars; each part gets a short
factual note (4 calls in parallel), then one merge call turns the notes, in order,
into the two-part recap with the same rules. Notes are never returned. One
recap uses one quota unit however many calls it needs.

Everything runs within a 135 s budget (the Edge gateway answers 504 at 150 s): each
call has a 60 s timeout, one retry only on 5xx/network and only with ≥ 10 s left,
and part calls stop 35 s early so the merge has time. Past 2M chars (~20 h of
reading) the function keeps the most recent 2M, which is about 9 parts, three map
rounds. Measured on hy3 (2026-10-02): 8k chars 3.2 s, 300k chars 11 s in one call,
a whole novel (690k chars, 3 parts) 14 s, and 2M chars (9 parts) 31 s. The client
waits up to 30 s per request (`CloudRecapEngine.REQUEST_TIMEOUT_MS`); a submission
whose response is lost is recovered by the lookup at the start of the next attempt,
so a short client timeout can delay a recap but never lose one.

## Offline engine extension point

`RecapEngine` is provider-neutral; each row stores the `engineId` and `model` that
produced it. To add an on-device engine:

1. Implement `RecapEngine` in reader data with its own `id` (e.g. `"local"`). It gets a
   `RecapInput` (the whole excerpt, language, lastSentence) and maps its own failures to
   `RecapResult` (`Retryable`/`Permanent`; never `AuthRequired`).
2. Add a separate setting for it in `RecapSettings` (do not reuse cloud consent).
3. Choose it in `DefaultRecapEngineSelector.select()` when cloud isn't usable, and
   include it in `observeAvailable()`.
4. Capture is gated on cloud consent in `ReaderViewModel`; widen that gate to "any
   recap engine enabled".

The runner, retention, eligibility and UI need no changes.

## Retention (`RecapJobRunner.runCleanup`, at app start, then at most hourly before a pass)

- The excerpt and last sentence are dropped as soon as a result is stored, when the
  server rejects the input, or at skip time (only a hash stays for the repeat check).
- Excerpts and last sentences older than 24 hours are nulled
  (`RecapJobPolicy.EXCERPT_RETENTION`); pending rows then become
  `FAILED_PERMANENT/EXCERPT_EXPIRED`.
- Rows older than 180 days are deleted, as are rows whose book is in neither `books`
  nor live `library_books`.
- Deleting a statistics session, or all of them, deletes the linked recaps in the same
  transaction. Recaps with no statistics row are kept until the rules above.
- Removing a book from the device deletes its recaps unless a server copy (`books`)
  still has the id. Library-book merges move rows; profile data clear deletes them.
- Banner dismissals live in preferences; entries whose recap no longer exists are
  pruned, and the list is capped at 1,000 ids.

## Privacy

The API receives only `excerpt`, `language` and `lastSentence`: no titles, ids or
chapter names. A long excerpt's earlier parts are held server-side in
`recap_upload_parts` (owner-only, no client table access) until the last part
arrives, then deleted; abandoned parts are deleted after an hour. Otherwise the
function stores none of it (only a per-user daily count).
Recaps are not part of Parrot Cloud sync or backup: sync is row-based
(`SyncOutboxEntry` types: library books, book links, positions, bookmarks, reader
settings, reading sessions without `recap_session_id`) and backups upload book
files only. There is no
`session_recap` table on the server. Recap rows live in the profile database,
which OS backups (Android Auto Backup, iCloud/device backup) still include; the
Android backup rules exclude secrets, models and book files, not databases.
Logs carry status and error codes only (`RecapDiagnostics`); `toString()` of inputs, results and capture
types omits the text. `recap_session_id` is not part of the synced statistics payload.
