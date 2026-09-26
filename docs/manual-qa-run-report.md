# Parrot Samsung QA run report

## Run manifest

- **Run date:** 2026-09-26 (local device time; timezone not independently verified)
- **Repository:** `/Users/rokretar/StudioProjects/StoryTellerKMP`
- **Source commit:** `d1e18128288d7342c211d226a9041387a0974796` (`fix(network): classify expected failure reporting [QA-BUG-0005, test 503]`).
- **Working tree at start:** clean except for the user-provided, untracked `docs/manual-qa-goal.md` and `docs/manual-qa-test-plan.md`; preserve both.
- **Required physical device:** serial `RFCWC0SSVDM`; model `SM-S921B` (Galaxy S24)
- **OS:** Android 16 / API 36; One UI 8.0 (device property `ro.build.version.oneui=80500`); build `S921BXXSGDZG1`; security patch 2026-07-05
- **Display/navigation baseline:** portrait, 1080×2340 px, physical density 480 dpi / override 420 dpi, font scale 1.0, gesture navigation (`navigation_mode=2`)
- **Power/network/permissions baseline:** battery saver off; airplane mode off; notification permission granted; active network appears Wi‑Fi; app storage volume 65 GB free of 224 GB; other permissions/settings and active profile not yet inventoried
- **Installed package:** `com.retro99.parrot`, version `0.4.5` (version code `21`), target SDK 36, debuggable, installer reported `null` (ADB/package-manager install source unavailable)
- **Installed build provenance:** rebuilt and reinstalled after commit `d1e18128288d7342c211d226a9041387a0974796`; package version remains `0.4.5` (21), debuggable, installer `null`. Installed APK and local debug APK SHA-256 both `b1f6d3ed57189df9cafb001a154c490526078687150331e479bd3680c080595e` (case 496 identity check).
- **Builds attempted from current source:** `./gradlew :androidApp:assembleDebug :androidApp:assembleRelease` — **BUILD SUCCESSFUL**. Release minification emitted missing XML serialization service warnings; Crashlytics mapping task ran. Debug APK SHA-256 `fea8fb9b0ddaccde047b3af52ec70bae1f362bfb22361fa61779ae57c463a1fb`; release APK SHA-256 `b438a4b05cb0a99ac05817e5faf05e48477028d228318d05e7736088e01bb222`; mapping SHA-256 `3ff347e6cb669d70a3309da0713a9d74e7e46d7448e5f3ee66018f181a6e2b91`.
- **Verification commands:** `./gradlew :lib:network:implementation:testAndroidHostTest :lib:analytics:implementation:testAndroidHostTest :androidApp:assembleDebug :androidApp:assembleRelease` — **BUILD SUCCESSFUL**; ten Android host tests passed (4 network classification/report-policy, 6 analytics/diagnostic payload); debug and release APKs built. Post-commit `./gradlew :lib:network:implementation:testAndroidHostTest :lib:analytics:implementation:testAndroidHostTest :androidApp:assembleDebug` — **BUILD SUCCESSFUL** (host test tasks were up-to-date); committed debug APK installed/hash-verified and Samsung retest 5 passed. An earlier aggregate `allTests` attempt could not link the iOS test runner because `FirebaseCore` is unavailable in the Xcode toolchain.
- **Current visible destination at start:** Reader. A screenshot was briefly captured, found to contain book text, and deleted; no reader-content screenshot is retained.
- **Firebase QA status:** The user waived Firebase Analytics ingestion verification on 2026-09-26 and accepted visible debug events for app-side Analytics checks. This does **not** prove Firebase delivery; no Firebase ingestion claim will be made. Crashlytics non-fatal delivery and symbolication remain required and unverified. Debug provider is local-only (`DebugAnalyticsManager`).

## Commands / evidence

All device commands use the explicit serial `RFCWC0SSVDM`; other attached devices (an Android Auto head unit and an emulator) were not targeted. Initial device checks included:

```sh
adb devices -l
adb -s RFCWC0SSVDM shell getprop ro.product.model
adb -s RFCWC0SSVDM shell getprop ro.build.version.release
adb -s RFCWC0SSVDM shell getprop ro.build.version.sdk
adb -s RFCWC0SSVDM shell getprop ro.build.version.oneui
adb -s RFCWC0SSVDM shell dumpsys package com.retro99.parrot
adb -s RFCWC0SSVDM shell settings get secure navigation_mode
adb -s RFCWC0SSVDM shell wm size
adb -s RFCWC0SSVDM shell wm density
adb -s RFCWC0SSVDM shell df -h /data/user/0
```

Case evidence and sanitized excerpts will be stored under [`manual-qa-evidence/`](manual-qa-evidence/). No Firebase test property has been set. No app data was cleared. A test-only configuration cleanup is pending because the Firebase pass has not started.

## Progress

**Current test:** Case 6 — App launch & onboarding; blocked before execution because the logged-out Welcome route is unavailable. Cases 1–5 were recorded BLOCKED for first-install/session/guest/Welcome prerequisites. Continue in numbered catalogue order.

| Area | Status | Notes |
|---|---|---|
| Device selection (495) | PASS | Physical SM-S921B enumerated and all inspected with explicit serial. |
| Tested build identity (496) | PASS | Committed QA-BUG-0005 debug APK reinstalled; local/installed SHA-256 matched. |
| Device baseline (497) | BLOCKED | Core values recorded; active profile, complete permission/settings inventory and repeatable test fixture still missing. |
| Fixtures (S2–S5; 498–501) | BLOCKED / partial | No identified server/cloud credentials or controlled media/failure fixtures yet. Device can toggle airplane mode and kill processes; safe low-storage fixture absent. |
| Labelled log capture (502) | NOT RUN | Timestamp-filtered logcat excerpt captured for case 503; continuous capture was not started. |
| Debug provider (503) | PASS (local provider) | Local Analytics events visible. QA-BUG-0005 baseline had 19 exception records; retest 1 had six; retest 2 showed zero markers/nine events; attempt 3 counts were invalid stale buffer output and excluded; retest 4 showed zero exception markers/two events; post-commit retest 5 repeated zero markers/two events on the rebuilt, hash-matched APK. Full performance case 513 remains NOT RUN. See case-503 evidence files. |
| Firebase Analytics delivery (504–505) | DEFERRED (user waiver) | User accepts debug-provider event visibility for Analytics checks; Firebase ingestion remains unverified and is not claimed. |
| Crashlytics delivery / symbolication / breadcrumbs (506–508) | BLOCKED | No authorized Firebase Console session, controlled corrupt-file fixture or release-device test. |
| Privacy / duplicate / QA classification (509–511) | FAIL / BLOCKED | QA-BUG-0001 passes host tests/local debug output. Distinctive payload use is not yet executed; Crashlytics payload inspection is blocked. QA-BUG-0002 sanitized local exception retest passes. Source audit confirms duplicate login reports (QA-BUG-0004); startup confirms network exception flood (QA-BUG-0005). QA-BUG-0003 and production QA-traffic policy remain open. |
| Offline telemetry / overhead / cleanup (512–514) | NOT RUN | These need an instrumented on-device pass. |
| Screen groups B–M | IN PROGRESS / NOT RUN | Case 1 is blocked before execution by the unavailable safe first-install fixture; no app screen has been signed off. |

## Blockers and prerequisites

1. Need a repeatable controlled QA account/media fixture set: Storyteller/Audiobookshelf library, Parrot Cloud account, valid/corrupt/large EPUBs and fault-injection fixture.
2. Need authorized Firebase Console access for Crashlytics non-fatal delivery/symbolication and an agreed production QA-traffic policy. Firebase Analytics ingestion was waived by the user; no ingestion claim will be made. Opening Console redirects to Google sign-in; no authorized account is available in this browser session (tab `tab_53bffb7c-3a99-4faf-a663-fa33695c06e8`).
3. Need a safe, explicitly allocated test profile/data set before destructive imports/deletions, low-storage tests or switching the active app build.
4. Need run-specific continuous/logged evidence capture and complete device/settings baseline before executing screen cases.
5. Bluetooth/headset fixtures are not confirmed available; those cases will be BLOCKED if reached without the accessory. Authors navigation is conditionally disabled per source documentation and must be confirmed against installed navigation before disposition.
6. Case 1 needs a disposable first-install state. Do not erase the currently installed app/profile data without explicit authorization or a confirmed safe QA user/profile.

## User-approved verification scope adjustment

On 2026-09-26 the user reported having tested debug event behavior and stated that seeing events in the debug build is sufficient. For Firebase **Analytics** event-delivery checks only, accept local debug-provider visibility and record Firebase ingestion tests 504–505 as DEFERRED by user waiver. Do not label debug logs as Firebase delivery: the current `DebugAnalyticsManager` writes local Kermit logs and does not forward to Firebase. Crashlytics non-fatal delivery/symbolication are separate and remain required unless the user also waives them.

## Final summary

**IN PROGRESS — no screen signed off.** Per user direction, execution is proceeding from catalogue case 1 upward. Cases 1–6 are blocked before test steps: case 1 needs safe first-install state, case 2 a controlled signed-in QA server session, case 3 a disposable guest state/local library, and cases 4–6 logged-out/Welcome-route fixtures. Setup cases 495–496 and local setup case 503 are preparatory evidence; case 503 is not Firebase delivery evidence. Ten Android host tests passed, and debug/release builds succeeded. QA-BUG-0005 is fixed, committed (`d1e18128`), installed/hash-matched, and post-commit retested successfully; the stale attempt 3 was invalid and excluded. Other setup requirements remain blocked, waived or not run. The user waived Firebase Analytics ingestion checks; Crashlytics delivery remains unverified.
