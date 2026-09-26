# Parrot Samsung QA run report

## Run manifest

- **Run date:** 2026-09-26 (local device time; timezone not independently verified)
- **Repository:** `/Users/rokretar/StudioProjects/StoryTellerKMP`
- **Source commit:** `956ec8a443d2236396c490a3056efdf9a1181e32` (`Implement UI refresh and recap support`)
- **Working tree at start:** clean except for the user-provided, untracked `docs/manual-qa-goal.md` and `docs/manual-qa-test-plan.md`; preserve both.
- **Required physical device:** serial `RFCWC0SSVDM`; model `SM-S921B` (Galaxy S24)
- **OS:** Android 16 / API 36; One UI 8.0 (device property `ro.build.version.oneui=80500`); build `S921BXXSGDZG1`; security patch 2026-07-05
- **Display/navigation baseline:** portrait, 1080×2340 px, physical density 480 dpi / override 420 dpi, font scale 1.0, gesture navigation (`navigation_mode=2`)
- **Power/network/permissions baseline:** battery saver off; airplane mode off; notification permission granted; active network appears Wi‑Fi; app storage volume 65 GB free of 224 GB; other permissions/settings and active profile not yet inventoried
- **Installed package:** `com.retro99.parrot`, version `0.4.5` (version code `21`), target SDK 36, debuggable, installer reported `null` (ADB/package-manager install source unavailable)
- **Installed build provenance:** updated from the current QA working tree with `adb -s RFCWC0SSVDM install -r .../androidApp-debug.apk`; package version remains `0.4.5` (21), debuggable, installer `null`. Installed APK SHA-256 `f77dc3563cf2b1828af20628ac89ed9054e3d15a442c6130927f13e1b60eb937` exactly matches the local debug APK. Build inputs are source commit `956ec8a443d2236396c490a3056efdf9a1181e32` plus uncommitted QA-BUG-0001 changes; rebuild/reinstall after committing before finalizing case 496.
- **Builds attempted from current source:** `./gradlew :androidApp:assembleDebug :androidApp:assembleRelease` — **BUILD SUCCESSFUL**. Release minification emitted missing XML serialization service warnings; release Crashlytics mapping-file task ran. Debug APK SHA-256 `f77dc3563cf2b1828af20628ac89ed9054e3d15a442c6130927f13e1b60eb937`; release APK SHA-256 `020ee5da105c90e901596377ded26003b1c2c0d2962ac1d820c5ca474be6ebbe`.
- **Verification commands:** `./gradlew :lib:analytics:implementation:testAndroidHostTest :androidApp:assembleDebug :androidApp:assembleRelease` — **BUILD SUCCESSFUL**; Android host unit tests passed. Separate `:lib:analytics:implementation:allTests` failed at iOS test linking because `FirebaseCore` is unavailable in the Xcode toolchain; Android host tests passed independently.
- **Current visible destination at start:** Reader. A screenshot was briefly captured, found to contain book text, and deleted; no reader-content screenshot is retained.
- **Firebase QA status:** Debug provider is local-only (`DebugAnalyticsManager`). Firebase-backed release build exists, but is not yet installed/exercised; Firebase Console/DebugView/Crashlytics delivery and symbolication have not been verified.

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
| Tested build identity (496) | BLOCKED | Installed APK matches current-source debug output, but the build includes uncommitted QA-BUG-0001 changes; clean post-commit build/reinstall still required. |
| Device baseline (497) | BLOCKED | Core values recorded; active profile, complete permission/settings inventory and repeatable test fixture still missing. |
| Fixtures (S2–S5; 498–501) | BLOCKED / partial | No identified server/cloud credentials or controlled media/failure fixtures yet. Device can toggle airplane mode and kill processes; safe low-storage fixture absent. |
| Labelled log capture (502) | NOT RUN | Timestamp-filtered logcat excerpt captured for case 503; continuous capture was not started. |
| Debug provider (503) | PASS | Cold-started the installed debug build on Samsung; local events observed. See [`setup-debug-provider-events.txt`](manual-qa-evidence/2026-09-26/setup-debug-provider-events.txt). |
| Firebase delivery / DebugView / Crashlytics (504–508) | BLOCKED | Firebase-backed release APK built; not installed or executed and no Firebase Console evidence available yet. No controlled corrupt-file fixture identified. |
| Privacy / duplicate / QA classification (509–511) | BLOCKED / FAIL | QA-BUG-0001 has a fail-closed parameter sanitizer; tests and Samsung debug log pass, but Firebase inspection is blocked. Raw exception context risk QA-BUG-0002 remains. A source-confirmed, suspected user-identity privacy gap (QA-BUG-0003) and the QA traffic exclusion policy remain unverified. |
| Offline telemetry / overhead / cleanup (512–514) | NOT RUN | These need an instrumented on-device pass. |
| Screen groups B–M | NOT RUN | No screen pass has begun. |

## Blockers and prerequisites

1. Need a repeatable controlled QA account/media fixture set: Storyteller/Audiobookshelf library, Parrot Cloud account, valid/corrupt/large EPUBs and fault-injection fixture.
2. Need authorized access to Firebase DebugView and Crashlytics for the project configured in this build, plus agreement on QA event exclusion/classification.
3. Need a safe, explicitly allocated test profile/data set before destructive imports/deletions, low-storage tests or switching the active app build.
4. Need run-specific continuous/logged evidence capture and complete device/settings baseline before executing screen cases.
5. Bluetooth/headset fixtures are not confirmed available; those cases will be BLOCKED if reached without the accessory. Authors navigation is conditionally disabled per source documentation and must be confirmed against installed navigation before disposition.

## Final summary

**IN PROGRESS — no screen signed off.** Setup case 495 and the debug-provider demonstration passed; setup remains incomplete. The analytics parameter sanitizer passed Android host tests, Android debug/release builds and local Samsung log verification. Firebase ingestion is not claimed. QA-BUG-0002 remains open; QA-BUG-0001 still needs Firebase payload verification and a clean post-commit build/retest.
