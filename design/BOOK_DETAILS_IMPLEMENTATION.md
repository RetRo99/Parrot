# Ember book details implementation

## Decisions retained

- Parrot Cloud remains a library: **Add to Parrot Cloud / In Parrot Cloud**.
- Legal strings and upload rights attestation are unchanged.
- Downloads remain per format; the primary action's size is its format's size only.
- Device-only files have no remove-download action. Whole-book device/cloud deletion lives
  only in Manage and still requires the existing confirmation.
- Inline conflicts retain both remote resolution and **Keep this device’s position**.
- Manage retains an Open action for each downloaded, ready format.

## Main files

Under `feature/books/ui/src/commonMain/kotlin/com/retro99/books/ui/detail/`:

- `BookDetailScreen.kt`: routing, top bar, fixed hero gradient, scrolling, snackbar and lifecycle.
- `BookDetailHeader.kt`: cover, title, authors, series, format pills, read-only rating.
- `BookDetailProgress.kt`: one reading bar, optional chapter/time data, conflict note.
- `BookDetailActions.kt`: primary per-format read/download and explicit Listen entry.
- `BookLocationsCard.kt`: device/cloud/linked-copy status and all current transfers.
- `BookDetailAbout.kt`: HTML cleanup, four-line description and in-place expansion.
- `BookDetailFacts.kt`: optional facts, spanning final cell and non-interactive tags.
- `BookManageSheet.kt`: per-format actions, copies, linking, Positions and permanent deletions.
- `BookDetailDialogs.kt`: existing confirmation and open-prompt flows.
- `BookDetailError.kt`: Retry and Servers for failed loads.
- `BookDetailPresentation.kt`, `BookDetailWidgets.kt`: presentation rules and shared Ember UI.

Supporting changes:

- `BookDetailViewModel.kt`, `BookDetailViewState.kt`, `BookDetailIntent.kt`: action permissions,
  Parrot activity, listen-entry state, comparison return, retry subscription cancellation.
- `feature/books/ui/.../model/`: metadata mapping and domain conflict flag.
- `feature/books/domain/.../model/`: optional size, duration, chapter and remote-time data.
- `feature/reader/domain/.../ObserveBookWithProgressUseCase.kt`: preserve available position
  metadata; library cache rules remain unchanged.
- `lib/server/api/.../ServerBooksRepository.kt` and `lib/server-storyteller/.../`: preserve rating
  and narration preparation data through remote/cache mapping.
- `feature/home/ui/.../navigation/` and `feature/reader/ui/.../ReaderScreen.kt`: explicit listen
  entry carried through playback-conflict navigation, starting narration only after readiness.
- `base-ui/.../EmberTokens.kt`: fixed hero color, detail outline and progress sizes.
- `translations/.../values/strings.xml`: new localized labels; existing cloud/legal wording kept.
- Shared linked-resume dialog and unlink confirmation: dismiss-as-Stay and Never-decision notice.

## Existing action reachability (code audit, not a device test)

| Action | New location |
|---|---|
| Back, favorite | Top bar |
| Read / continue | Primary action; every format also has Open in Manage |
| Listen | Secondary action when ready and downloaded; audiobook primary / Manage Open |
| Download | Primary format; other formats in Manage |
| Cancel server download | Phone status row and Manage |
| Cancel Parrot upload/restore | Every active transfer's status row |
| Remove downloaded content | Phone row (multi-format opens Manage); per-format in Manage |
| Add to Parrot Cloud | Cloud row / Manage, with unchanged rights confirmation |
| Retry upload/restore, replace file | Every relevant failed transfer row; replace still confirmed |
| Remove from Parrot Cloud | Manage only, existing confirmation |
| Delete device-only book | Manage only, existing confirmation |
| Link another copy, unlink | Manage; unlink still confirmed and explains Never-decisions |
| Open linked copy | Locations card and Manage |
| Reading positions | Manage with linked copies; Compare all in pending resume prompt |
| Use local / remote position | Conflict note and pre-open conflict dialog |
| Linked Continue / Stay / Compare | Existing prompt; dismissal means Stay; Compare retains open |
| Series | Header chips |
| Expand description | About |
| Load retry / server settings | Error state |
| Tags | Read-only until tag filtering exists |

Reader-only capabilities (device voice/TTS, bookmarks, audio controls and settings) remain in
the reader. Unsupported actions in the investigation report were not invented.

## Verification scope

Host tests cover permissions across availability states, per-format size, small conflicts,
preparing narration, metadata mapping, multiple transfers, comparison state and HTML cleanup.
A Storyteller test covers narration/rating cache round trips.

Initial verification did not deploy to a device/emulator, respecting the handoff restriction.
The debug APK was subsequently installed and launched on the Xiaomi at the user's request;
this was not a live Night/Day/E-ink visual check. Actual ~360dp layout, TalkBack announcements, live
server failures and download/cancel network flows still require manual acceptance checks.
Unknown device names, durations, file sizes and chapter counts are omitted, not fabricated.

## Verification results

Final command (passed, `BUILD SUCCESSFUL in 1m 16s`):

```sh
./gradlew \
  :feature:books:ui:testAndroidHostTest \
  :lib:server-storyteller:testAndroidHostTest \
  :androidApp:assembleDebug \
  :feature:books:ui:compileKotlinIosSimulatorArm64 \
  :feature:home:ui:compileKotlinIosSimulatorArm64 \
  :feature:reader:ui:compileKotlinIosSimulatorArm64 \
  :lib:server-storyteller:compileKotlinIosSimulatorArm64
```

Additional domain regressions (passed, `BUILD SUCCESSFUL in 10s`):

```sh
./gradlew :feature:books:domain:testAndroidHostTest \
  :feature:reader:domain:testAndroidHostTest
```

JUnit reports: books UI **41**, Storyteller **34**, books domain **46**, reader domain **78**:
**199 tests**, zero failures/errors/skips. `git diff --check` passed.

iOS was compiled only, not run. Android was built only in this verification pass.
The pre-existing `settings.gradle.kts` change and unrelated untracked work were left untouched.
No commit, deployment, database migration or remote-project action was performed.

## Follow-up polish

- A single downloaded format uses its name, not “1 formats”; multiple formats use a quantity
  resource. Size is omitted when not known for every downloaded format.
- The cloud row says **Parrot Cloud / ✓ On all your devices**, without repeating its title.
- Added and last-opened dates use native medium date formatting and localized Today/Yesterday,
  comparing calendar dates in the local timezone (including daylight-saving transitions).
- About has an 8dp label-to-description gap; titles stop at three lines with ellipsis.
- Status-card housekeeping buttons use ink text and a 1dp chip-border outline.
- The scroll content includes the measured home bottom-bar height plus 24dp of bottom padding.
- Regression coverage includes one/multiple/partially-downloaded formats, unknown sizes, date
  parsing, locale-dependent medium formats, local midnight and daylight-saving boundaries.

Polish verification passed (`BUILD SUCCESSFUL in 1m 13s`):

```sh
./gradlew :base:testAndroidHostTest :feature:books:ui:testAndroidHostTest \
  :androidApp:assembleDebug :feature:home:ui:compileKotlinIosSimulatorArm64
```

Base **7** + books UI **42** = **49 tests**, zero failures/errors/skips. Android build and iOS
compile passed; `git diff --check` passed. This polish build was not deployed to the Xiaomi.
