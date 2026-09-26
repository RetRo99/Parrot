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

Source audit identified QA-BUG-0006 (still open/partially remediated) and QA-BUG-0007 (fixed and Samsung-ret-tested). Welcome instrumentation QA-BUG-0010 is committed; case 1 has exercised its screen-view event. QA-BUG-0011's debug-badge fix is committed and the debug variant was verified early because the missing badge was visible in case-1 evidence. Case 7 release comparison and onboarding cases not yet reached remain NOT RUN/BLOCKED as individually stated.

| Case | Variant / evidence | Functional | Analytics | Diagnostics | Notes / defect |
|---:|---|---|---|---|---|
| 1 | Cold start on first install; debug APK, no permissions granted | PASS | PASS (local debug provider only) | PASS (local breadcrumbs; no failure induced) | Parrot alone was uninstalled/reinstalled under the user's authorization. Welcome displayed Get Started and Browse without account; `POST_NOTIFICATIONS` remained denied and no runtime permission was granted. One `app_launch_route_resolved`, one `welcome_screen_viewed`, and startup/visible breadcrumbs appeared in PID-scoped local logs. APK hash matched local/installed `059d4d…7620b`, source commit `eead1977`. A UI hierarchy helper timed out because it could not reach Compose idle, but the screenshot visually confirms the labels; not treated as an app failure. The initial screenshot also exposed QA-BUG-0011 (badge missing), fixed and retested separately. Evidence: [run record](manual-qa-evidence/2026-09-26/case-001-first-install-run.txt), [Welcome screenshot](manual-qa-evidence/2026-09-26/case-001-first-install-welcome.png). Firebase delivery is not claimed. |
| 2 | Cold start when already signed in | BLOCKED | BLOCKED | BLOCKED | Login route is ready on the latest build, but the demo credential value is not available in this resumed context. It will not be requested in chat or written to evidence/logs; the user must enter it directly in Parrot UI or provide it through an approved secure path. No authentication request has been submitted. Current blocker: [case-002 continuation evidence](manual-qa-evidence/2026-09-26/case-002-login-credential-blocked.txt); historical [case-002 blocker evidence](manual-qa-evidence/2026-09-26/case-002-missing-credential-blocker.txt) is retained. |
| 3 | Cold start in guest mode | NOT RUN | NOT RUN | NOT RUN | Previous preservation-based BLOCKED disposition withdrawn. Need perform guest entry and create/import a safe local test EPUB before verifying persistence on the next cold start; no step executed. Historical note: [case-003](manual-qa-evidence/2026-09-26/case-003-guest-cold-start-blocked.txt). |
| 4 | Get Started | PASS | PASS (local debug provider only) | PASS (local breadcrumb; no failure induced) | Tapped once as the required Login route precondition for case 2. Login appeared with server-type dropdown; one attempt, completion and Login exposure event plus one route breadcrumb observed. No credentials entered. Evidence: [run record](manual-qa-evidence/2026-09-26/case-004-get-started-run.txt), [Login screenshot](manual-qa-evidence/2026-09-26/case-004-get-started-login.png). Firebase Analytics delivery is not claimed. |
| 5 | Browse without account | NOT RUN | NOT RUN | NOT RUN | Previous data-preservation blocker withdrawn. Guest action/outcome events are implemented but not yet exercised; no UI action executed. Historical note: [case-005](manual-qa-evidence/2026-09-26/case-005-guest-entry-blocked.txt). |
| 6 | Back arrow on Welcome | NOT RUN | NOT RUN | NOT RUN | Welcome is reachable. Source conditionally renders a toolbar arrow only when a non-null `onBack` callback is supplied; the first-install root passes null. Verify the nested login→Welcome route and system Back before deciding whether the catalogue's assumed arrow exists. Historical note: [case-006](manual-qa-evidence/2026-09-26/case-006-welcome-back-blocked.txt). |
| 7 | Build badge — debug variant | PASS (debug only) | N-A (static build marker) | N-A (static build marker) | Initial case-1 screenshot on hash `059d4d…7620b` showed no badge despite `DEBUGGABLE` (QA-BUG-0011); fixed by reading `BuildConfig.isDebug` directly in `LoginNavigationViewModel`. Retest on Samsung hash `b6dd4a…67092` visibly shows DEBUG. Release comparison is a separate not-run row below. Evidence: [failure screenshot](manual-qa-evidence/2026-09-26/case-001-first-install-welcome.png), [fixed screenshot and retest](manual-qa-evidence/2026-09-26/qa-bug-0011-debug-badge-retest.png), [retest details](manual-qa-evidence/2026-09-26/qa-bug-0011-debug-badge-retest.txt). |
| 7 | Build badge — release variant | NOT RUN | N-A (static build marker) | N-A (static build marker) | No release build was installed on the Samsung; case 7 parent remains incomplete until RELEASE badge behavior is safely compared. |
| 8 | E-ink theming | DEFERRED | N-A (platform deferred) | DEFERRED | No e-ink hardware for this pass; per test-plan scope override. [Disposition](manual-qa-evidence/2026-09-26/case-008-eink-deferred.txt). |
| 9 | Rotation on Welcome | NOT RUN | NOT RUN | NOT RUN | Previous preservation-based BLOCKED disposition withdrawn; no rotation performed yet. Historical note: [case-009](manual-qa-evidence/2026-09-26/case-009-welcome-rotation-blocked.txt). |
| 10 | Background and resume on Welcome | NOT RUN | NOT RUN | NOT RUN | Previous preservation-based BLOCKED disposition withdrawn; no background/resume interaction performed yet. Historical note: [case-010](manual-qa-evidence/2026-09-26/case-010-welcome-resume-blocked.txt). |
| 11 | Open last book on launch (enabled) | NOT RUN | NOT RUN | NOT RUN | Previous preservation-based BLOCKED disposition withdrawn; create/import a controlled local EPUB and saved position before testing. Historical note: [case-011](manual-qa-evidence/2026-09-26/case-011-last-book-launch-blocked.txt). |
| 12 | Open last book on launch (no current book) | NOT RUN | NOT RUN | NOT RUN | Previous preservation-based BLOCKED disposition withdrawn; prepare a no-current-book fixture before testing. Historical note: [case-012](manual-qa-evidence/2026-09-26/case-012-last-book-no-current-blocked.txt). |
| 13 | Locale rendering (Spanish device locale) | BLOCKED (partial: reachable Books, Series, Statistics and Settings surfaces rendered English; other routes were not reached) | PASS after QA-BUG-0008 fix (first run exposed missing startup event; retest emitted one `app_launch_route_resolved` event on launch; local provider only) | NOT RUN (no induced UX failure; relaunch showed no fatal exception in the PID-scoped check; actionable Crashlytics delivery/context not verified, QA-BUG-0006 remains open) | Locale changed from `en-GB` to `es-ES`, app force-stopped/launched, four reachable tabs inspected and screenshots saved; locale restored to `en-GB`. Retest build `0.4.5` (21), APK SHA-256 `b2045002…882f4`. Firebase Analytics ingestion is waived, not claimed. [Run evidence](manual-qa-evidence/2026-09-26/case-013-locale-run.txt); [instrumentation retest](manual-qa-evidence/2026-09-26/case-013-launch-event-retest.txt). |

Cases 14–36 and Login extensions 515–534, 751–753 (all not yet exercised):

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
| 22 | Successful sign-in (credentials) | NOT RUN | NOT RUN | NOT RUN | Demo sign-in has not yet been attempted on the latest instrumentation build; use only in Parrot UI. QA-BUG-0003 identity clear and QA-BUG-0013 normal success branch remain to verify on-device. |
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
| 522 | Welcome root system Back | NOT RUN | NOT RUN | NOT RUN | No system Back on root Welcome. |
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

### Login instrumentation preflight (source/build verification only)

| Signal | Emission boundary and safe fields | Verification before UI pass |
|---|---|---|
| `login_screen_viewed` | Actual visible Login route; source screen and entry point | Auth Analytics sanitizer tests PASS; Samsung exposure still NOT RUN |
| `login_attempted` | Accepted credentials/OAuth submit; server type, method, retry, started outcome | Sanitizer tests PASS; Samsung sequence still NOT RUN |
| `login_succeeded` | Only after auth and local credential persistence; method/server type/outcome/duration | Source boundary PASS; Samsung sign-in still NOT RUN |
| `login_failed` / `login_cancelled` / `login_abandoned` | Distinct terminal states and bounded reason codes | Schema sanitizer tests PASS; device outcomes still NOT RUN |
| `oauth_login_step_failed` | Bounded Storyteller OAuth exchange stage and status code | Existing name retained; Samsung OAuth path NOT RUN |
| Login diagnostics | Typed start/stage/terminal breadcrumbs with diagnostic-only correlation ID; one unexpected report at UI boundary | Unit tests/build PASS; local Samsung failure breadcrumbs and Crashlytics delivery NOT RUN/BLOCKED |

All case-level Analytics statuses below remain NOT RUN until observed on the Samsung. Debug provider evidence is local-only; Firebase Analytics ingestion is waived and never inferred from local logs. Crashlytics delivery remains required and blocked by unavailable authorized Console access.

## Screen groups C–M

No cases in these groups have started. Execute in catalogue order after case 1 is dispositioned; add a row per case and applicable variant (especially Back/dismiss/lifecycle variants) before and during each screen pass. Do not mark a parent case PASS until all required variants pass with evidence.
