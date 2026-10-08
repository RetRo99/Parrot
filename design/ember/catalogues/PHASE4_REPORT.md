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
