# Goal: Samsung screen-by-screen QA, diagnostics and feature analytics

## Objective

Make every reachable Parrot screen and every user-facing action reliable on the Samsung phone connected through ADB. Work through one screen at a time: inventory its actions, add or repair useful diagnostic breadcrumbs and Firebase Analytics events, report unexpected experience-degrading failures to Firebase Crashlytics, execute its numbered manual tests on the phone, fix failures, and retest before signing off the screen.

Cover opening, closing, every form of Back, tabs, dialogs, sheets, menus, search, filtering, sorting, favourites, reading, listening, downloads, imports, offline use, statistics, profiles, settings, accounts, single-device backup/restore, and interruptions. Include slow operations, wrong or stale state, lost progress, silent failures and broken recovery—not only crashes. Analytics must show how users reach features, which features they actually use, where operations succeed, and where users abandon or cannot complete them.

Use [the numbered test catalogue](manual-qa-test-plan.md) as the execution checklist. Preserve existing case numbers; add new cases for newly discovered actions. This is the specification for a future implementation/testing pass, not a claim that instrumentation or device tests have already been completed.

## Boundaries and coverage rules

- Primary and required device: the connected physical Samsung Android phone. Record its actual model, Android version, One UI version, navigation mode and build; do not assume them.
- No cross-app or cross-device synchronisation verification. Do not change data in another app/device to create conflicts or compare propagation. Local persistence and local profile isolation remain in scope.
- Server authentication, fetching the library, downloads, cloud account actions and file backup/restore on this phone remain in scope. OAuth browser/app handshakes are permitted for authentication only.
- iOS, e-ink hardware and Android Auto are deferred for this pass. Headset/Bluetooth cases require the corresponding accessory. Record missing prerequisites rather than fabricating results.
- Inventory the installed build first. A Kotlin screen file does not prove that the screen is reachable. Authors routes are currently commented out in `HomeTab.kt` and `HomeDestination.kt`; Authors cases are conditional until navigation exposes them.
- Existing cases sometimes say “as designed,” “or,” or contain assumed UI labels. Before executing those cases, record the single intended outcome from current code/product behaviour. An unexplained silent no-op is not a passing outcome. Distinguish an outdated test from a real product defect.
- This catalogue is extensive, not proof of all possible combinations. Completion requires reconciling visible controls, gestures, accessibility actions, menu entries and screen/ViewModel intents against the catalogue. Add an ID for every uncovered reachable action.

## Repeat this workflow for each screen

1. **Inventory:** identify all entry points, exits, controls, overlays, system interactions, loading/empty/content/error states, and persisted state. Map each reachable intent/control to test IDs.
2. **Define outcomes:** specify success, cancellation, failure, retry and state restoration. Resolve ambiguous expected results before scoring.
3. **Audit instrumentation:** trace UI → ViewModel → use case/repository/service. Identify existing analytics and exception reporting so new logging does not duplicate them.
4. **Implement diagnostics:** add bounded breadcrumbs and operation context. Report unexpected failures that block, corrupt, lose or materially degrade the user experience. Add recovery outcomes and timings for long-running actions.
5. **Implement usage events:** screen exposure, entry source, feature use and committed action outcomes. Preserve existing event names where their semantics are correct.
6. **Verify locally:** run relevant existing tests/build checks for modified code, then install the intended build on the Samsung. Add targeted logic tests only where they verify substantive failure/state behaviour.
7. **Execute on-device:** happy paths first, then Back/cancel/repeated taps, lifecycle, offline/interruption, persistence, accessibility and failure/recovery cases. Keep case-level evidence.
8. **Verify Firebase:** check actual Analytics delivery and representative Crashlytics non-fatals for this build. A logcat line alone does not prove Firebase ingestion.
9. **Fix and retest:** rerun the failed case and adjacent navigation/state cases affected by the fix. Track unresolved blockers explicitly.
10. **Sign off:** record functional, analytics and diagnostics results separately. Move to the next screen once this screen is complete or its remaining blockers are documented.

## Instrumentation contract

### Three distinct signals

| Signal | Purpose | Examples |
|---|---|---|
| Diagnostic breadcrumbs | Explain the sequence leading to a problem; available in production reporting as well as local QA logs | Enter reader → open file → parse complete → prepare audio → playback stalled |
| Firebase Analytics | Measure navigation, adoption, completion and abandonment | Screen viewed, filter committed, playback started, backup completed, retry succeeded |
| Firebase Crashlytics non-fatal | Investigate unexpected user-impacting failures with stack/cause and context | Cannot open a valid downloaded book, position cannot be saved, playback engine fails, statistics query fails |

Do not throw an exception just to log an ordinary user action. Preserve real causes and cancellation semantics. Report a caught exception once at the boundary that knows the operation and user impact; lower layers may add context, but must not submit duplicate non-fatals. If a broken invariant has no exception, use a stable, descriptive diagnostic exception type only for that unexpected state.

Wrong credentials, invalid form input, picker cancellation, Back, cancelled downloads, denied permissions and expected offline conditions normally produce bounded breadcrumbs and an outcome event, not a Crashlytics issue for every occurrence. Still record the user-facing failure and recovery. Escalate when handling itself breaks: endless loading, unusable retry, lost local data, corrupt state or repeated failure after conditions recover.

### Shared context to add or standardise

- Stable `screen`, `source_screen`, `entry_point`, `action`, `operation`, `stage`, `outcome` and `reason_code` values.
- Build version/code, platform and app foreground/background state; use Firebase-provided device metadata rather than duplicating everything.
- Server **type**, media **type**, local/remote availability, network category, elapsed time and retry count when relevant.
- A per-operation correlation ID in diagnostics; capture context when work starts so later navigation/profile changes cannot misattribute a callback. Do not use high-cardinality operation IDs as feature-report dimensions.
- Non-sensitive local entity references only when essential for diagnosis. Avoid book titles/content, search text, preview text, bookmark names, profile names, email, passwords, tokens, full server URLs, raw deep links and file paths. Sanitize throwable messages/causes and request logs too.
- Sanitized reason codes and stable messages for grouping. Do not put changing book IDs or full payloads into exception messages.
- Breadcrumbs for operation start, meaningful stage changes, terminal outcome and recovery. Bound their volume; no per-byte transfer, per-word TTS, per-frame, per-scroll-pixel or per-keystroke reporting.
- Timing starts at the accepted user action and ends at usable content or terminal failure/cancellation. A timeout diagnostic must use a documented operation-specific threshold, not a universal arbitrary timeout.

### Event semantics

- One `screen_view` for an actual visible destination exposure, not every Compose recomposition. Define foreground re-entry consistently. Treat sheets/dialogs as named overlays rather than accidentally replacing the underlying screen.
- Navigation records source, destination and entry point: tab, card, toolbar, system Back, mini player, continue reading, notification or deep link. Do not infer navigation solely from button taps that may fail.
- Separate `started`/attempted from `succeeded`, `failed`, `cancelled` and `abandoned` for meaningful operations. “Book opened” means usable reader content, not just tapping Read. A closed screen does not imply cancellation of background work.
- Committed slider/seek changes produce one usage event per committed action; summarize high-frequency reading interactions by session/method where useful.
- Returning with Undo is recorded as a reversal, not another independent adoption/success. Automatic playback advance is distinguished from a user action.
- Retry has its own attempt and terminal outcome. Avoid counting automatic retries as additional users or feature adoption.
- Reuse the typed `AnalyticsEvent` hierarchy. Document new event names, allowed parameters, emission location and exactly-once boundary before adding them.
- Session summaries distinguish active reading, audio playing, paused, background and screen-off time. Screen dwell alone is not reading time or successful feature use.
- Validate event schemas/types and registration of reporting dimensions against the Firebase SDK/project used by the tested build. DebugView is delivery evidence; production usage reports must exclude QA traffic by an agreed build/environment policy.

### Repository starting points and known gaps

| Location | What to inspect |
|---|---|
| `lib/analytics/api/.../Analytics.kt` | Existing `logEvent`, `logException`, `setUserId` contract; no dedicated breadcrumb/context API yet |
| `lib/analytics/api/.../AnalyticsEvent.kt` | Existing reader, bookmarks, narration, settings and TTS events |
| `lib/analytics/api/.../BookAnalyticsEvent.kt` | Existing library, auth, navigation, servers, profiles and statistics events |
| `lib/analytics/implementation/.../AnalyticsManager.kt` | Firebase forwarding and preference-gated local exception logging |
| `lib/analytics/implementation/.../DebugAnalyticsManager.kt` | Local logging only; this provider does not forward events/non-fatals to Firebase |
| `lib/analytics/implementation/.../di/AnalyticsModule.kt` | `isDebug` selects local-only debug provider versus Firebase provider |
| `base/.../repository/BaseRepository.kt` and `base/.../result/AppResult.kt` | Existing exception-reporting boundaries; audit duplicate reports before adding UI reports |

Paths with `...` abbreviate the module's Kotlin source/package path. These are audit entry points, not a completed instrumentation audit. Existing event payloads include free-form values such as `profile_name` and `error_message`; review and sanitize them as part of the corresponding screen pass.

**Important build requirement:** use a Firebase-enabled release-like QA build or an explicitly configured QA provider when verifying delivery. Setting an ADB Firebase debug property does not turn `DebugAnalyticsManager` into a Firebase sender. Preserve useful local debugging while verifying the production reporting path.

### Per-screen instrumentation checklist

Every row also inherits screen entry/exit, back/dismiss source, loading duration, terminal outcomes and retry coverage from the shared contract. Items below are required behaviours; names not already present in code are proposed semantics, not claims of existing events.

| Screen/surface | Usage signals | Experience degradation to diagnose/report |
|---|---|---|
| Splash/startup | Launch source, warm/cold route, usable-screen timing | Bootstrap/database failure, invalid restored route, stuck splash, fallback from missing last book |
| Welcome | Welcome viewed, get started, guest chosen | Tap with no navigation, failed guest initialisation, repeated onboarding loop |
| Server login | Server type, credentials/OAuth method, submit/outcome, cancel/retry | Unhandled auth response, callback mismatch, transport failure outcome, stuck loading, account not persisted |
| Home/tab navigation | Tab switched, route transitions, Back, continue-reading entry | Duplicate routes, wrong stack, wrong profile/book target, restoration failure |
| Books library | Search opened/committed with count bucket, filters, sort, layout, book selected, refresh | Query/refresh failure, stale state, lost cache, favourite rollback, materially slow content |
| Filter/sort sheets | Open, selection, reset, dismiss, result count bucket | Applied UI and query disagree, impossible reset, lost selection |
| Book detail | Format action, favourite, metadata expansion, series entry, delete confirm/cancel, backup action | Missing/invalid record, wrong format opened, mutation failure, cache/file mismatch, unavailable primary action |
| Series list/detail | Series selected, refresh, search, book opened, favourite | Group/order/load failure, stale membership, wrong book target |
| Authors list/detail, if exposed | Author selected, refresh, book opened | Wrong author membership, failed query, stuck list, wrong book target |
| Reader | Open/close outcome, navigation method summaries, chapter jumps, audio-only switch | Parse/render failure, blank content, lost/wrong position, save failure, repeated severe page-turn stalls |
| TOC | Open/dismiss, jump method, success, undo | Invalid anchor, wrong chapter, failed restoration |
| Bookmarks | Open, add/rename/delete/reorder/jump/undo outcomes | Persistence failure, duplicate/corrupt anchors, wrong jump, lost order |
| Reader settings | Open/source, section, committed setting/value enum or bucket, undo, font import | Setting save/apply failure, layout/position loss, unusable font, lost custom font |
| ReadAloud/audio-only | Playback start/pause/stop, seek/skip, chapter, speed, mode switch | Missing overlays, audio/text drift, failed preparation, stalled audio, lost control/state |
| Sleep timer | Open, preset/custom, start/cancel/postpone, automatic expiry | Timer fails to stop audio, duplicate expiry, wrong remaining time after resume |
| Voice settings | Source, select, preview/stop, download/delete outcome, terms accept/cancel, rate/pitch | Model/engine load failure, silent output, corrupt model, failed cleanup, stuck preparation |
| Audiobook player | Play/pause, seek, skip/track, speed, track sheet, close | Buffering stall, engine/track error, lost offset, failed auto-advance, wrong duration |
| Mini player/notification/lock screen | Command and source, open target, stop | Stale metadata, ignored commands, orphan service, wrong target, duplicate sessions |
| Downloads/imports | Start, meaningful phase, success/failure/cancel/retry, source, type, size bucket, duration | Truncated/corrupt file, storage/write failure, phantom readiness, stranded progress, leaked partial files |
| Cloud account/file backup | Auth outcome, backup enable, attestation, transfer/restore/delete outcome | Failed account persistence, quota/load failure, unusable restored file, incorrect local transfer state; no sync verification |
| Statistics and each detail sheet | View/source, period/detail type, refresh, supported row navigation | Failed aggregation/session save, negative/duplicate time, stale totals, profile leakage, broken date boundaries |
| Profiles/dialogs | Open, create/rename/switch/delete confirm/cancel and outcome | Partial switch, local data leakage/loss, failed persistence, callback written into wrong profile |
| App settings/log sharing | Row opened, toggle committed, clear-current-book, logs share/clear outcome | Unpersisted preferences, wrong visibility state, failed export/clear, inaccessible share file |
| Server management | Add/login/logout/remove confirm/cancel/outcome | Stale connection state, invalid session handling, wrong source removed, active work left orphaned |
| External callbacks/lifecycle | Callback category/outcome, foreground restoration, interruption/recovery | Invalid target, duplicate handling, startup crash loop, process-death recovery failure |

## Samsung/ADB execution procedure

Record all commands, chosen serial and installed package in the run manifest. Resolve values from the actual device/build; placeholders below must not be pasted unchanged. Always target the Samsung explicitly when multiple devices exist.

```sh
adb devices -l
export SERIAL='<Samsung serial from adb devices>'
export APP_ID='<installed application ID of the tested build>'
adb -s "$SERIAL" shell getprop ro.product.model
adb -s "$SERIAL" shell getprop ro.build.version.release
adb -s "$SERIAL" shell getprop ro.build.version.sdk
adb -s "$SERIAL" shell dumpsys package "$APP_ID"
adb -s "$SERIAL" logcat -v threadtime > samsung-qa-logcat.txt
```

Run continuous logcat in a separate terminal/process; stop it when the capture ends. Capture around each failed case, including events before failure. Record One UI version, display/font settings, notification permission, battery mode, network, free space and active profile in the manifest. Capture a screenshot/screen recording for UI defects and an Android bugreport for ANRs or platform/service failures when needed. Store artifacts under a run-specific local directory; link sanitized excerpts in the QA report.

For a Firebase-enabled build, enable Analytics debug delivery for the tested package, then disable it after testing:

```sh
adb -s "$SERIAL" shell setprop debug.firebase.analytics.app "$APP_ID"
# Perform labelled test actions and verify the chosen device in Firebase DebugView.
adb -s "$SERIAL" shell setprop debug.firebase.analytics.app .none.
```

Crashlytics delivery is verified separately using a controlled, handled failure with a known operation/time/build. Relaunch when required by report submission behaviour and allow for ingestion delay. Check issue context and symbolicated stack, not only issue existence. Any fatal-crash probe belongs in a disposable QA session with saved work, never as a production user control.

Distinguish Home/background, swipe away from Recents, background process kill, force-stop and relaunch. They have different Android service/restart semantics. `force-stop` is not a simulation of ordinary background process death. Record exactly which interruption was used; do not expect playback or workers to continue after force-stop.

Use controlled test files/profiles/accounts for deletion, corruption and storage pressure. Keep enough device space for evidence and OS operation; prefer constrained test allocation/fault injection over exhausting the phone. If a failure cannot be induced via UI/ADB, record the required fault-injection fixture and mark BLOCKED until available. Restore network, permissions, battery settings and fixture state after each perturbation.

## Screen execution order and case mapping

Run the baseline and extension IDs together for each screen. Shared lifecycle/accessibility/observability cases must be instantiated per screen, not ticked once for the entire app.

| Order | Screen group | Baseline cases | Extension cases |
|---|---|---|---|
| A | Device/build/reporting setup | Setup prerequisites | 495–514 |
| B | Startup, Welcome, login | 1–36 | 515–534 |
| C | Home/navigation and Books | 37–84, 469–470 | 535–564, 754 |
| D | Book detail and transfers/import | 85–124, 327–356 | 565–579, 675–694 |
| E | Series; conditional Authors | 125–137 | 580–594 |
| F | Reader, TOC, bookmarks | 138–184 | 595–624 |
| G | Reader settings | 185–231 | 625–639 |
| H | ReadAloud, sleep timer, voices | 232–289 | 640–659 |
| I | Audiobook, mini player, media service | 290–326 | 660–674 |
| J | Statistics | 385–407 | 695–709 |
| K | App settings, profiles, servers | 408–452 | 710–729 |
| L | Cloud account and single-device backup | 357–384, with sync deferrals | 730–739 |
| M | Cross-cutting lifecycle/polish/reporting | 453–494, with sync deferrals | 740–750; repeat shared checks throughout B–L |

## Evidence and reporting

Create one run manifest and a result ledger. No cases start as PASS. Use NOT RUN, PASS, FAIL, BLOCKED, N-A or DEFERRED; record a reason for the last three. An unavailable required fixture is BLOCKED, not N-A. Deferred platform/sync cases do not count toward this pass's executed coverage.

```text
Run: <date/build/commit/device>
Case: <number>/<screen>/<variant, e.g. toolbar-back or system-back>
Preconditions/fixture:
Steps actually performed:
Expected / actual:
Functional: NOT RUN | PASS | FAIL | BLOCKED | N-A | DEFERRED
Analytics: expected names/count/source/outcome; observed; evidence or blocker
Diagnostics: expected breadcrumbs/non-fatal classification; observed; evidence
Artifacts: screenshot/video/log timestamps/Firebase issue or DebugView reference
Defect: ID, severity, reproducibility, user impact, fix commit
Retest: build/date/result and adjacent regression IDs
```

For each screen produce an action-to-test inventory, event dictionary, diagnostic failure map and execution summary. Track open defects by impact: crash/data loss; blocked core feature; degraded/recoverable behaviour; visual/wording defect. A working feature with missing required analytics or diagnostics remains instrumentation-incomplete.

The final usage summary must make it possible to answer: Which screens lead to successful reading/listening? Which features have the most users and repeat usage? Which settings are changed? Which download/import/voice operations fail or get abandoned? Which statistics sheets are useful? Which recovery paths work? Use distinct users/sessions and success denominators, not raw tap volume alone.

## Completion criteria

- Every reachable screen/action is mapped to numbered tests and exercised on the recorded Samsung build, including all applicable Back/dismiss/lifecycle variants.
- All applicable tests have evidence-backed results; no required case remains NOT RUN. Blocked/failed cases prevent an unqualified completion claim. N-A/deferred exclusions are documented.
- No unresolved crash, ANR, local data loss, profile leakage or blocked reading/listening/download flow remains. Other known defects have explicit disposition.
- Each screen has verified navigation/feature events and useful bounded diagnostics for its user-impacting failures. Representative non-fatals are visible in Firebase with actionable context and stack traces.
- Instrumentation does not change cancellation behaviour, multiply reports on recomposition, expose private content, noticeably slow the app or turn transient connectivity into an exception flood.
- Full applicable screen pass and final regression smoke pass succeed on the tested build. Report test counts by status, instrumentation gaps, remaining defects, device/build identity and evidence locations.

**Deliverables:** instrumentation/fixes in the app, updated numbered catalogue, run manifest, per-case result ledger, per-screen event/failure maps, Firebase evidence, and a concise final QA report. Creating these planning documents alone does not satisfy the execution goal.
