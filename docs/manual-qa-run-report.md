# Parrot Samsung QA run report

## Run manifest

- **Run date:** 2026-09-26 (local device time; timezone not independently verified)
- **Repository:** `/Users/rokretar/StudioProjects/StoryTellerKMP`
- **Current source/installed QA commit:** `b720ca69b9b76454af98b620adc5b1bcb7ac75f7` (`fix(login): show debug badge on Welcome [QA-BUG-0011, test 7]`).
- **Working tree at start:** clean except for the user-provided, untracked `docs/manual-qa-goal.md` and `docs/manual-qa-test-plan.md`; preserve both.
- **Required physical device:** serial `RFCWC0SSVDM`; model `SM-S921B` (Galaxy S24)
- **OS:** Android 16 / API 36; One UI 8.0 (device property `ro.build.version.oneui=80500`); build `S921BXXSGDZG1`; security patch 2026-07-05
- **Display/navigation baseline:** portrait, 1080×2340 px, physical density 480 dpi / override 420 dpi, font scale 1.0, gesture navigation (`navigation_mode=2`)
- **Power/network/permissions baseline:** battery saver off; airplane mode off; active network appears Wi‑Fi; app storage volume previously measured at 65 GB free of 224 GB. Notification permission was granted before the authorized Parrot uninstall; after clean reinstall, `POST_NOTIFICATIONS` is denied and the package has no other granted runtime permissions. Other settings/permissions and active profile are not fully inventoried.
- **Installed package:** `com.retro99.parrot`, version `0.4.5` (version code `21`), target SDK 36, debuggable, installer reported `null` (ADB/package-manager install source unavailable)
- **Installed build provenance:** rebuilt and reinstalled after commit `d1e18128288d7342c211d226a9041387a0974796`; package version remains `0.4.5` (21), debuggable, installer `null`. Installed APK and local debug APK SHA-256 both `b1f6d3ed57189df9cafb001a154c490526078687150331e479bd3680c080595e` (case 496 identity check).
- **Latest installed instrumentation build:** debug package `com.retro99.parrot` 0.4.5 (21), source through commit `b720ca69b9b76454af98b620adc5b1bcb7ac75f7`; local and Samsung-pulled APK SHA-256 `b6dd4a4cee8df560e3c5ed8097f3a14407c58703f777d29edb5d8322be167092`. It remains a debug-only analytics provider.
- **Earlier release artifact:** a release APK and mapping were built before the Welcome instrumentation work; release SHA-256 `b438a4b05cb0a99ac05817e5faf05e48477028d228318d05e7736088e01bb222`, mapping SHA-256 `3ff347e6cb669d70a3309da0713a9d74e7e46d7448e5f3ee66018f181a6e2b91`. It has not been installed on the Samsung or re-built from the current commit.
- **Verification commands:** `./gradlew :lib:network:implementation:testAndroidHostTest :lib:analytics:implementation:testAndroidHostTest :androidApp:assembleDebug :androidApp:assembleRelease` — **BUILD SUCCESSFUL**; ten Android host tests passed (4 network classification/report-policy, 6 analytics/diagnostic payload); debug and release APKs built. Post-commit `./gradlew :lib:network:implementation:testAndroidHostTest :lib:analytics:implementation:testAndroidHostTest :androidApp:assembleDebug` — **BUILD SUCCESSFUL** (host test tasks were up-to-date); committed debug APK installed/hash-verified and Samsung retest 5 passed. An earlier aggregate `allTests` attempt could not link the iOS test runner because `FirebaseCore` is unavailable in the Xcode toolchain.
- **Visible destination at run start:** Reader. A screenshot was briefly captured, found to contain book text, and deleted. After the authorized Parrot-only uninstall/reinstall, the app reached Welcome; current installed build is `b720ca69` and current destination remains Welcome.
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

Case evidence and sanitized excerpts are stored under [`manual-qa-evidence/`](manual-qa-evidence/). No Firebase test property has been set. On 2026-09-26 the user authorized Parrot/demo-data reset; only `com.retro99.parrot` was uninstalled and reinstalled on serial `RFCWC0SSVDM`. No unrelated apps, personal files, phone settings or services were modified.

## Progress

**Current screen:** Startup/Welcome/Login, case 2 next. The demo Storyteller credential is available in this continuation and will be used only in Parrot UI; no credential values are retained in reports, source, commands, evidence or logs. Login instrumentation/privacy and failure-path fixes are being completed before the first authenticated action. Case 1 passed on the Samsung with hash-matched `eead1977`; case 7 debug badge was fixed/retested in `b720ca69`, release comparison remains NOT RUN. Cases 3–6 and 9–12 remain NOT RUN. Case 8 is DEFERRED (no e-ink hardware); case 13 remains partial/BLOCKED for unvisited routes. User-authorized scope permits modifying/removing Parrot and connected demo data only; restore any device-wide setting changed by testing.

| Area | Status | Notes |
|---|---|---|
| Device selection (495) | PASS | Physical SM-S921B enumerated and all inspected with explicit serial. |
| Tested build identity (496) | PASS | Earlier committed QA-BUG-0005 build identity passed; current committed Welcome/debug-badge QA build is separately hash-matched (`b6dd4a…67092`). |
| Device baseline (497) | BLOCKED | Core values recorded; active profile, complete permission/settings inventory and repeatable test fixture still missing. |
| Fixtures (S2–S5; 498–501) | IN PROGRESS / partial | Storyteller demo credential is available for UI-only login; mixed-media/cloud fixtures and controlled media/failure files still require inventory/generation. User authorizes Parrot demo data/accounts to be changed or deleted. Device can toggle airplane mode and kill processes; safe constrained-storage fixture remains absent. |
| Labelled log capture (502) | NOT RUN | Timestamp-filtered logcat excerpt captured for case 503; continuous capture was not started. |
| Debug provider (503) | PASS (local provider) | Local Analytics events visible. QA-BUG-0005 baseline had 19 exception records; retest 1 had six; retest 2 showed zero markers/nine events; attempt 3 counts were invalid stale buffer output and excluded; retest 4 showed zero exception markers/two events; post-commit retest 5 repeated zero markers/two events on the rebuilt, hash-matched APK. Full performance case 513 remains NOT RUN. See case-503 evidence files. |
| Firebase Analytics delivery (504–505) | DEFERRED (user waiver) | User accepts debug-provider event visibility for Analytics checks; Firebase ingestion remains unverified and is not claimed. |
| Crashlytics delivery / symbolication / breadcrumbs (506–508) | BLOCKED | No authorized Firebase Console session, controlled corrupt-file fixture or release-device test. |
| Privacy / duplicate / QA classification (509–511) | FAIL / BLOCKED | QA-BUG-0001 passes host tests/local debug output. Distinctive payload use is not yet executed; Crashlytics payload inspection is blocked. QA-BUG-0002 sanitized local exception retest passes. Source audit confirms duplicate login reports (QA-BUG-0004); startup confirms network exception flood (QA-BUG-0005). QA-BUG-0003 and production QA-traffic policy remain open. |
| Offline telemetry / overhead / cleanup (512–514) | NOT RUN | These need an instrumented on-device pass. |
| Screen groups B–M | IN PROGRESS / NOT RUN | Case 1 is PASS. Case 7 debug variant was executed early and fixed; release variant remains NOT RUN. Resume case 2 before other ordered onboarding/login cases. No screen has been signed off. |
| Case 13 — Locale rendering | BLOCKED / partial | On Samsung locale `es-ES`, reachable Books, Series, Statistics and Settings surfaces rendered English with no visible clipping in captured areas; other routes not reached. Locale restored to `en-GB`. Initial run found QA-BUG-0008; fixed/retested with exactly one `app_launch_route_resolved` local debug event after relaunch. No Crashlytics delivery claim. Evidence: `case-013-locale-*.png`, `case-013-locale-run.txt`, `case-013-launch-event-retest.txt`. |

## Blockers and prerequisites

1. Storyteller demo credential is available for login UI use only; it must never be written to the repository, logs or QA evidence. Also need identify/create valid, corrupt and large EPUBs, Parrot Cloud credentials, Audiobookshelf access and a controlled fault-injection fixture.
2. Need authorized Firebase Console access for Crashlytics non-fatal delivery/symbolication and an agreed production QA-traffic policy. Firebase Analytics ingestion was waived by the user; no ingestion claim will be made. Opening Console redirects to Google sign-in; no authorized account is available in this browser session (tab `tab_53bffb7c-3a99-4faf-a663-fa33695c06e8`).
3. User authorized modification/deletion of all Parrot and connected demo accounts/data on the Samsung. Use the permission for controlled fixtures; do not affect unrelated apps, personal files or services.
4. Need run-specific continuous/logged evidence capture and complete device/settings baseline before executing screen cases.
5. Bluetooth/headset fixtures are not confirmed available; those cases will be BLOCKED if reached without the accessory. Authors navigation is conditionally disabled per source documentation and must be confirmed against installed navigation before disposition.
6. Permission now covers app data clearing, uninstall/reinstall, account logout, profile/book/settings changes, and connected demo data. Cases previously blocked only on preservation uncertainty must be rerun.
7. QA-BUG-0006 remains partially remediated/open: bounded typed diagnostic context/breadcrumbs exist and startup breadcrumbs were seen locally, but numerous legacy error call sites remain and Firebase Crashlytics delivery is unverified. QA-BUG-0007's safe setting buckets are fixed and local-provider retested (commit `26c8e314`).
8. QA-BUG-0008 startup Analytics is fixed/retested locally (commit `72c47ff8`). QA-BUG-0009 has cancellation-preserving fallback plus host failure-path and Samsung normal-startup checks (commit `ca9e6779`); device failure injection and Crashlytics delivery remain unverified.
9. Case 13 remains BLOCKED for unvisited app routes, despite partial visual evidence and an Analytics local-provider retest. Continue from case 1 with fresh evidence after reset.

## User-approved verification scope adjustment

On 2026-09-26 the user reported having tested debug event behavior and stated that seeing events in the debug build is sufficient. For Firebase **Analytics** event-delivery checks only, accept local debug-provider visibility and record Firebase ingestion tests 504–505 as DEFERRED by user waiver. Do not label debug logs as Firebase delivery: the current `DebugAnalyticsManager` writes local Kermit logs and does not forward to Firebase. Crashlytics non-fatal delivery/symbolication are separate and remain required unless the user also waives them.

## Final summary

**IN PROGRESS — no screen signed off.** Case 1 passes with a clean Samsung install and local Welcome event/breadcrumb evidence. Case 7's debug badge variant passes after commit `b720ca69`; release comparison is not run. Resume with case 2 using the user-supplied Storyteller demo credentials without writing secrets anywhere. QA-BUG-0010 Welcome event implementation is committed (`eead1977`) and case-1 exposure is retested; Get Started/guest outcomes remain pending cases 4–5. QA-BUG-0007 (`26c8e314`), QA-BUG-0008 (`72c47ff8`) and QA-BUG-0009 (`ca9e6779`) remain fixed as documented. QA-BUG-0006 is partially remediated/open: typed context and breadcrumb API/startup breadcrumbs exist, but legacy call sites and Firebase Crashlytics delivery remain unverified. QA-BUG-0011 debug badge fix/retest is documented. Setup cases 495–496 and local setup case 503 are not Firebase evidence. Firebase Analytics ingestion is waived; Crashlytics delivery remains in scope.
