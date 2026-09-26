# Parrot Samsung QA run report

## Run manifest

- **Run date:** 2026-09-26 (local device time; timezone not independently verified)
- **Repository:** `/Users/rokretar/StudioProjects/StoryTellerKMP`
- **Source commit:** `ffef0a3d03ca73b4ffcff8cf9a47a243e035d2ac` (`fix(analytics): filter event dimensions [QA-BUG-0001, test 509]`); QA-BUG-0002 changes are currently uncommitted.
- **Working tree at start:** clean except for the user-provided, untracked `docs/manual-qa-goal.md` and `docs/manual-qa-test-plan.md`; preserve both.
- **Required physical device:** serial `RFCWC0SSVDM`; model `SM-S921B` (Galaxy S24)
- **OS:** Android 16 / API 36; One UI 8.0 (device property `ro.build.version.oneui=80500`); build `S921BXXSGDZG1`; security patch 2026-07-05
- **Display/navigation baseline:** portrait, 1080×2340 px, physical density 480 dpi / override 420 dpi, font scale 1.0, gesture navigation (`navigation_mode=2`)
- **Power/network/permissions baseline:** battery saver off; airplane mode off; notification permission granted; active network appears Wi‑Fi; app storage volume 65 GB free of 224 GB; other permissions/settings and active profile not yet inventoried
- **Installed package:** `com.retro99.parrot`, version `0.4.5` (version code `21`), target SDK 36, debuggable, installer reported `null` (ADB/package-manager install source unavailable)
- **Installed build provenance:** updated in place from the QA working tree; package version remains `0.4.5` (21), debuggable, installer `null`. Installed APK SHA-256 `7ced27f33b538ceca645827faec256f463efe4c806e0faa6f07fed119b193e40` exactly matches the local debug APK. Build inputs are commit `ffef0a3d03ca73b4ffcff8cf9a47a243e035d2ac` plus uncommitted QA-BUG-0002 changes; case 496 still needs a clean post-commit build/reinstall.
- **Builds attempted from current source:** `./gradlew :androidApp:assembleDebug :androidApp:assembleRelease` — **BUILD SUCCESSFUL**. Release minification emitted missing XML serialization service warnings; Crashlytics mapping task ran. Debug APK SHA-256 `7ced27f33b538ceca645827faec256f463efe4c806e0faa6f07fed119b193e40`; release APK SHA-256 `f5d59bb14fa71c236593b15dc135c4d6f06ee9c2352cb97397eb8c4e848a34a5`; mapping SHA-256 `5810dad882ee18e9d42a483ecbeca41096c020e78f5f921a5a4e493057a9e04e`.
- **Verification command:** `./gradlew :lib:analytics:implementation:testAndroidHostTest :lib:analytics:implementation:compileKotlinIosSimulatorArm64 :lib:analytics:implementation:compileTestKotlinIosSimulatorArm64 :androidApp:assembleDebug :androidApp:assembleRelease` — **BUILD SUCCESSFUL**; six Android host tests passed; iOS main/test source compilation passed. An earlier aggregate `allTests` attempt could not link the iOS test runner because `FirebaseCore` is unavailable in the Xcode toolchain.
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

**Current screen group:** A — Samsung setup and reporting readiness (cases 495–514). Screen B has not started.

| Area | Status | Notes |
|---|---|---|
| Device selection (495) | PASS | Physical SM-S921B enumerated and all inspected with explicit serial. |
| Tested build identity (496) | BLOCKED | Installed APK matches current-source debug output, but the build includes uncommitted QA-BUG-0002 changes; clean post-commit build/reinstall still required. |
| Device baseline (497) | BLOCKED | Core values recorded; active profile, complete permission/settings inventory and repeatable test fixture still missing. |
| Fixtures (S2–S5; 498–501) | BLOCKED / partial | No identified server/cloud credentials or controlled media/failure fixtures yet. Device can toggle airplane mode and kill processes; safe low-storage fixture absent. |
| Labelled log capture (502) | NOT RUN | Timestamp-filtered logcat excerpt captured for case 503; continuous capture was not started. |
| Debug provider (503) | FAIL (diagnostics) / PASS (analytics) | Cold-started debug build; local analytics observed, but 19 sanitized handled-exception records appeared in ~14 seconds for unreachable-server requests (QA-BUG-0005). See both case-503 evidence files. |
| Firebase Analytics delivery (504–505) | DEFERRED (user waiver) | User accepts debug-provider event visibility for Analytics checks; Firebase ingestion remains unverified and is not claimed. |
| Crashlytics delivery / symbolication / breadcrumbs (506–508) | BLOCKED | No authorized Firebase Console session, controlled corrupt-file fixture or release-device test. |
| Privacy / duplicate / QA classification (509–511) | FAIL / BLOCKED | QA-BUG-0001 passes host tests/local debug output. Distinctive payload use is not yet executed; Crashlytics payload inspection is blocked. QA-BUG-0002 sanitized local exception retest passes. Source audit confirms duplicate login reports (QA-BUG-0004); startup confirms network exception flood (QA-BUG-0005). QA-BUG-0003 and production QA-traffic policy remain open. |
| Offline telemetry / overhead / cleanup (512–514) | NOT RUN | These need an instrumented on-device pass. |
| Screen groups B–M | NOT RUN | No screen pass has begun. |

## Blockers and prerequisites

1. Need a repeatable controlled QA account/media fixture set: Storyteller/Audiobookshelf library, Parrot Cloud account, valid/corrupt/large EPUBs and fault-injection fixture.
2. Need authorized Firebase Console access for Crashlytics non-fatal delivery/symbolication and an agreed production QA-traffic policy. Firebase Analytics ingestion was waived by the user; no ingestion claim will be made. Opening Console redirects to Google sign-in; no authorized account is available in this browser session (tab `tab_53bffb7c-3a99-4faf-a663-fa33695c06e8`).
3. Need a safe, explicitly allocated test profile/data set before destructive imports/deletions, low-storage tests or switching the active app build.
4. Need run-specific continuous/logged evidence capture and complete device/settings baseline before executing screen cases.
5. Bluetooth/headset fixtures are not confirmed available; those cases will be BLOCKED if reached without the accessory. Authors navigation is conditionally disabled per source documentation and must be confirmed against installed navigation before disposition.

## User-approved verification scope adjustment

On 2026-09-26 the user reported having tested debug event behavior and stated that seeing events in the debug build is sufficient. For Firebase **Analytics** event-delivery checks only, accept local debug-provider visibility and record Firebase ingestion tests 504–505 as DEFERRED by user waiver. Do not label debug logs as Firebase delivery: the current `DebugAnalyticsManager` writes local Kermit logs and does not forward to Firebase. Crashlytics non-fatal delivery/symbolication are separate and remain required unless the user also waives them.

## Final summary

**IN PROGRESS — no screen signed off.** Setup case 495 passed; case 503 found a diagnostics flood. Six Android host tests and Android debug/release builds pass. The user waived Firebase Analytics ingestion checks; Firebase delivery is not claimed. Crashlytics delivery remains unverified. QA-BUG-0002 is locally retested and awaiting its focused commit; QA-BUG-0005 needs a fix before proceeding.
