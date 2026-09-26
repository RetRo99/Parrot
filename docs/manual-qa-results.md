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
| 509 | Sensitive-data inspection | BLOCKED | BLOCKED (distinctive payload exercise not run) | BLOCKED (Crashlytics delivery inspection) | QA-BUG-0001 source issue fixed with a provider-boundary allowlist; host tests and local debug output pass. Firebase Analytics ingestion is waived, but distinctive user text was not exercised. QA-BUG-0002 sanitizer passes locally; QA-BUG-0003 remains suspected. |
| 510 | Exception deduplication | NOT RUN | N-A (diagnostic case) | FAIL (source audit; runtime not run) | Login failure is reported by both `LoginDataRepository` and `LoginViewModel` (QA-BUG-0004); runtime non-fatal count is unverified. QA-BUG-0002 exception context fix is in progress. |
| 511 | QA traffic classification | BLOCKED | BLOCKED | N-A (reporting policy case) | No agreed QA exclusion policy or build/environment marker found in the inspected reporting path; Firebase runtime remains unverified. |
| 512 | Offline telemetry resilience | NOT RUN | NOT RUN | NOT RUN | Network was online; no offline telemetry exercise. |
| 513 | Logging overhead | NOT RUN | NOT RUN | NOT RUN | No comparative timed/scroll/playback test. |
| 514 | End-of-run cleanup | NOT RUN | N-A (setup) | NOT RUN | Firebase debug property not changed; final restoration audit pending. |

## B — App launch & onboarding

| Case | Variant / evidence | Functional | Analytics | Diagnostics | Notes / defect |
|---:|---|---|---|---|---|
| 1 | Cold start on first install; current installed app was already provisioned | BLOCKED | NOT RUN | NOT RUN | The Samsung already has `com.retro99.parrot` installed and was displaying `MainActivity`. Case 1 requires a clean first-install state with no permissions granted. Do not uninstall or clear app data without confirmation: current app/profile/fixture ownership is unknown. A disposable QA Android user/profile or explicit authorization to reset app data is required. No case-1 launch steps were performed; no PASS is claimed. [Precondition evidence](manual-qa-evidence/2026-09-26/case-001-first-install-blocked.txt). |
| 2 | Cold start when already signed in | BLOCKED | NOT RUN | NOT RUN | Requires a known signed-in QA server session before killing/relaunching. No controlled server credentials were provided, and the current session is unverified; no account state was inspected or changed. No test steps were performed. [Precondition evidence](manual-qa-evidence/2026-09-26/case-002-signed-in-cold-start-blocked.txt). |

## Screen groups C–M

No cases in these groups have started. Execute in catalogue order after case 1 is dispositioned; add a row per case and applicable variant (especially Back/dismiss/lifecycle variants) before and during each screen pass. Do not mark a parent case PASS until all required variants pass with evidence.
