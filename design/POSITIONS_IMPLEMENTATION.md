# Reading positions — Ember implementation and verification

## Test cleanup rule

When removing test data, remove the queued outbox mutation as well as its row, or
let the change sync and undo it through the normal UI. Removing only a local row
does not cancel an upload and a later pull can restore it.

## Review follow-up

`tile` removed in favor of `bg`; selected fill renamed `surfaceSelected` with an
explicit white/surface E-ink value. Shared `date_yesterday` replaces the Book details
and positions-specific strings; positions uses `calendarDateLabel()` for calendar-day
boundaries. Zero selection says "Choose a version to update" and cannot submit.
Unused old positions title/conflict resources removed (historical docs retain names).

### Follow-up Samsung real apply and sync

Created a disposable UI link from the downloaded Psalm Storyteller copy to The Crossing
Cloud test copy. Starting local total progression: source 0.8796296296296297; target 0.
Selected the source's local candidate and ticked only the approximate Cloud target.
Apply closed the sheet and reloaded cards: target About 88%, manual/Set from Storyteller,
Latest. Snackbar: "Updated 1 version · saved on this phone, syncing". Direct capture:
`design/screens/positions-demo-applied.png`.

After normal UI Sync now, target remote_revision advanced 38 → 39 and the target's
reading_position mutation left the outbox. The target retained total progression
0.8796296296296297 and origin manual. This verifies Cloud acknowledgement, not merely
the snackbar. Opening target Book details shows 87% (existing truncating formatter).
Reader-open verification is incomplete: the cloud-only target offers Download eBook,
but tapping it did not download/open the test file during this run.

No genuine partial apply was produced and no `positions-demo-partial.png` is fabricated.
An offline server push is asynchronous after successful local apply, so it does not
cause the apply result to be partial. The existing isolated partial fixture remains
separate from a real production-app failure test.

Unlinked using normal UI, then Sync now. Final database: zero active links; no book_link
or book_link_decision mutations in the outbox. The disposable target intentionally
retains the applied 88% position, acknowledged at Cloud revision 39. Normal unlink
tombstones and Never decisions remain synced; no normal UI to clear a Never decision
was found. No row-only cleanup was used.

### Baseline link tests: report only

At 7a2d2a76 (5a01fdec parent), the same four LinkPicker/LinkReview assertions fail.
Manual picking works on Samsung: choose → confirmation sheet → confirm → saved link.
Tests omit OnConfirmLink, so neither success nor same-source error is actually attempted.
Review tests assume score >=90 means confident; production isConfident requires a
shared normalized valid ISBN. On Samsung, temporarily seeded a shared ISBN on Atomic
Habits and The Crossing locally/offline (both original metadata fields were null).
UI displayed Same ISBN and "Link the 1 matches with the same ISBN"; bulk action returned
"1 linked" and library aggregation went from 21 books to 20. Unlinked through UI,
restored temporary ISBN fields before reconnecting, and synced the unlink. Temporary
instrumentation APK and source removed; Wi-Fi/mobile data restored. No link implementation
or baseline tests changed.

Night normal/pair/apply captures added. Concurrent unrelated edits were found in positions
renderers/fixtures; they were preserved and are included in a separate visual-review
commit with the owner's approval. The resource now matches the capture assertion:
"Not started yet". Night captures were independently checked against the production
screen/sheet titles and saved directly from the device.

Specification: the supplied `POSITIONS_PROMPT.md` and eight reference screenshots.
The uncommitted audit was not needed. Endpoint-specific reconciliation remains deferred.

## Changes

- `feature/books/ui/.../positions/PositionsScreen.kt`: public fixture renderer, persistent
  top bar/introduction, fixed selected-position action, error/unlinked/loading states,
  pull-to-refresh and an accessible refresh action, resume refresh, notices and details.
- `PositionCard.kt`: whole-card radio selection, latest pill, chapter/time/percent,
  attribution, sentence-only excerpt, unavailable rows, server-only stale warning and
  the inline Book details link below a disagreement pair.
- `PositionsApplySheet.kt`: Ember sheet, whole-tile checkboxes, reliable/approximate/
  collapse/disabled results, count-aware locked apply action and truthful local-save copy.
- `PositionsPrimitives.kt`, `PositionsLabels.kt`, `TranslationFailureMessage.kt`:
  Ember-only rendering and centralized presentation/result wording. No Material components
  or MaterialTheme references remain anywhere in the positions presentation package.
- `PositionsViewModel.kt`, `PositionsViewState.kt`, `PositionsIntent.kt`,
  `PositionsDataSource.kt`: guarded selection/preview/apply, cancellable refresh, UI-layer
  registry-name join, live position observation, explicit errors versus lost links,
  failure partition/details/retry, and a frozen preview source. A conflict resolving into
  a single candidate preserves selection. The UI adapter checks persisted links before
  interpreting the domain API's nullable result as "no longer linked".
- Book details and home navigation carry the book title into the initial positions state,
  so it is available before loading and remains available on error.
- `lib/server/api/.../PositionDeviceName.kt`: one shared helper for position wording.
  Positions and its sheet, Book details' prompt and the reader prompt all use it. The
  existing platform identity service distinguishes phone/tablet/iPhone/iPad.
- `PositionOrigin.kt`: exhaustive real-reading allow-list with a coverage test.
- `ObserveCopyPositionsUseCase.kt`: a fetch failure no longer marks a device reading stale.
- `TranslationOutcome.kt`, `CopyPositionTranslator.kt`, `TranslatePositionUseCase.kt`,
  `CopyPositionRow.kt`, `PreviewApplyPositionUseCase.kt`: additive failure diagnostics
  at mapping failure points, preserving all nullable compatibility callers, strategy
  order, successful mappings, tick defaults and write guards. `NoTranslation` carries
  `MissingFile(versionKey)`, `NoMatch` or `Unknown`; only MissingFile recommends downloading.
- `base-ui/.../EmberTokens.kt`: additive `note` and `tile` color tokens.
- `tools/ember-fixtures`: isolated, network-free production-composable scenarios,
  screenshot/semantics capture and interactive smoke scripts.

## New strings

All new resources are in `translations/src/commonMain/composeResources/values/strings.xml`.
The top bar reuses `positions_action`; the old title/conflict labels are no longer used
by this screen. The new families are:

- Introduction/formats/location: `positions_intro`, `positions_format_ebook`,
  `positions_format_audio`, `positions_format_readalong`, `positions_on_device`,
  `positions_phone_suffix`, `positions_server_suffix`, `positions_place_about`.
- Reading/availability: `positions_not_started`, `positions_no_reading`,
  `positions_unavailable`, `positions_read_on`, `positions_listened_on`,
  `positions_yesterday`, `positions_pair_note`, `positions_open_version`,
  `positions_server_stale`.
- Load/action states: `positions_use_selected`, `positions_loading`,
  `positions_load_error`, `positions_load_error_help`, `positions_try_again`,
  `positions_unlinked`, `positions_refresh`, `positions_close`.
- Apply sheet: `positions_move_title`, `positions_move_intro`,
  `positions_goes_reliable`, `positions_goes_approximate`, `positions_jump_start`,
  `positions_jump_end`, `positions_cannot_update`, `positions_no_match`,
  `positions_missing_file`, `positions_move_footnote`, `positions_update_one`,
  `positions_update_many`, `positions_updating`.
- Outcomes: `positions_updated_one`, `positions_updated_many`,
  `positions_saved_syncing`, `positions_partial_update`, `positions_none_updated`,
  `positions_failure_details`, `positions_failure_title`.

No wording claims a confirmed server write. A later "synced" variant belongs in the
central notice formatter, once the corresponding acknowledgement is available.

## Verification

- Reader-domain Android host tests: **178 passed**, including unchanged
  PositionsPanelTest tests, origin coverage, local/server stale regressions and each
  translation cause. Missing files can still produce proportional successes.
- Positions Android host tests: **13 passed** (10 ViewModel tests, 2 presentation tests,
  1 copy-mapping test). Covers radio behavior, unavailable selection, gating/defaults,
  toggles/counts, dismissal, failure versus unlinked, cancellation, live updates,
  all/partial/none results, cause-to-string mapping, applying locks, conflict identity
  migration and frozen preview sources.
- Android application and isolated fixture APK build successfully.
- iOS simulator-target Kotlin compilation and `:composeApp:linkDebugFrameworkIosSimulatorArm64`
  succeed on the final sources. An earlier linker attempt hit a Kotlin/Native backend
  error; the final complete build rerun passed.
- The broad books-UI suite also has four failures in untouched LinkPickerViewModelTest
  and LinkReviewViewModelTest. These are reported separately, not treated as positions
  passes and not changed as part of this workstream.
- `positions_capture.py --serial RFCWC0SSVDM --allow-device` captures 17 states in Day
  and E-ink under `design/screens/positions-*.png`, with UIAutomator XML alongside them.
  Screenshots are direct device captures, not fabricated rendering.
- `positions_smoke.py --serial RFCWC0SSVDM --allow-device` passes in both themes:
  disabled initial action; repeat-tap radio behavior; changed source; target counts;
  disabled target; dismissal cleanup; apply notice; not-started rows; retry;
  applying locks; failure details; and home/work server labels after scrolling.
- E-ink uses the existing scrim-free, animation-free Ember popup sheet, outlined tiles,
  filled radio/check marks, black/white Latest pill and bold black warning copy.
  Loading/updating are static text; the spinner composable returns before animation
  setup in E-ink mode.
  Pixel comparison also confirms that the visible background above the E-ink sheet
  is identical to the normal screen (no scrim).

Fixture scenarios: normal, pair, full, error, loading, unlinked, no-selection, apply,
apply-all, apply-start, apply-no-match, apply-not-supported, updating, success, partial,
none and details. Fixtures never touch production accounts, positions or sync workers.

## Authorized Samsung production-app integration

- Installed the final Android debug APK without clearing application data.
- There were no active linked groups, so created one deliberate temporary test link
  through the normal UI between the downloaded Storyteller book and the existing
  "The Crossing" test library copy. This seed exercises different texts/approximate
  matching; it is not a claim that the titles are actually the same book.
- Verified real book title/device naming, separate local/server disagreement candidates,
  Latest and excerpts, disabled initial action, source selection, and pull-to-refresh.
- The real apply preview includes only the other version, not either candidate of the
  source's own version. Approximate matching starts unticked; ticking changes the
  action from Update 0 versions to Update 1 version. Dismissed without applying.
- Verified the inline "open this version" link opens Book details with its existing
  conflict card; did not resolve or overwrite that conflict.
- Removed the temporary link via the UI, then returned to the still-open positions
  screen. Resume refresh correctly shows the unlinked state, preserves its introduction
  and removes the bottom action bar.
- Read-only before/after database comparisons confirm all saved reading places,
  origins and reading times are unchanged and there are zero active links.
- Removed only the test's new, unsynced "never link" decision with a temporary guarded
  instrumentation helper; decisions now match the baseline. The helper APK/source and
  device helper jar were removed. The normal unlink tombstone is retained.
- Real application screenshots: `positions-demo-screen.png`, `positions-demo-apply.png`,
  `positions-demo-unlinked.png`. These use the phone's existing Night preference.
  Day/E-ink and actual write outcomes were exercised in the isolated fixtures/domain
  tests; the integration deliberately did not change any saved reading position.

## Deferred

- Endpoint-specific reconciliation, as requested.
- A server-acknowledged "synced" notice variant; current outcomes report local saves.
