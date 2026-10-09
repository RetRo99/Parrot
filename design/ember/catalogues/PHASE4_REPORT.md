# Phase 4 catalogue add / Get books report

**Branch:** `opds/phase4-screens`; **Implementation commit:** `0b7c17d8`
**Status:** The Add/Get books slice is implemented and verified. This is not the full 84-board catalogue experience: Browse, book detail, and Downloads destinations remain placeholders.

## Implemented

- Added a validated OPDS add flow with URL checks, duplicate protection, cancellation, HTTP policy, and certificate/authentication dialogs. Validation uses the production parser and transport without registering the catalogue or writing access status; credentials are saved only after successful validation.
- Added Project Gutenberg and Standard Ebooks presets in `catalogue-presets.json`, plus Get books, preset detail/sign-in, and custom add screens.
- Wired Library Add to Get books while preserving file import. Added OPDS to the existing Add a library type picker without changing Storyteller or Audiobookshelf flows.
- Added active/failed download count to the Downloads action and retained cover handling through `CatalogueImageModel`.
- Added tests for add validation, security/cancellation, and preset parsing/display-host behavior.

## Decisions

- Project Gutenberg OPDS: `https://www.gutenberg.org/ebooks/search.opds/`.
- Standard Ebooks OPDS: `https://standardebooks.org/feeds/opds`.
- Standard Ebooks `/terms` and `/terms-of-use` returned 404, so the preset has no terms link. Gutenberg's terms link and OPDS URL were verified.
- Android can confirm anonymous cleartext HTTP; iOS blocks HTTP. Credentials are not sent over HTTP or to non-HTTP(S) schemes.

## Verification

- `:feature:catalogue:ui:testAndroidHostTest` — passed (up-to-date on final run).
- `:lib:server-opds:testAndroidHostTest` and `:lib:server-opds:iosSimulatorArm64Test` — passed (up-to-date on final run).
- iOS simulator tests for Catalogue UI, Login UI, and Home UI — passed.
- Android `:androidApp:assembleDebug` — passed.
- iOS Simulator app build with `xcodebuild` — passed after `pod install --deployment` synchronized the local CocoaPods sandbox; locked dependency versions were unchanged.
- `git diff --check` — passed.
- Books UI tests — 67/71 passed on Android and iOS; the same four unrelated link-picker/review assertions failed (`LinkPickerViewModelTest` ×2 and `LinkReviewViewModelTest` ×2; failures at lines 124 and 164).

## Fixtures and remaining scope

- `tools/ember-fixtures/catalogue_capture.py --list` currently returns only `descriptionText`, a description-block sample rather than a Phase 4 screen. The existing Day/E-ink sample captures are not Add/Get books fixtures; no new screen fixtures were produced. A booted iPhone simulator was available, but the current app state was an audiobook reader, not a catalogue screen.
- Browse, book detail, Downloads, paging, and the rest of the 84 design boards are outside this implemented slice and remain follow-up work.
- Android ADB is present in the SDK and one physical device is connected; the capture helper requires explicit `--allow-device` before installing its isolated fixture APK on a real device. No real-device fixture install was performed.

## Unrelated known issues

- Previously recorded iOS test-source/link blockers in `feature:books:domain`, `feature:sync:data`, and a Compose test link involving `FirebaseCore` were not part of this final verification run.
- The existing `clearAllData()` sign-out behavior is unchanged.

## Phase 4 run 2 — 2026-10-08

**Status:** The Add/Get books slice now has feed-derived catalogue names, cover-loading safety, copy corrections, and captured Day/E-ink fixtures for the requested states. Browse, book detail, and Downloads destinations remain placeholders.

### Run 2 changes

- The validated first OPDS page supplies the saved catalogue name: trimmed, capped at 60 characters, and falling back to the address host when blank or missing. Preset names remain explicit.
- Added the typed `CatalogueCover(CatalogueImageModel)` entry point and an Android host test that prevents passing raw addresses to the image loader.
- Restored the Add a library back/top bar and the preset-detail back affordance; aligned labels, field errors, and key dialog copy with the boards. Android HTTP warning now uses “Go back” as the safe primary action; the blocked unsupported-sign-in dialog also says “Go back”.
- Corrected wrong-password and one-book removal copy. Duplicate-address copy retains a `TODO-design` note pending design review.
- Device wording uses the Android/iOS helper; the Android fixture can show the iPhone wording for its iOS-only board.

### Fixtures and comparison

- Captured 19 requested views in Day and E-ink, plus `catalogues` in Night: 39 PNGs with matching UI hierarchy XMLs under `design/screens/`.
- Captures were made only on `emulator-5554`. The connected physical device (`10.41.65.3:5555`) was not installed or used.
- Found/fixed: the Add and preset-detail back bars were missing; the preset-detail tile showed `P` rather than the board’s `G`; address/account labels and field errors lacked the board’s emphasis; the web-page error used straight quotes; the HTTP and unsupported-blocked dialog actions had the wrong safe-action copy; dialog addresses lacked emphasis. The fixture checker also corrupted literal Unicode ellipses and used a case-sensitive popup-window focus check; both harness issues are fixed. The empty-catalogues expectation now matches its rendered all-caps heading.
- Left/accepted differences: the Get books rows show host plus status as required by the written spec, while the older boards omit some status detail; the Project Gutenberg catalogue-row marker remains its title initial (`P`) rather than the board’s `G`. The add-sign-in fixture includes the password helper/visibility affordance required by the copy reference, though these are not shown on its older board. Preset detail retains its required Add catalogue action although the board image omits it. The sign-in fixture uses the previously agreed simplified browse backdrop because Browse is still a placeholder. Shared `EmberDialog` text-action layout also remains different from the boards’ filled/stacked primary-button treatment. E-ink boards are not available for these requested Add/Get books states.

### Verification before final app builds

- `:feature:catalogue:ui:testAndroidHostTest` — 28/28 passed; `:feature:catalogue:ui:iosSimulatorArm64Test` — 27/27 passed.
- `:lib:server:api:testAndroidHostTest` and `:lib:server:api:iosSimulatorArm64Test` — 39/39 passed each.
- `:lib:server-opds:testAndroidHostTest` and `:lib:server-opds:iosSimulatorArm64Test` — 52/52 passed each.
- `:base-ui:iosSimulatorArm64Test` — 7/7 passed. Unchanged Login UI tests — 45/45 passed on Android host and 45/45 on iOS Simulator.
- `:tools:ember-fixtures:assembleDebug` — passed. Fixture capture checks passed for all 39 PNG/XML pairs. `git diff --check` — passed.
- `:androidApp:assembleDebug` — passed in the combined final-build run.
- `:composeApp:linkDebugFrameworkIosSimulatorArm64` — passed on retry with an 8 GiB Gradle heap. The initial combined attempt reached the iOS link but ran out of the configured 4 GiB heap; no source changes were needed.

### Run 2 follow-up

- Final Android and iOS app build targets passed. The iOS link retry completed successfully; its memory override was command-local and did not change project configuration.

## Phase 4 run 3 — catalogue browser — 2026-10-09

**Status:** the `CatalogueBrowse` route now opens the real catalogue browser. The book page, Choose a file, row downloads, Downloads, Libraries and catalogue settings are still to come; tapping a book opens the placeholder book page.

### What was built

- `feature/catalogue/ui/.../browse`: `CatalogueBrowser` (all logic, no Compose), `CatalogueBrowseScreen`, `CatalogueBrowseViewModel`, `CatalogueBrowseGateway`, models. 43 tests for the logic and models were written before it.
- Route references now carry a page (`CataloguePlace`) or a book (`CatalogueBookPlace`); they are forgotten when a catalogue is turned off, removed or moved, and all of them on a profile change.
- `lib/server/api`: `localNetworkHostLeaving`. `lib/server-opds`: a page address stays valid in a later session of the same profile, catalogue and address (needed to reload a page after signing in); searches and file locations stay bound to their session.
- `HomeNavigationStateHolder.replaceCurrent` for a page that turns out to be one book.
- 21 fixtures (14 boards + 7 page-failure reasons), captured on `emulator-5554` only: Day and E-ink for all, Night for `browse` and `list`.

### Behaviour worth knowing

- **Page limit:** a list keeps at most 20 pages. Loading page 21 drops page 1 from memory; its request is remembered. Scrolling back near the top fetches the dropped page again (Day/Night by itself with a "Loading more…" row at the top, E-ink with a "Load earlier books" button) and then drops the page at the far end, which is reached again through the next link of the page before it. Filter chips, shelves and folders stay throughout.
- **Late results:** every answer is checked against the list and first-load it was asked for; requests are cancelled when their list is replaced or the screen is left.
- **Gutenberg:** its lists are entries without files and without an author element, so they are drawn as folder rows (title, author as the subtitle), not as book rows with covers. Opening one fetches its page, which the grouping rule turns into the book page.

### Verification

- Android host / iOS simulator: `lib/server/api` 42/42 and 42/42; `lib/server-opds` 52/52 and 52/52; `feature/catalogue/ui` 72/72 and 71/71; `feature/home/ui` 81/81 and 87/87; `composeApp` Android host 55/55 (iOS not run: known FirebaseCore link error).
- `:tools:ember-fixtures:assembleDebug` passed; 44 captures taken with their hierarchy checks.
- Not tried against a real catalogue inside the app.
- `./gradlew :androidApp:assembleDebug :composeApp:linkDebugFrameworkIosSimulatorArm64 --max-workers=2` — both passed in one run, no heap retry needed.
