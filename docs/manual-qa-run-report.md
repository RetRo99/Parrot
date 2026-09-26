# Parrot Samsung QA run report

## Run manifest

- **Run date:** 2026-09-26 (local device time; timezone not independently verified)
- **Repository:** `/Users/rokretar/StudioProjects/StoryTellerKMP`
- **Installed app source commit:** `492f1ac1a17d744f6d0d73332e4d97c003366bc3` (`fix(navigation): handle root Welcome Back safely [QA-BUG-0018, test 522]`). Cases 3/5 used `54708a90`; case 2 used `20c39318`; each tested build is separately hash-matched in its evidence.
- **Working tree at start:** clean except for the user-provided, untracked `docs/manual-qa-goal.md` and `docs/manual-qa-test-plan.md`; preserve both.
- **Required physical device:** serial `RFCWC0SSVDM`; model `SM-S921B` (Galaxy S24)
- **OS:** Android 16 / API 36; One UI 8.0 (device property `ro.build.version.oneui=80500`); build `S921BXXSGDZG1`; security patch 2026-07-05
- **Display/navigation baseline:** portrait, 1080×2340 px, physical density 480 dpi / override 420 dpi, font scale 1.0, gesture navigation (`navigation_mode=2`)
- **Power/network/permissions baseline:** battery saver off; airplane mode off; active network appears Wi‑Fi; app storage volume previously measured at 65 GB free of 224 GB. Notification permission was granted before the authorized Parrot uninstall; after clean reinstall, `POST_NOTIFICATIONS` is denied and the package has no other granted runtime permissions. Other settings/permissions and active profile are not fully inventoried.
- **Installed package:** `com.retro99.parrot`, version `0.4.5` (version code `21`), target SDK 36, debuggable, installer reported `null` (ADB/package-manager install source unavailable)
- **Installed build provenance:** rebuilt and reinstalled after `492f1ac1a17d744f6d0d73332e4d97c003366bc3`; package version `0.4.5` (21), debuggable, installer `null`. Local and Samsung-pulled debug APK SHA-256 both `630fdab19b8b0b8c5a4d317c1c25eff68457f686b9658a00a7b066a0c5841fba`.
- **Latest installed instrumentation build:** debug package `com.retro99.parrot` 0.4.5 (21), source commit `492f1ac1a17d744f6d0d73332e4d97c003366bc3`; exact hash and Samsung case-522/756 evidence: [root Back run record](manual-qa-evidence/2026-09-26/case-522-756-welcome-system-back-run.txt). It remains a debug-only analytics provider.
- **Earlier release artifact:** a release APK and mapping were built before the Welcome instrumentation work; release SHA-256 `b438a4b05cb0a99ac05817e5faf05e48477028d228318d05e7736088e01bb222`, mapping SHA-256 `3ff347e6cb669d70a3309da0713a9d74e7e46d7448e5f3ee66018f181a6e2b91`. It has not been installed on the Samsung or re-built from the current commit.
- **Latest verification commands:** `:feature:login:ui:iosSimulatorArm64Test`, `:lib:analytics:implementation:testAndroidHostTest` and `:androidApp:assembleDebug` completed successfully for QA-BUG-0018/0019. Earlier Home exposure host tests passed. Firebase Console delivery is a separate blocker.
- **Current visible destination:** Root Welcome after case 522 system Back, exit and relaunch PASS. The latest screenshot confirms Welcome, DEBUG badge and usable onboarding controls. Case 6 evidence confirms no toolbar Back arrow is offered on root Welcome. The local EPUB used in case 3 was removed from Parrot's database by the authorized reset; earlier case-3 PASS evidence remains valid. No temporary Downloads source remains.
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

Case evidence and sanitized excerpts are stored under [`manual-qa-evidence/`](manual-qa-evidence/). No Firebase test property has been set. On 2026-09-26 the user authorized Parrot/demo-data reset; only Parrot was uninstalled/reinstalled or cleared (`pm clear com.retro99.parrot`) on serial `RFCWC0SSVDM`. One controlled EPUB was temporarily copied to Downloads for import and then removed, along with the exact DocumentsUI search-history entry created for its filename. No unrelated apps, files, phone settings or services were changed.

## Progress

**Current screen:** Welcome, case 7 release-badge comparison queued. Cases 522/756 PASS on Samsung. Case 6 is N-A: no toolbar Back arrow is offered on root Welcome. QA-BUG-0018 final-entry guarding and explicit Android root Back exit passed on the hash-matched build; QA-BUG-0019's missing event was reproduced on an intermediate build, then one event and breadcrumb pair passed on the final build. Cases 3/5 previously passed in one shared Samsung run with local EPUB persistence/import; case 4 was exercised early as case 2's Login-route precondition. QA-BUG-0016 is partially verified: Login-success, authenticated cold-start, guest-selection and guest-cold-start Home exposures passed; recomposition/tab-switch deduplication remains NOT RUN. QA-BUG-0017 common fault-injection/retry regression test and Samsung guest success-path smoke passed, but case 755's device failure branch is BLOCKED without a safe preference-write fault fixture. Login inventory remains mapped to cases 15–36, 515–534 and 751–753; case 753 is BLOCKED for missing safe persistence fault injection. Cases 1–5 are PASS as individually recorded, case 6 is N-A, case 522/756 PASS; case 7 debug badge passed, and release comparison remains NOT RUN pending QA-BUG-0020's Samsung confirmation. Cases 8–14 follow catalogue order; case 8 is DEFERRED (no e-ink hardware). Case 13 remains partial/BLOCKED for unvisited routes. The case-3 local EPUB data was cleared under authorization and can be recreated from the repository fixture. No temporary Downloads file or device-wide setting change remains.

| Area | Status | Notes |
|---|---|---|
| Device selection (495) | PASS | Physical SM-S921B enumerated and all inspected with explicit serial. |
| Tested build identity (496) | PASS | QA-BUG-0018 debug APK from code commit `492f1ac1` is installed; local and Samsung-pulled hashes match at `630fdab1…c5841fba`. See [case-522/756 run evidence](manual-qa-evidence/2026-09-26/case-522-756-welcome-system-back-run.txt). Earlier case-2 and case-3 builds are separately recorded. |
| Device baseline (497) | BLOCKED | Core values recorded; active profile, complete permission/settings inventory and repeatable test fixture still missing. |
| Fixtures (S2–S5; 498–501) | IN PROGRESS / partial | Storyteller demo credential is available for UI-only login; mixed-media/cloud fixtures and controlled media/failure files still require inventory/generation. User authorizes Parrot demo data/accounts to be changed or deleted. Device can toggle airplane mode and kill processes; safe constrained-storage fixture remains absent. |
| Labelled log capture (502) | NOT RUN | Timestamp-filtered logcat excerpt captured for case 503; continuous capture was not started. |
| Debug provider (503) | PASS (local provider) | Local Analytics events visible. QA-BUG-0005 baseline had 19 exception records; retest 1 had six; retest 2 showed zero markers/nine events; attempt 3 counts were invalid stale buffer output and excluded; retest 4 showed zero exception markers/two events; post-commit retest 5 repeated zero markers/two events on the rebuilt, hash-matched APK. Full performance case 513 remains NOT RUN. See case-503 evidence files. |
| Firebase Analytics delivery (504–505) | DEFERRED (user waiver) | User accepts debug-provider event visibility for Analytics checks; Firebase ingestion remains unverified and is not claimed. |
| Crashlytics delivery / symbolication / breadcrumbs (506–508) | BLOCKED | No authorized Firebase Console session, controlled corrupt-file fixture or release-device test. |
| Privacy / duplicate / QA classification (509–511) | BLOCKED / PARTIAL | QA-BUG-0001 provider allowlist and QA-BUG-0002 sanitizer pass locally. QA-BUG-0003 identity fix/startup clearing are committed and observed; a credentials success journey was exercised for case 2, but the full approved identity-payload policy check remains pending. QA-BUG-0004 centralization is committed and unit/build verified; Samsung failure retest and Crashlytics delivery remain pending/blocked. Distinctive payload and production QA-traffic policy remain open. |
| Offline telemetry / overhead / cleanup (512–514) | NOT RUN | These need an instrumented on-device pass. |
| Screen groups B–M | IN PROGRESS / NOT RUN | Cases 1–5 are PASS as individually recorded; case 6 is N-A because its arrow is unavailable on root Welcome; cases 522/756 Back exit/event behavior PASS. Case 7 debug variant is verified while release remains NOT RUN. QA-BUG-0016 Login/authenticated/guest Home exposures are Samsung-retested; recomposition/tab deduplication remains pending. QA-BUG-0017 common failure/retry test and guest success path pass, but device failure-branch case 755 is blocked. QA-BUG-0018/0019 fixes are Samsung-retested. No screen has been signed off. |
| Case 13 — Locale rendering | BLOCKED / partial | On Samsung locale `es-ES`, reachable Books, Series, Statistics and Settings surfaces rendered English with no visible clipping in captured areas; other routes not reached. Locale restored to `en-GB`. Initial run found QA-BUG-0008; fixed/retested with exactly one `app_launch_route_resolved` local debug event after relaunch. No Crashlytics delivery claim. Evidence: `case-013-locale-*.png`, `case-013-locale-run.txt`, `case-013-launch-event-retest.txt`. |

## Blockers and prerequisites

1. Credentials are not retained; the demo sign-in for case 2 was completed only in Parrot UI, and its local app data was later cleared. Separate fixtures still needed: corrupt/large EPUBs, Parrot Cloud credentials and Audiobookshelf access. The local EPUB fixture at `iosApp/iosApp/sample-books/PrideAndPrejudice.epub` supported the guest run. QA-BUG-0013/0015 case 753 and QA-BUG-0017 case 755 lack a safe way to inject app-scoped registry/preferences write failure on-device; they are BLOCKED, not passed.
2. Need authorized Firebase Console access for Crashlytics non-fatal delivery/symbolication and an agreed production QA-traffic policy. Firebase Analytics ingestion was waived by the user; no ingestion claim will be made. Opening Console redirects to Google sign-in; no authorized account is available in this browser session (tab `tab_53bffb7c-3a99-4faf-a663-fa33695c06e8`).
3. User authorized modification/deletion of all Parrot and connected demo accounts/data on the Samsung. Use the permission for controlled fixtures; do not affect unrelated apps, personal files or services.
4. Need run-specific continuous/logged evidence capture and complete device/settings baseline. Latest build/device identity is recorded; active profile and all settings/permissions remain incomplete. Case 502 continuous capture is still NOT RUN.
5. Bluetooth/headset fixtures are not confirmed available; those cases will be BLOCKED if reached without the accessory. Authors navigation is conditionally disabled per source documentation and must be confirmed against installed navigation before disposition.
6. Permission now covers app data clearing, uninstall/reinstall, account logout, profile/book/settings changes, and connected demo data. Cases previously blocked only on preservation uncertainty must be rerun.
7. QA-BUG-0006 remains partially remediated/open: bounded typed diagnostic context/breadcrumbs exist and startup/Login breadcrumbs are source-instrumented, but Login runtime failure context and numerous legacy call sites remain unverified; Firebase Crashlytics delivery is blocked. QA-BUG-0007's safe setting buckets are fixed and local-provider retested (commit `26c8e314`).
8. QA-BUG-0008 startup Analytics is fixed/retested locally (commit `72c47ff8`). QA-BUG-0009 has cancellation-preserving fallback plus host failure-path and Samsung normal-startup checks (commit `ca9e6779`); device failure injection and Crashlytics delivery remain unverified.
9. Case 13 remains BLOCKED for unvisited app routes, despite partial visual evidence and an Analytics local-provider retest. Continue from case 1 with fresh evidence after reset.

## User-approved verification scope adjustment

On 2026-09-26 the user reported having tested debug event behavior and stated that seeing events in the debug build is sufficient. For Firebase **Analytics** event-delivery checks only, accept local debug-provider visibility and record Firebase ingestion tests 504–505 as DEFERRED by user waiver. Do not label debug logs as Firebase delivery: the current `DebugAnalyticsManager` writes local Kermit logs and does not forward to Firebase. Crashlytics non-fatal delivery/symbolication are separate and remain required unless the user also waives them.

## Final summary

**IN PROGRESS — no screen signed off.** Cases 1–5 PASS with Samsung evidence; case 6 is N-A because root Welcome has no toolbar Back arrow; case 7 debug badge passes while release comparison remains NOT RUN pending Samsung confirmation of QA-BUG-0020; cases 522/756 root system Back, usage event and clean relaunch PASS on source commit `492f1ac1`, local/Samsung APK SHA-256 `630fdab1…c5841fba`. A minified Firebase-configured release candidate from `a90bbe8c` is built and signature-compatible; do not claim installed/tested until its on-device hash is verified. The earlier case-2 build remains documented at `20c39318`; case 3 verified guest cold-start/local-library retention and its shared run covered case 5 import. QA-BUG-0016 Home exposure passed Login, authenticated startup, guest selection and guest startup; recomposition/tab deduplication remains NOT RUN. QA-BUG-0017 common injected failure/retry test and Samsung success path pass, but device case 755 is BLOCKED. QA-BUG-0018 final-entry guard/exit and QA-BUG-0019 Back telemetry are fixed and Samsung-retested. Next catalogue actions after case 7 are case 8 DEFERRED (no e-ink hardware), then cases 9–14. Case 753 is BLOCKED pending safe registry/preferences fault injection. QA-BUG-0003 payload-policy verification remains pending; QA-BUG-0004 Samsung failure-path retest is pending; QA-BUG-0014 delayed rapid-submit device retest is pending; QA-BUG-0015 device fault-path is blocked. QA-BUG-0007 (`26c8e314`), QA-BUG-0008 (`72c47ff8`) and QA-BUG-0009 (`ca9e6779`) remain fixed as documented. QA-BUG-0006 is partially remediated/open; Crashlytics delivery remains unverified. Setup cases 495–496 and local setup case 503 are not Firebase evidence. Firebase Analytics ingestion is waived; Crashlytics delivery remains in scope.
