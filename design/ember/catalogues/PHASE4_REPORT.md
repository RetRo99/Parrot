# Phase 4 catalogue add / Get books report

**Branch:** `opds/phase4-screens`  
**Baseline:** `b24b9b87`; add-flow test-first slice `81fc7b5c`  
**Status:** implementation and final platform verification in progress

## Scope implemented

- Added a validated OPDS add flow with duplicate checks, cancellation, distinct checking/sign-in phases, HTTP confirmation/blocking, certificate and unsupported-auth dialogs, and account persistence only after validation succeeds.
- Added transient repository validation using the production OPDS parser and transport without registering a source or persisting access status during validation.
- Added Project Gutenberg and Standard Ebooks presets in `catalogue-presets.json`; the Standard Ebooks terms link is omitted because its terms URLs returned 404. Gutenberg's terms link and OPDS URL were verified.
- Added Get books, preset detail, custom add, and preset sign-in UI. The Library Add action offers Get books and preserves file import; OPDS is available from the existing Add a library type picker without changing Storyteller or Audiobookshelf selection behavior.
- Added active/failed download count to the Get books Downloads action and kept cover loading on the existing `CatalogueImageModel` path.
- Added common tests for add validation/security/cancellation and preset parsing/display host.

## Decisions

- Project Gutenberg OPDS URL: `https://www.gutenberg.org/ebooks/search.opds/`.
- Standard Ebooks OPDS URL: `https://standardebooks.org/feeds/opds`.
- Standard Ebooks' `/terms` and `/terms-of-use` returned 404, so the preset does not label its collections policy as terms of use.
- Cleartext HTTP is confirmable for anonymous catalogues on Android and blocked on iOS; credentials are never sent over HTTP or to non-HTTP(S) schemes.

## Verification so far

- `:feature:catalogue:ui:testAndroidHostTest` — passed.
- `:feature:login:ui:compileAndroidMain` — passed.
- `:feature:home:ui:compileAndroidMain` — passed.
- `:lib:server-opds:compileAndroidMain` — passed (reported up to date).
- `:feature:books:ui:testAndroidHostTest` — 67 passed, 4 existing link-picker/review assertions failed (`LinkPickerViewModelTest` ×2, `LinkReviewViewModelTest` ×2; failures at lines 124 and 164).
- `git diff --check` — passed.
- Final iOS checks, application builds, and visual fixture capture are pending.

## Existing verification blockers

- The previously recorded iOS Compose test-link failure involving `FirebaseCore` and iOS test-source compilation failures in `feature:books:domain` and `feature:sync:data` remain baseline issues to recheck where applicable.
- `clearAllData()` previously did nothing on sign-out; this catalogue change does not address that unrelated behavior.
- No Android ADB executable is available in this environment. A booted iOS simulator is available; visual capture is still to be attempted there.

## Final results

To be updated after the requested iOS/Android checks and any available fixture capture.
