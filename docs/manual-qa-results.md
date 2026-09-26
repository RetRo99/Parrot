# Parrot manual QA results

Run manifest: [manual-qa-run-report.md](manual-qa-run-report.md). All cases begin NOT RUN; results below are updated only after execution/evidence. Functional, analytics and diagnostics are tracked separately. Cross-app/cross-device sync cases are deferred per the test plan overrides.

## A — Samsung setup and reporting readiness (495–514)

| Case | Variant / evidence | Functional | Analytics | Diagnostics | Notes / defect |
|---:|---|---|---|---|---|
| 495 | Physical Samsung selected; explicit ADB serial `RFCWC0SSVDM` | PASS | N-A (setup) | PASS (device identity recorded) | `adb devices -l` showed Samsung serial/model; all property/package commands used `adb -s RFCWC0SSVDM`. |
| 496 | Installed package/build identity | PASS | N-A (setup) | PASS (package/hash verified) | After QA-BUG-0005 commit `d1e18128`, rebuilt and reinstalled `com.retro99.parrot` 0.4.5 (21); installed and local APK SHA-256 both `b1f6d3ed57189df9cafb001a154c490526078687150331e479bd3680c080595e`. See case-503 retest5 evidence. |
| 497 | Device baseline | BLOCKED | N-A (setup) | BLOCKED | Android/One UI, model, display, navigation, font scale, battery saver, network, notification permission and free space recorded. Active profile and full settings/permission baseline remain unknown. |
| 498 | Guest fixture readiness | BLOCKED | N-A (setup) | BLOCKED | No controlled guest profile + valid local EPUB fixture identified. |
| 499 | Mixed-media fixture readiness | BLOCKED | N-A (setup) | BLOCKED | No reproducibly identified mixed-media/server library fixture. |
| 500 | Long-content fixtures | BLOCKED | N-A (setup) | BLOCKED | Long-title/description, large EPUB and many-track fixtures unavailable/not identified. |
| 501 | Failure fixtures | BLOCKED | N-A (setup) | BLOCKED | Corrupt EPUB, controlled unavailable server and safe constrained-storage fixture unavailable/not identified. |
| 502 | Labelled run capture | NOT RUN | NOT RUN | BLOCKED | Timestamp-filtered logcat excerpt captured for case 503, but continuous capture was not started. |
| 503 | Debug-provider tracked action | PASS | PASS (local only) | PASS (sanitized provider) | Baseline had 19 sanitized exceptions; retest 1 had six; retest 2 had zero markers/nine events; retest 3 was invalid due to stale unscoped logcat buffer and is excluded; retests 4 and 5 used a fresh PID and bounded 20-second captures: each showed two events, zero markers; retest 5 is post-commit. See case-503 evidence files. Not Firebase evidence. |
| 504 | Firebase-provider verification | BLOCKED | DEFERRED (user waiver) | BLOCKED | User accepted local debug event visibility instead of Firebase Analytics ingestion; Firebase delivery is not claimed. Crashlytics verification remains blocked. |
| 505 | Analytics DebugView delivery | DEFERRED | DEFERRED (user waiver) | N-A (Analytics case) | User accepted debug-provider evidence for event behavior; DebugView not inspected and Firebase delivery is not claimed. |
| 506 | Controlled non-fatal delivery | BLOCKED | N-A (Crashlytics case) | BLOCKED | Controlled fixture and Crashlytics Console evidence unavailable; no non-fatal was deliberately submitted. |
| 507 | Optimized-build symbolication | BLOCKED | N-A (Crashlytics case) | BLOCKED | Release mapping generated; no uploaded/symbolicated controlled issue verified. |
| 508 | Breadcrumb correlation | BLOCKED | N-A (diagnostic case) | BLOCKED | No labelled Books → Detail → Reader controlled failure run. |
| 509 | Sensitive-data inspection | BLOCKED | BLOCKED (distinctive payload exercise not run) | BLOCKED (Crashlytics delivery inspection) | QA-BUG-0001 provider allowlist and QA-BUG-0002 diagnostic sanitizer pass locally. QA-BUG-0003 identity derivation was removed and startup clear observed on Samsung; successful Login payload and distinctive text are not yet exercised. Firebase Analytics ingestion is waived. |
| 510 | Exception deduplication | NOT RUN | N-A (diagnostic case) | BLOCKED (Firebase/non-fatal runtime count unavailable) | QA-BUG-0004 duplicate reporters were removed in `d97de1b8` and `51846059`; forced Login/authenticator tests/build pass. Samsung wrong-credential/transport run and Crashlytics count are still pending/blocked. |
| 511 | QA traffic classification | BLOCKED | BLOCKED | N-A (reporting policy case) | No agreed QA exclusion policy or build/environment marker found in the inspected reporting path; Firebase runtime remains unverified. |
| 512 | Offline telemetry resilience | NOT RUN | NOT RUN | NOT RUN | Network was online; no offline telemetry exercise. |
| 513 | Logging overhead | NOT RUN | NOT RUN | NOT RUN | No comparative timed/scroll/playback test. |
| 514 | End-of-run cleanup | NOT RUN | N-A (setup) | NOT RUN | Firebase debug property not changed; final restoration audit pending. |

## B — App launch & onboarding

Source audit identified QA-BUG-0006 (still open/partially remediated) and QA-BUG-0007 (fixed and Samsung-ret-tested). Welcome instrumentation QA-BUG-0010 is committed; cases 1–5 have exercised startup/visible route and entry outcomes. QA-BUG-0011's debug-badge fix and QA-BUG-0020's release-badge fix are committed and retested on the Samsung. Cases 8–14 remain individually deferred or not run as stated.

| Case | Variant / evidence | Functional | Analytics | Diagnostics | Notes / defect |
|---:|---|---|---|---|---|
| 1 | Cold start on first install; debug APK, no permissions granted | PASS | PASS (local debug provider only) | PASS (local breadcrumbs; no failure induced) | Parrot alone was uninstalled/reinstalled under the user's authorization. Welcome displayed Get Started and Browse without account; `POST_NOTIFICATIONS` remained denied and no runtime permission was granted. One `app_launch_route_resolved`, one `welcome_screen_viewed`, and startup/visible breadcrumbs appeared in PID-scoped local logs. APK hash matched local/installed `059d4d…7620b`, source commit `eead1977`. A UI hierarchy helper timed out because it could not reach Compose idle, but the screenshot visually confirms the labels; not treated as an app failure. The initial screenshot also exposed QA-BUG-0011 (badge missing), fixed and retested separately. Evidence: [run record](manual-qa-evidence/2026-09-26/case-001-first-install-run.txt), [Welcome screenshot](manual-qa-evidence/2026-09-26/case-001-first-install-welcome.png). Firebase delivery is not claimed. |
| 2 | Cold start when already signed in | PASS | PASS (local debug provider only) | PASS (local route breadcrumbs; no failure induced) | Signed in through Parrot UI, force-stopped only Parrot, and relaunched on the Samsung. Startup resolved to Home/Books with one `app_launch_route_resolved` and one `home_screen_viewed` (`source_screen=splash`, `entry_point=app_launch`); zero Welcome/Login exposure events and no fatal marker. Home tab labels were present in the post-relaunch hierarchy. Login success and one Home exposure (`source_screen=login`, `entry_point=login_success`) were observed in the case-2 setup journey. Firebase ingestion is waived and not claimed. [Case-2 run evidence](manual-qa-evidence/2026-09-26/case-002-cold-start-signed-in-run.txt). |
| 3 | Cold start in guest mode | PASS | PASS (local debug provider only) | PASS (local breadcrumbs; no failure induced) | Parrot-only data reset; chose guest mode, imported the controlled local Pride and Prejudice EPUB, removed its temporary Downloads source, force-stopped/relaunched Parrot and verified direct Home routing plus the same local book. Exactly one cold-start route and Home exposure; no Welcome/Login exposure. Firebase delivery is not claimed. [Run evidence and screenshots](manual-qa-evidence/2026-09-26/case-003-guest-cold-start-run.txt). |
| 4 | Get Started | PASS | PASS (local debug provider only) | PASS (local breadcrumb; no failure induced) | Tapped once as the required Login route precondition for case 2. Login appeared with server-type dropdown; one attempt, completion and Login exposure event plus one route breadcrumb observed. No credentials entered. Evidence: [run record](manual-qa-evidence/2026-09-26/case-004-get-started-run.txt), [Login screenshot](manual-qa-evidence/2026-09-26/case-004-get-started-login.png). Firebase Analytics delivery is not claimed. |
| 5 | Browse without account; local-only Home and import | PASS (shared case-3 journey) | PASS (local debug provider only) | PASS (local breadcrumbs; no failure induced) | Browse without account opened an empty local Home; a single local EPUB import then succeeded with no remote account/server books. Exact UI outcome, guest event counts and destination attribution are recorded in [shared case-3/5 run evidence](manual-qa-evidence/2026-09-26/case-003-guest-cold-start-run.txt). This is one shared journey, not an independent repeat. Firebase delivery is not claimed. |
| 6 | Welcome toolbar back-arrow availability | N-A | N-A | N-A | Samsung Welcome screenshot confirms there is no toolbar Back arrow on the reachable root route; source only shows the arrow when `onBack` is non-null, and root Welcome receives null. System Back is a separate case (522/756), not counted as this unavailable control. Evidence: [case-006 availability run](manual-qa-evidence/2026-09-26/case-006-welcome-arrow-availability-run.txt). |
| 7 | Build badge — debug variant | PASS (retested after QA-BUG-0020 fix) | N-A (static build marker) | N-A (static build marker) | Earlier QA-BUG-0011 failure/fix evidence is retained. On the same final fix build used for the release retest, the hash-matched Samsung debug APK `eff1d633…98178fbbe` visibly showed DEBUG and no RELEASE. Evidence: [fixed debug screenshot](manual-qa-evidence/2026-09-26/case-007-debug-welcome-retest.png) and [shared case-7 retest](manual-qa-evidence/2026-09-26/case-007-build-badge-retest.txt). |
| 7 | Build badge — release variant | PASS (retested after QA-BUG-0020 fix) | N-A (static build marker; Firebase event delivery not checked) | N-A (no user-impacting runtime failure) | Pre-fix Samsung release build hash `5f16d8e9…aa88342b` showed neither badge; that FAIL is retained in [pre-fix case-7 evidence](manual-qa-evidence/2026-09-26/case-007-release-badge-run.txt) and QA-BUG-0020 history. Fixed release APK hash `d2e97bc8…f09480f2` is hash-matched locally/on Samsung and visibly shows RELEASE without DEBUG. Evidence: [fixed release screenshot](manual-qa-evidence/2026-09-26/case-007-release-welcome-retest.png) and [shared case-7 retest](manual-qa-evidence/2026-09-26/case-007-build-badge-retest.txt). Release Crashlytics settings request still failed to reach its configured host; no Firebase delivery claim is made. |
| 8 | E-ink theming | DEFERRED | N-A (platform deferred) | DEFERRED | No e-ink hardware for this pass; per test-plan scope override. [Disposition](manual-qa-evidence/2026-09-26/case-008-eink-deferred.txt). |
| 9 | Rotation on Welcome | NOT RUN | NOT RUN | NOT RUN | Previous preservation-based BLOCKED disposition withdrawn; no rotation performed yet. Historical note: [case-009](manual-qa-evidence/2026-09-26/case-009-welcome-rotation-blocked.txt). |
| 10 | Background and resume on Welcome | NOT RUN | NOT RUN | NOT RUN | Previous preservation-based BLOCKED disposition withdrawn; no background/resume interaction performed yet. Historical note: [case-010](manual-qa-evidence/2026-09-26/case-010-welcome-resume-blocked.txt). |
| 11 | Open last book on launch (enabled) | NOT RUN | NOT RUN | NOT RUN | Previous preservation-based BLOCKED disposition withdrawn; create/import a controlled local EPUB and saved position before testing. Historical note: [case-011](manual-qa-evidence/2026-09-26/case-011-last-book-launch-blocked.txt). |
| 12 | Open last book on launch (no current book) | NOT RUN | NOT RUN | NOT RUN | Previous preservation-based BLOCKED disposition withdrawn; prepare a no-current-book fixture before testing. Historical note: [case-012](manual-qa-evidence/2026-09-26/case-012-last-book-no-current-blocked.txt). |
| 13 | Locale rendering (Spanish device locale) | BLOCKED (partial: reachable Books, Series, Statistics and Settings surfaces rendered English; other routes were not reached) | PASS after QA-BUG-0008 fix (first run exposed missing startup event; retest emitted one `app_launch_route_resolved` event on launch; local provider only) | NOT RUN (no induced UX failure; relaunch showed no fatal exception in the PID-scoped check; actionable Crashlytics delivery/context not verified, QA-BUG-0006 remains open) | Locale changed from `en-GB` to `es-ES`, app force-stopped/launched, four reachable tabs inspected and screenshots saved; locale restored to `en-GB`. Retest build `0.4.5` (21), APK SHA-256 `b2045002…882f4`. Firebase Analytics ingestion is waived, not claimed. [Run evidence](manual-qa-evidence/2026-09-26/case-013-locale-run.txt); [instrumentation retest](manual-qa-evidence/2026-09-26/case-013-launch-event-retest.txt). |

### Welcome/startup action and instrumentation map

| Reachable entry/action/exit | Test IDs | Existing/required usage event | Diagnostic/failure coverage |
|---|---:|---|---|
| Splash cold launch → Welcome; visible exposure | 1, 3, 14, 515–520 | `app_launch_route_resolved`, then one `welcome_screen_viewed` with bounded source/entry point | Startup start/route-selected breadcrumbs; unexpected auth-state-read failure handled by QA-BUG-0009. App lifecycle/route restoration still needs its mapped cases. |
| Get Started → Login | 4, 15–36, 521, 523–534, 751–753 | `welcome_action_attempted`, completion only on Login visibility, then `login_screen_viewed` | Bounded route start/visible breadcrumbs. Auth and persistence failures mapped to Login/QA-BUG-0015; case 753 has no safe Samsung write-fault fixture. |
| Browse without account → Home | 3, 5, 11–12, 521, 754–755 | Guest attempt and persisted-state completion, followed by one actual `home_screen_viewed` with `welcome` / `browse_without_account` attribution | Start/persisted/visible breadcrumbs. Preference-write failure was identified as QA-BUG-0017; case 755 covers failure and recovery. |
| Root Welcome Back; conditional toolbar Back from nested Login | 6, 28, 522–523, 756 | Case 6 toolbar arrow is N-A on root Welcome. Case 522/756 PASS with one bounded `navigation_back` outcome (`welcome` → `app_exit`, `system_back`, `exited`), actual clean exit/relaunch and one breadcrumb pair. Nested Login Back remains NOT RUN under case 28/523. | Ordinary Back creates no non-fatal. QA-BUG-0018 final-entry guard/root handler and QA-BUG-0019 Back telemetry are fixed and Samsung-retested. Evidence: [case-522/756 run](manual-qa-evidence/2026-09-26/case-522-756-welcome-system-back-run.txt). |
| Repeated Welcome actions / foreground return | 10, 515–516, 521, 740–741, 747 | One accepted attempt and one committed terminal outcome per action; screen exposure follows actual visibility | Deduplication/lifecycle restoration not yet verified; avoid interpreting Compose recomposition as a new exposure. |

Welcome event names and safe dimensions: `welcome_screen_viewed(screen, source_screen, entry_point)`, `welcome_action_attempted(screen, action, outcome=started)`, `welcome_action_completed(screen, action, outcome)`; guest Home entry then emits `home_screen_viewed(screen, source_screen, entry_point)`. Local debug-provider observations are recorded separately from Firebase delivery. A QA-BUG-0017 exception is unexpected only when persistence itself throws; ordinary Back, validation and repeated taps must not create Crashlytics noise.

Cases 14–36 and Login extensions 515–534, 751–753 (not yet fully exercised; case 22 sign-in was a case-2 precondition only):

| Case | Variant / evidence | Functional | Analytics | Diagnostics | Notes / defect |
|---:|---|---|---|---|---|
| 14 | Offline cold start | NOT RUN | NOT RUN | NOT RUN | Network currently appears online; no offline cold launch performed. |
| 15 | Server-type dropdown options | NOT RUN | NOT RUN | NOT RUN | Source says Storyteller and Audiobookshelf only; Samsung check pending. |
| 16 | URL help tooltip | NOT RUN | NOT RUN | NOT RUN | Help icon and tooltip dismissal not exercised. |
| 17 | Invalid URL validation | NOT RUN | NOT RUN | NOT RUN | No invalid URL submitted. |
| 18 | Empty submit validation | NOT RUN | NOT RUN | NOT RUN | No empty-form interaction performed. |
| 19 | Password masking | NOT RUN | NOT RUN | NOT RUN | Password field not exercised. |
| 20 | Show/hide password | NOT RUN | NOT RUN | NOT RUN | Visibility toggle not exercised. |
| 21 | IME Sign-in action | NOT RUN | NOT RUN | NOT RUN | Valid-form submit via keyboard not exercised. |
| 22 | Successful sign-in (credentials) | NOT RUN | NOT RUN | NOT RUN | A successful credentials sign-in was performed as case 2's required precondition: one attempt/success and one Login→Home exposure were observed on the local debug provider. Case 22 remains NOT RUN as a full case because Server Management's logged-in card and remaining applicable checks have not been verified. QA-BUG-0003 identity clear and QA-BUG-0013 normal success branch remain to verify on-device. |
| 23 | Wrong credentials | NOT RUN | NOT RUN | NOT RUN | No rejection attempted yet. QA-BUG-0004 reporter centralization is source/test verified; Samsung outcome and diagnostics path remain pending. |
| 24 | Network error on sign-in | NOT RUN | NOT RUN | NOT RUN | Offline submit/recovery not exercised. |
| 25 | OAuth happy path | NOT RUN | NOT RUN | NOT RUN | Browser/app handoff not exercised. |
| 26 | OAuth cancelled | NOT RUN | NOT RUN | NOT RUN | Cancellation path not exercised. |
| 27 | OAuth while backgrounded | NOT RUN | NOT RUN | NOT RUN | App lifecycle interruption not exercised. |
| 28 | Back from Login | NOT RUN | NOT RUN | NOT RUN | Toolbar/system Back variants pending. |
| 29 | Rotation during login | NOT RUN | NOT RUN | NOT RUN | No form state/rotation check. |
| 30 | Session expired | NOT RUN | NOT RUN | NOT RUN | Requires signed-in session and controlled expiry; not attempted. |
| 31 | Storyteller OAuth deep-link callback | NOT RUN | NOT RUN | NOT RUN | Callback not exercised. |
| 32 | Google auth callback | NOT RUN | NOT RUN | NOT RUN | Callback not exercised. |
| 33 | Add a second server account | NOT RUN | NOT RUN | NOT RUN | No account addition performed. |
| 34 | URL variants | NOT RUN | NOT RUN | NOT RUN | Normalization not tested. |
| 35 | Keyboard overlap | NOT RUN | NOT RUN | NOT RUN | Small-screen/IME coverage not run. |
| 36 | Slow sign-in response | NOT RUN | NOT RUN | NOT RUN | No delayed endpoint/duplicate-submit check. |
| 515 | Warm launcher return | NOT RUN | NOT RUN | NOT RUN | Welcome route not backgrounded/reopened. |
| 516 | Launcher rapid taps | NOT RUN | NOT RUN | NOT RUN | Startup duplicate-launch behavior not tested. |
| 517 | Upgrade with local data | NOT RUN | NOT RUN | NOT RUN | No fixture-preserving upgrade run. |
| 518 | Missing last-book file at startup | NOT RUN | NOT RUN | NOT RUN | No reopen-last-book fixture or missing-file injection. |
| 519 | Deleted last-book reference | NOT RUN | NOT RUN | NOT RUN | No last-book deletion/relaunch run. |
| 520 | Back during startup | NOT RUN | NOT RUN | NOT RUN | No startup Back action performed. |
| 521 | Repeated Welcome action | NOT RUN | NOT RUN | NOT RUN | Rapid Get Started/guest taps pending. |
| 522 | Welcome root system Back and relaunch | PASS | PASS (local debug provider only) | PASS (local breadcrumbs; no failure induced) | On final hash-matched build, system Back finished Parrot cleanly, emitted exactly one bounded Back outcome event plus start/completion breadcrumbs, and a relaunch returned to usable Welcome. No Home exposure or fatal marker. An earlier intermediate build exited cleanly but omitted Back instrumentation; this led to QA-BUG-0019 and was retested after the explicit root handler. Shared evidence: [case-522/756 run](manual-qa-evidence/2026-09-26/case-522-756-welcome-system-back-run.txt). Firebase Analytics ingestion is waived and not claimed. |
| 523 | Login keyboard Back | NOT RUN | NOT RUN | NOT RUN | IME dismissal and subsequent navigation Back pending. |
| 524 | Login from Add Server Back | NOT RUN | NOT RUN | NOT RUN | Return-to-Servers path not exercised. |
| 525 | Login editing after rejection | NOT RUN | NOT RUN | NOT RUN | Rejection/edit/retry not exercised. |
| 526 | Login submit race | NOT RUN | NOT RUN | NOT RUN | Atomic single-flight gate is committed (`f8de3890`) and Login regression tests/debug build pass; no Samsung rapid submit or controlled delayed endpoint run yet. QA-BUG-0014 remains suspected pending device retest. |
| 527 | Login Back while loading | NOT RUN | NOT RUN | NOT RUN | No pending request/leave/late-callback test. |
| 528 | Switch server type after input | NOT RUN | NOT RUN | NOT RUN | Input retention/request type not tested. |
| 529 | Clipboard and whitespace | NOT RUN | NOT RUN | NOT RUN | No whitespace/clipboard interaction. |
| 530 | Untrusted/expired TLS | NOT RUN | NOT RUN | NOT RUN | Controlled TLS endpoint unavailable. |
| 531 | Wrong endpoint response | NOT RUN | NOT RUN | NOT RUN | Malformed response fixture unavailable. |
| 532 | OAuth callback replay | NOT RUN | NOT RUN | NOT RUN | Callback replay fixture not run. |
| 533 | OAuth stale/mismatched callback | NOT RUN | NOT RUN | NOT RUN | Stale callback fixture not run. |
| 534 | Authentication event semantics | NOT RUN | NOT RUN | NOT RUN | Auth attempt/success/failure/cancel/abandon schemas are implemented and sanitizer-tested, but no Samsung outcome sequence has been exercised. QA-BUG-0015 stage mapping is fixed in `befb812c`; the failure-path device assertion remains blocked with case 753. |
| 751 | Server-type dropdown dismissals | NOT RUN | NOT RUN | NOT RUN | Outside-tap/system-Back variants pending; appended to catalogue. |
| 752 | URL help tooltip dismissals | NOT RUN | NOT RUN | NOT RUN | Button/outside/system-Back variants pending; appended to catalogue. |
| 753 | Server/credential persistence failure | BLOCKED | BLOCKED | BLOCKED | QA-BUG-0013 implementation/tests (`dc005fa5`) and QA-BUG-0015 diagnostic mapping fix/tests (`befb812c`) are committed. No safe registry/preferences fault-injection fixture is available on the Samsung. No failure was induced; normal sign-in does not substitute. |
| 754 | Login-success Home exposure (case 2 setup) | PASS | PASS (local debug provider only) | PASS (local breadcrumb) | One `home_screen_viewed` with bounded `login` / `login_success` attribution after successful credential persistence; no duplicate observed. Evidence: [case-2 run](manual-qa-evidence/2026-09-26/case-002-cold-start-signed-in-run.txt). |
| 754 | Authenticated cold-start Home exposure (case 2) | PASS | PASS (local debug provider only) | PASS (local breadcrumb) | One `app_launch_route_resolved` and one visible Home event with `splash` / `app_launch`; zero Welcome/Login screen-view events after force-stop/relaunch. Firebase delivery not claimed. |
| 754 | Guest selection Home exposure (cases 3/5 shared run) | PASS | PASS (local debug provider only) | PASS (local breadcrumb) | Exactly one `home_screen_viewed` with `welcome` / `browse_without_account`, following persisted guest preference; matching actual-visible Home breadcrumb. Evidence: [case-3/5 run](manual-qa-evidence/2026-09-26/case-003-guest-cold-start-run.txt). |
| 754 | Guest cold-start Home exposure (case 3) | PASS | PASS (local debug provider only) | PASS (local breadcrumb) | After force-stop/relaunch, exactly one route resolution and one Home exposure with `splash` / `app_launch`; local EPUB remained visible and Welcome/Login were absent. Evidence: [case-3 run](manual-qa-evidence/2026-09-26/case-003-guest-cold-start-run.txt). |
| 754 | Recomposition and tab-switch deduplication | NOT RUN | NOT RUN | NOT RUN | No recomposition or tab-switch sequence was run after Home entry; retain as an explicit case-754 requirement. |
| 755 | Guest-mode preference write failure and retry | BLOCKED | BLOCKED | BLOCKED | QA-BUG-0017 fix commit `54708a90` has a passing common fault-injection regression test and debug build; successful guest retry/navigation was exercised on the Samsung, but no safe app-scoped device preference-write fault fixture is available to trigger the failure UI/non-fatal and recovery path. No failure branch or Firebase Crashlytics delivery is claimed. |
| 756 | Welcome root system-Back usage and exit outcome | PASS (shared case-522 run) | PASS (local debug provider only) | PASS (local breadcrumbs; no failure induced) | The final build logged one `navigation_back` event (`welcome` → `app_exit`, `system_back`, `exited`) and one started/completed breadcrumb pair; Back exited and relaunch returned to Welcome. Earlier intermediate run had no Back signal and is retained in the evidence as QA-BUG-0019's runtime observation. Firebase Analytics ingestion is waived and not claimed. |

### Login instrumentation preflight (source/build verification only)

| Signal | Emission boundary and safe fields | Verification before UI pass |
|---|---|---|
| `login_screen_viewed` | Actual visible Login route; source screen and entry point | Auth Analytics sanitizer tests PASS; Samsung exposure still NOT RUN |
| `login_attempted` | Accepted credentials/OAuth submit; server type, method, retry, started outcome | Sanitizer tests PASS; Samsung sequence still NOT RUN |
| `login_succeeded` | Only after auth and local credential persistence; method/server type/outcome/duration | Source boundary PASS; Samsung sign-in still NOT RUN |
| `login_failed` / `login_cancelled` / `login_abandoned` | Distinct terminal states and bounded reason codes | Schema sanitizer tests PASS; device outcomes still NOT RUN |
| `oauth_login_step_failed` | Bounded Storyteller OAuth exchange stage and status code | Existing name retained; Samsung OAuth path NOT RUN |
| Login diagnostics | Typed start/stage/terminal breadcrumbs with diagnostic-only correlation ID; one unexpected report at UI boundary | Unit tests/build PASS; local Samsung failure breadcrumbs and Crashlytics delivery NOT RUN/BLOCKED |

Case-level Analytics status is reported per row above; cases 1–5 have local-provider evidence as individually described, while the remaining cases are NOT RUN/BLOCKED unless stated. Debug provider evidence is local-only; Firebase Analytics ingestion is waived and never inferred from local logs. Crashlytics delivery remains required and blocked by unavailable authorized Console access.

## Screen groups C–M

Only the Home/Books destination was observed as the guest target and local-import fixture for cases 3/5; no Home-group baseline case has started. Execute remaining groups in catalogue order; add a row per case and applicable variant (especially Back/dismiss/lifecycle variants) before and during each screen pass. Do not mark a parent case PASS until all required variants pass with evidence.
