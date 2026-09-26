# Parrot manual QA results

Run manifest: [manual-qa-run-report.md](manual-qa-run-report.md). All cases begin NOT RUN; results below are updated only after execution/evidence. Functional, analytics and diagnostics are tracked separately. Cross-app/cross-device sync cases are deferred per the test plan overrides.

## A — Samsung setup and reporting readiness (495–514)

| Case | Variant / evidence | Functional | Analytics | Diagnostics | Notes / defect |
|---:|---|---|---|---|---|
| 495 | Physical Samsung selected; explicit ADB serial `RFCWC0SSVDM` | PASS | N-A (setup) | PASS (device identity recorded) | `adb devices -l` showed Samsung serial/model; all property/package commands used `adb -s RFCWC0SSVDM`. |
| 496 | Installed package/build identity | BLOCKED | N-A (setup) | BLOCKED | Installed APK hash matches current-source debug output, but the build includes uncommitted QA-BUG-0001 changes; clean post-commit build/reinstall still required. |
| 497 | Device baseline | BLOCKED | N-A (setup) | BLOCKED | Android/One UI, model, display, navigation, font scale, battery saver, network, notification permission and free space recorded. Active profile and full settings/permission baseline remain unknown. |
| 498 | Guest fixture readiness | BLOCKED | N-A (setup) | BLOCKED | No controlled guest profile + valid local EPUB fixture identified. |
| 499 | Mixed-media fixture readiness | BLOCKED | N-A (setup) | BLOCKED | No reproducibly identified mixed-media/server library fixture. |
| 500 | Long-content fixtures | BLOCKED | N-A (setup) | BLOCKED | Long-title/description, large EPUB and many-track fixtures unavailable/not identified. |
| 501 | Failure fixtures | BLOCKED | N-A (setup) | BLOCKED | Corrupt EPUB, controlled unavailable server and safe constrained-storage fixture unavailable/not identified. |
| 502 | Labelled run capture | NOT RUN | NOT RUN | BLOCKED | Timestamp-filtered logcat excerpt captured for case 503, but continuous capture was not started. |
| 503 | Debug-provider tracked action | PASS | PASS (local only) | PASS (local log) | Cold-started the installed debug build on Samsung; observed local `network_request_failed` and `sync_run_completed` entries with only approved fields. See [`setup-debug-provider-events.txt`](manual-qa-evidence/2026-09-26/setup-debug-provider-events.txt). Not Firebase evidence. |
| 504 | Firebase-provider verification | BLOCKED | BLOCKED | BLOCKED | Current-source release build succeeded, but it is not installed/exercised and Firebase Console ingestion has not been observed. |
| 505 | Analytics DebugView delivery | BLOCKED | BLOCKED | N-A (Analytics case) | No DebugView access/evidence or labelled action yet. |
| 506 | Controlled non-fatal delivery | BLOCKED | N-A (Crashlytics case) | BLOCKED | Controlled fixture and Crashlytics Console evidence unavailable; no non-fatal was deliberately submitted. |
| 507 | Optimized-build symbolication | BLOCKED | N-A (Crashlytics case) | BLOCKED | Release mapping generated; no uploaded/symbolicated controlled issue verified. |
| 508 | Breadcrumb correlation | BLOCKED | N-A (diagnostic case) | BLOCKED | No labelled Books → Detail → Reader controlled failure run. |
| 509 | Sensitive-data inspection | BLOCKED | BLOCKED (Firebase delivery inspection) | BLOCKED (delivery inspection) | QA-BUG-0001 source issue fixed with a provider-boundary allowlist; host tests and local debug output pass. Firebase payload inspection remains unrun; QA-BUG-0002 raw exception context and suspected hashed-user-ID issue QA-BUG-0003 remain. |
| 510 | Exception deduplication | NOT RUN | N-A (diagnostic case) | BLOCKED | No controlled boundary failure executed. QA-BUG-0002 records raw exception-context risk; duplicate reporting not yet measured. |
| 511 | QA traffic classification | BLOCKED | BLOCKED | N-A (reporting policy case) | No agreed QA exclusion policy or build/environment marker found in the inspected reporting path; Firebase runtime remains unverified. |
| 512 | Offline telemetry resilience | NOT RUN | NOT RUN | NOT RUN | Network was online; no offline telemetry exercise. |
| 513 | Logging overhead | NOT RUN | NOT RUN | NOT RUN | No comparative timed/scroll/playback test. |
| 514 | End-of-run cleanup | NOT RUN | N-A (setup) | NOT RUN | Firebase debug property not changed; final restoration audit pending. |

## Screen groups B–M

No cases have started. Add a row per case and applicable variant (especially Back/dismiss/lifecycle variants) before and during each screen pass; do not mark a parent case PASS until all required variants pass with evidence.
