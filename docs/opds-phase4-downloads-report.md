# Phase 4 Downloads and list actions — 2026-10-09

Scope: Downloads, list-row download/cancel, preset-supplied book-link rows,
catalogue-settings navigation and download announcements. Not a sign-off of the
full Phase 4 Android/iOS gate or Samsung QA programme.

## Verification before final app builds

- Tests were written first for download action mapping, controller actions and
  retention, tapped-row preparation/cancel/fallback, preset parsing/routing, and
  announcement transitions. Expected compile-red runs are retained in the local
  `opencode` temporary directory (`opds-red-*.log`).
- The initial fixture APK and catalogue host tests passed before fixture capture.
  Downloads, failures, empty Downloads and list states were captured on
  `emulator-5554` in Day/E-ink, plus list states in Night. Every capture with a
  board was visually compared before the runtime work. Empty Downloads has no board.
- Font metrics, title-cover colours and wrapping differ from the boards. Ember's
  existing fonts/tokens are retained; actions have at least 48dp targets. Failure
  sizes now omit redundant `.0` (`620 MB`, `48 MB`); Day/E-ink captures confirm it.
- Catalogue UI, Home UI and Server API Android-host/iOS-simulator tests pass.
  Exact final counts and app build outcomes will be appended after the final checks.
- `:tools:ember-fixtures:testDebugUnitTest` has no test sources. Its debug APK builds.
  Translations has no test source set; both targets compile through their consumers.

## Runtime evidence and discoveries

Samsung `RFCWC0SSVDM`, model SM-S921B, Android 16. APK update installation succeeded,
without app-data clearing. A disposable profile was created for a fresh-state run;
the existing default profile and its books were not removed or altered.

1. Activating the disposable profile crashed before Get books. Logcat identifies
   `BooksListViewModel.observeFavorites` / `DatabaseManager.getDatabase`:
   `No active user profile. Cannot access database.` No catalogue stack frame is
   the cause. This unrelated profile-switch/database race is recorded, not fixed.
2. Relaunch recovered to Welcome in the fresh profile. Choosing local files and
   cancelling the picker reached a Library with **0 books**.
3. Library → Add → Get books succeeded. Built-in presets were missing. Inspection
   of the actual APK proved `catalogue-presets.json` was absent. The module now
   enables Android resources, consistent with Base UI/Translations.
4. The new APK-packaging check failed before the fix and passes for both the app
   and fixture APK after it. Both presets are present; only Project Gutenberg has
   the link-book hint. The corrected app APK SHA-256 is
   `e43b43a6445cf4b72369b695817c2036034320d39aa0d161cfe32e8074faee25`.
5. The corrected APK was update-installed. A Samsung system-update screen then
   took the foreground. Testing stopped without interacting with the updater.
   Adding the built-in preset, search/download/reader and baseline restoration
   on Samsung remain **BLOCKED** pending return to Parrot. The disposable profile
   remains; no system settings, unrelated files or accounts were changed.

Raw runtime captures stay local. The screenshot of the fresh Get books state
contains only public catalogue UI; system-picker listings are not committed.
No Firebase ingestion, Crashlytics delivery or audible TalkBack/VoiceOver result
is claimed. The previously requested emulator journey is a separate check, not
a substitute Samsung PASS.

## State/action contract

Downloads:

- Waiting: `Waiting to download…` → Cancel (deletes the request).
- Known size: `<received> of <total> MB`, progress bar → Cancel.
- Unknown size: `Downloading · <received> MB so far`, no bar → Cancel.
- Checking and Adding: `Adding to library…` → no action.
- Done: `In your library` → Open the local book in Reader.
- Connection: `Couldn't finish · the connection was lost` → Retry.
- Too large: `Too large to add · <size> (the limit is <limit>)` → Dismiss.
- Storage: `Not enough space on <device> · needs <size>` → Retry.
- Invalid: `This file isn't a book Parrot can open` → Dismiss.
- Protected: `This file is protected (DRM) and can't be opened` → Dismiss.
- Refused: `<Catalogue> didn't allow this download` → Retry.
- Sign-in: `Sign-in needed for <catalogue>` → Sign in; verified details resume
  only that catalogue's downloads.
- Interrupted: `Stopped when Parrot closed` → Start again.

Only the first failed/interrupted row has a filled button. Completed rows are
purged when leaving Downloads or just after 24 hours; unfinished rows are retained.
The timer uses the queue's strict-older-than cutoff (deadline plus one millisecond).

List rows:

- Available: download arrow → prepare only the tapped book.
- Getting ready: `Getting ready…` → separate cancel target; late detail replies
  cannot enqueue work.
- Waiting: `Waiting to download…` → Cancel.
- Known size: progress bar and percentage → Cancel.
- Unknown size: `Downloading · <received> MB so far`, no bar → Cancel.
- Checking/Adding: `Adding to library…` → no action.
- Done/In library: `In your library` → no download action.
- Failed/interrupted: ordinary download arrow; tapping opens the book's repair
  action rather than falsely reporting a restarted download.

The row body still opens the book page. Download/cancel is a separate 48dp target.
One edition uses the first openable file in catalogue order. Several editions or
no openable file silently open the book page. Successful queueing shows the
existing verbatim download notice with View → Downloads.

## Book-link rule and retained identity

`listEntriesAreBooks` is optional, persisted with ServerConfig, defaults false,
and is set only by shipped preset data (Project Gutenberg). Address-added
catalogues remain false, even at the same provider address.

With the flag, a paginated feed or search-result route may treat a link-only
navigation entry as a book if it has non-empty plain-text content/summary and a
queryless, fragmentless resource link. Pure navigation/start pages stay folders;
query-based author/subject links stay folders. No provider name, URL path pattern,
title or image bytes are consulted. Linked book rows ignore generic thumbnails
and use title covers; their text body is the author line. Listing identity is
carried through detail/edition fallback, queue provenance and library lookup.

The actual first page, list and `query=whale` XML responses are saved unchanged
under `feature/catalogue/ui/src/commonTest/resources/linked-books`. Common tests
run them through the production parser on both targets; Android additionally
checks byte parity with the embedded iOS fixture registry. First page gives three
folders; list/search give 25 linked book rows only with the flag. The preset now
starts at its advertised navigation page `/ebooks.opds/`.

## Accessibility

A shared transition tracker announces 25/50/75% only for visible book identities
or Downloads; unknown size once at start; Waiting once when queued; Done/Failed
once throughout the app; Cancelled only after an explicit successful cancel (or
cancelled preparation). Historical terminal rows do not announce on startup.
Profile change resets transition/visibility state and drains queued old titles.
Android uses native accessibility announcement events; iOS queues announcement
speech. Duplicate per-recomposition book-card live regions were removed.

## Boundaries

No existing test assertions were weakened or skipped; no existing test files
needed changing. New tests cover profile changes, including leave before the
profile flow catches up, late row replies and preserved listing identity.

Outside the permitted implementation folders, changes are limited to:

- `lib/server/api/.../ServerConfig.kt` and its new serialization test;
- this report and the Phase 4 status link in the implementation plan.
- Samsung QA results, bug register, run note and a sanitized evidence note for
  the actually observed profile-activation crash (QA-BUG-0101).

The four known Books UI test failures, composeApp iOS **test** FirebaseCore link
problem, Books Domain/Sync Data iOS test compilation failures and ineffective
sign-out cleaner are untouched. Updated-copy/sample acquisition and the complete
custom-server Android/iOS Phase 4 gate remain deferred. No new TODO-design strings.

## Emulator continuation and preset-effect fix

The independent fresh-profile run on `emulator-5554` reproduced the profile
activation crash; relaunch recovered. The Library had 0 books and Get books had
no registered catalogue. Built-in presets now appeared.

The first preset-add attempt stayed Checking with no completion. Its
`LaunchedEffect(flow, presetCheck)` set `presetCheck = null` before the suspending
submit, cancelling itself. A regression test holds validation pending and proves
the request key is consumed only after validation/persistence; the production
effect now uses that ordering. Both catalogue host/simulator tests and QA APK
assembly pass after the fix.

Retest: adding built-in Project Gutenberg succeeded. Its first page showed
Popular/Latest/Random folders, and Popular showed title-cover book rows with
independent download icons. Title search **failed at the search response (403)**.
The live OpenSearch descriptor advertises
`http://m.gutenberg.org/ebooks/search.opds/?query={searchTerms}`. An independent
HTTPS GET of the www-host title search returns 200, but production follows the
advertised template. No provider-specific rewrite or HTTPS/address substitution
was added. Therefore the exact uninterrupted requested journey is **not PASS**.

After clearing search, the Pride and Prejudice row's download action correctly
opened its book page: the live detail feed has two publications/editions (without
images and with images). This verifies multi-edition fallback, not direct
single-edition row queueing. Downloading the default 0.6 MB EPUB on the book page
showed live progress and a View notice. The notice expired before automation
could tap it; Downloads was reached through Get books instead.

Downloads showed `In your library` and Open. Open entered Reader and rendered
the cover; right-edge page taps advanced to usable book content (Page 2 of 27).
Returning to Downloads showed the empty state, confirming completed-row purge
on leave without removing the library book. Direct single-edition row queueing
and search-result rows remain controller/parser-test evidence, not a successful
live-provider demonstration.

## Final checks

Before the preset-effect follow-up, the exact combined command
`./gradlew :androidApp:assembleDebug :composeApp:linkDebugFrameworkIosSimulatorArm64 --max-workers=2`
passed in 1m 52s after the initial report commit. The repeat on the final
follow-up commit, exact counts and second-book check are recorded below without
replacing the earlier failures.

### Final results (source `703c82fb`)

The combined app-build command passed again, **BUILD SUCCESSFUL in 1m 14s**.
Android assemble was up to date after the successful QA assembly; the iOS
framework link executed. No OOM/heap override or project memory change was needed.
Final QA/app APK SHA-256:
`632a1dad51d2dc40f2e3393dd142d068f15df3162fa75c9246f7293c5128f515`.

| Touched module | Android host passed/total | iOS simulator passed/total |
|---|---:|---:|
| feature/catalogue/ui | 131/131 | 129/129 |
| feature/home/ui | 84/84 | 90/90 |
| lib/server/api | 43/43 | 43/43 |
| translations | No configured tests; Android compiled | No configured tests; native compiled |
| tools/ember-fixtures | No test sources; testDebugUnitTest NO-SOURCE, APK built | Android-only tool |

Final/relevant successful commands (all Gradle invocations use `--max-workers=2`):

```sh
./gradlew :feature:catalogue:ui:testAndroidHostTest :feature:home:ui:testAndroidHostTest :lib:server:api:testAndroidHostTest :tools:ember-fixtures:testDebugUnitTest :tools:ember-fixtures:assembleDebug --max-workers=2
./gradlew :feature:catalogue:ui:testAndroidHostTest :feature:home:ui:testAndroidHostTest :lib:server:api:testAndroidHostTest :feature:catalogue:ui:iosSimulatorArm64Test :feature:home:ui:iosSimulatorArm64Test :lib:server:api:iosSimulatorArm64Test --max-workers=2
./gradlew :feature:catalogue:ui:testAndroidHostTest :feature:home:ui:testAndroidHostTest :lib:server:api:testAndroidHostTest :androidApp:assembleDebug :tools:ember-fixtures:assembleDebug --max-workers=2
./gradlew :feature:catalogue:ui:iosSimulatorArm64Test :feature:home:ui:iosSimulatorArm64Test :lib:server:api:iosSimulatorArm64Test :tools:ember-fixtures:testDebugUnitTest --max-workers=2
./gradlew :feature:catalogue:ui:testAndroidHostTest :feature:catalogue:ui:iosSimulatorArm64Test :androidApp:assembleDebug --max-workers=2
./gradlew :androidApp:assembleDebug :composeApp:linkDebugFrameworkIosSimulatorArm64 --max-workers=2
python3 tools/ember-fixtures/check_catalogue_assets.py androidApp/build/outputs/apk/debug/androidApp-debug.apk
python3 tools/ember-fixtures/check_catalogue_assets.py tools/ember-fixtures/build/outputs/apk/debug/ember-fixtures-debug.apk
python3 tools/ember-fixtures/catalogue_capture.py --serial emulator-5554 --views downloads downloadsFailed downloadsEmpty listStates --themes day eink --skip-install
python3 tools/ember-fixtures/catalogue_capture.py --serial emulator-5554 --views listStates --themes night --skip-install
git diff --check
```

The first native announcement compilation failed (NSAttributedString initializer);
it was corrected to Foundation's `create`, then all three module simulator suites
passed. Expected compile-red test runs are not counted as successful suites.
Fixture capture hit install-overlay/idle timeouts during intermediate attempts;
the initial required set and the final full Day/E-ink set plus Night list states
all completed. The final corrected Day/E-ink failure boards were compared again;
no further visual polishing was performed.

The second requested download was initiated from Moby Dick's book page (default
0.7 MB EPUB), showed progress and completed. On return to Popular, both it and
Pride and Prejudice showed In your library with no download icon; Library showed
exactly **2 books** in the disposable profile. The original emulator profile was
restored; switching again hit the same unrelated profile/database crash, and
relaunch recovered the original **5-book** Library and prior continue-reading
position. QA books/source remain only in the separate disposable profile.

Samsung remains stopped at the updater; its disposable profile and active-profile
restoration are still pending. No complete Samsung/Phase 4 provider gate or audible
screen-reader pass is claimed. The exact emulator journey remains partial at
title search (403) and direct row queueing (two-edition fallback), despite the
successful independent Downloads/Reader/second-download checks.

Implementation steps 1–4 are complete; live-provider acceptance is **partial**.
The committed report precedes both combined long builds, and this final update
records their outcomes. The only untracked path left is the untouched `.opencode/`.
