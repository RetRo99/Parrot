# iOS navigation: baseline and first implementation slice

Date: 2026-10-06

## Scope

Implements the plan's tab-state ownership and platform back-policy changes. Sheet presentation,
reader-as-modal, native iOS shell, and custom transition animations are unchanged.

Plan: `docs/superpowers/plans/2026-10-06-ios-navigation-polish.md`.

## Baseline evidence

- Before code changes, Home's Android host tests and iOS simulator compilation passed.
- Dependency resolution confirmed `org.jetbrains.androidx.navigation3:navigation3-ui:1.1.2`,
  `androidx.navigation3:navigation3-runtime:1.1.7`, and Lifecycle navigation decoration 2.11.0.
- Inspected the matching published sources, not only current upstream source:
  - Nav3 UI has iOS normal/pop/predictive horizontal transitions with parallax and veil/unveil.
  - `NavDisplay(backStack = ...)` remembers decorated entries for that one supplied stack.
  - Runtime decoration invokes `onPop` after an entry leaves both the back stack and composition.
  - The ViewModel decorator clears its entry's provider key on that callback.
- Added an iOS Compose-runtime regression test reproducing the previous ownership problem:
  replacing the active Books stack with Settings invokes pop cleanup for Books.
  This test intentionally documents the old implementation; production uses retained per-tab entries.
- Booted simulator: iPhone Air, iOS 27.0. The previously installed Parrot app launched into a reader.
  Its build provenance was not established, so it is not treated as a current-source UI baseline.
- On that previously installed app, a center tap reported success without changing the reader.
  The cause was not established. With the freshly built app, taps and vertical scrolling work.
  Do not claim reference swipe recordings or physical-device feel from this session.

## Flow inventory for subsequent phases

| Caller → destination | Current presentation | Controls / behavior to preserve |
|---|---|---|
| Books → Book detail | Per-tab push; tabs visible | Toolbar back; open reader; links/positions actions |
| Series → Series detail → Book detail | Per-tab pushes; tabs visible | Toolbar back within originating tab |
| App Settings → Diagnostics | Per-tab push; tabs visible | Toolbar back |
| Statistics tab root / pushed Statistics | Root or push | Back control only when pushed |
| Book detail / mini-player → reader or player | Per-tab push; tabs hidden | Close; playback-conflict checks; reading attribution |
| Reader → reader settings | Full-screen push; tabs hidden | Close returns to reader; preserve preview/settings behavior |
| App Settings → reader settings | Same full-screen route | Caller-dependent presentation is a later design task |
| Library → filters | Local Ember sheet | Toggle filters; dismiss without popping library |
| Book detail → manage actions | Local Material sheet (e-ink dialog) | Actions and dismissal; not a Nav3 sheet entry |
| App Settings → profile edit | Local Ember sheet | Save/delete; dismissal callback blocked while busy |
| Settings → server management → login | Home push then root auth navigation | Return to settings; OAuth/autofill and server attribution |

The Nav3 bottom-sheet strategy exists, but current Home routes do not opt into it. Inventory is
source-derived; keyboard behavior, drag dismissal, unsaved-change handling, and all action-to-screen
handoffs still need runtime checks before Phase 3 changes.

## Changes

- Added explicit iOS `StayInTab` and Android `ReturnToStartTab` root-back policies.
- Back execution and analytics now resolve their destination from the same state-holder policy.
- Added persistent saveable-state/ViewModel decorators for each tab, outside the selected display.
- Key the visual `NavDisplay` by selected tab: switching tabs creates no cross-tab push/pop history
  while preserving decorators and screen state. Within-tab transitions keep Nav3's platform defaults.
- Added an explicit root-tab selection back handler for Android. Nav3's own handler only enables
  back when there is a previous scene; no synthetic previous scene is supplied for iOS tab roots.
- Profile reset increments a saveable entry-state generation, invalidating unchanged roots as well
  as detail entries. This prevents newly retained roots from leaking state between profiles.
- Preserved custom entry content identity when applying bottom-sheet metadata.

## Automated coverage

Shared state-holder tests cover:

- iOS root back remains in every tab; Android root back returns to the existing start-tab destination.
- Nested back affects only the selected tab; root target and execution agree.
- Switching/reselecting tabs retains route histories; identical routes can belong to different tabs.
- Reader replacement preserves attribution and affects only its target stack.
- Profile reset restores all roots and advances entry-state identity, including repeated resets.
- A non-Books start tab is honored.

iOS Compose-runtime tests cover actual saved-state decoration and cleanup callbacks:

- Reproduction of old active-stack ownership failure.
- Saved screen state retained across tab switches with no pop cleanup.
- Independent saved state for identical routes in different tabs.
- Pop cleanup delayed until simulated outgoing content leaves composition.
- Profile reset invalidates active/inactive roots and their saved state.
- Default ViewModel decorators retain models across tab selection and clear them on pop/profile reset.

Composition tests run on iOS rather than plain Android host tests, which cannot execute Compose's
Android platform hooks without an Android test environment. No global "return default values" mock
setting was added to hide those platform calls.

## Verification status

- `./gradlew :feature:home:ui:testAndroidHostTest`: **passed**, 74 tests, zero failures/skips.
- `./gradlew :feature:home:ui:compileKotlinIosSimulatorArm64`: **passed**.
- `./gradlew :feature:home:ui:iosSimulatorArm64Test`: **passed**, 80 tests, zero failures/skips.
- `./gradlew :androidApp:assembleDebug`: **passed**.
- `xcodebuild -workspace iosApp/iosApp.xcworkspace -scheme iosApp -configuration Debug
  -destination 'platform=iOS Simulator,id=45F57B43-B65F-4F73-AAED-034D5B3DA463'
  -derivedDataPath <session-temp>/parrot-nav-derived build`: **passed**, simulator signing enabled.
- Installed the resulting `Parrot.app` without clearing app data and launched it successfully.
- `git diff --check`: **passed**. No commits made.

### Current-build UI smoke checks

- Library → Settings → Library works; Library → Book detail → Settings → Books returns to
  the detail route. Toolbar Back returns to Library.
- A scripted left-edge swipe at Settings root left Settings selected; no auth/root transition
  was observed. This is a smoke observation, not full nested-handler acceptance.
- **Open:** Scripted edge swipes on Book detail (`1,460 → 370,460`, 0.8s and
  `12,360 → 380,360`, 0.5s) did not pop it. Vertical swipes and taps do work. Investigate
  gesture delivery/recognizer activation and compare current source with the original baseline
  before assigning a cause; do not assume the transitions alone make swipe-back functional.
- **Open:** After vertical scrolling in Book detail, the ABOUT label moved from y≈471 to y≈603
  on the first Settings → Books round-trip, while the route and nonzero scroll were retained.
  A second round-trip kept y≈603. Investigate settled scroll values and first-layout/content
  measurement before claiming pixel-exact scroll restoration.

Build logs and screenshots are in the session temporary directory under the `parrot-nav-` prefix;
they are not committed as project assets.

Manual acceptance remains open: real swipe progress/cancellation, navigation chrome during gestures,
precise scroll restoration, physical iPhone responsiveness, Android system-back input, minimum-supported
iOS, large text/VoiceOver/Reduce Motion/RTL, keyboard, and reader/playback interruptions.

This first slice is not a native-shell migration and does not establish the full Phase 0/1 manual
exit criteria. Continue with manual tab/gesture checks before changing transitions or sheets.
