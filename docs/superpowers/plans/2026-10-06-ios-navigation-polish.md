# iOS Navigation Polish Plan

**Status:** In progress. First slice: tab ownership and platform back policy.
Evidence and outstanding checks: `docs/ios-navigation-phase1-report.md`.

**Goal:** Make Parrot's navigation feel natural on iOS: finger-following, cancellable back
navigation; predictable sheets; and tabs that preserve where the user left off.

**Strategy:** Improve the existing Nav3 implementation first. Compare it with a small native
iOS shell prototype before deciding whether to migrate production navigation.

## Scope and guardrails

- Keep shared Compose screen content, shared routes, and business logic.
- Keep Android's current root-back policy unless a regression requires a correction.
- Preserve Ember themes, e-ink behavior, reader page-turn interactions, and playback continuity.
- Preserve login/OAuth, deep links, profile changes, and open-last-book-on-launch behavior.
- Do not replace Nav3, upgrade dependencies, or add a navigation library just for this work.
- Do not commit changes without user approval.
- Native migration and reader-as-modal are separate decisions, not assumed outcomes.
- Respect Reduce Motion, VoiceOver, safe areas, large text, and RTL layouts.
- Do not promise iOS cold-launch navigation restoration from `rememberSaveable` alone.
  Define and test restoration separately from tab retention and foreground/background behavior.

## Proposed interaction contract

| Interaction | Behavior |
|---|---|
| Open ordinary detail screen | Push within the selected tab |
| Back from detail | Pop one entry in that tab |
| Cancel interactive back | Keep route, screen state, and side effects unchanged |
| Back at iOS tab root | No navigation; never switch to Books |
| Android back at another tab root | Preserve existing return-to-start-tab behavior |
| Switch tabs | Immediate selection, no push/pop animation; restore destination and UI state |
| Reselect selected tab | Preserve current behavior initially; optional improvement below |
| Tab root toolbar | No back button |
| Pushed screen toolbar | Accessible back control; gesture is an alternative, not a replacement |
| Temporary sheet | Close/Done/Cancel control appropriate to the task; drag dismiss when safe |
| Continue a task within a sheet | Push within that modal flow; back stays inside the sheet |
| Leave a sheet for app content | Dismiss sheet, then push on its originating tab |
| Reader/player | Keep existing presentation until the prototype decision |

Presentation proposals:

- **Push:** Book detail, Series detail, Diagnostics, Notes/Highlights, and ordinary settings details.
- **Sheet:** Filters, book actions, profile editing, and reader settings opened from the reader.
- **Context-dependent:** Reader settings opened from App Settings may remain a pushed screen.
- **Prototype only:** Full-screen reader/player presentation and a multi-step linking modal.

These are defaults. Inventory existing flows before assigning presentation to every route.
A route's presentation may depend on its caller; a single global `isBottomSheet` flag is not
sufficient for all cases.

## Pre-implementation code findings

- Versions: Compose Multiplatform 1.12.1, Nav3 1.1.2, Navigation Event 1.1.0.
- `HomeNavigationStateHolder` saves a separate route stack for each tab.
- `goBack()` currently switches a non-default tab root back to the start tab on all platforms.
- `HomeNavigation` supplies only `currentBackStack` to one `BottomSheetNavDisplay`.
  This needs a lifecycle/state-retention test: decorator ownership currently follows the active
  stack, rather than a persistent owner per tab.
- `BottomSheetNavDisplay` does not override Nav3's platform transition defaults.
- The existing `BottomSheetSceneStrategy` is not opted into by current `HomeDestination` routes.
- Sheets are implemented through both `EmberBottomSheet` and direct `ModalBottomSheet` calls.
- Tab reselection currently records `TabReselected` and returns without changing navigation.
- Back actions go through ViewModel intents and navigation events. Check timing and exactly-once
  application rather than assuming this is already a gesture bug.
- iOS currently hosts the whole app in one `ComposeUIViewController`.
- `MainViewController()` initializes Koin and app initializers per controller creation.
  A multi-controller native shell must initialize these once instead.

## Phase 0 — Establish a baseline

- [ ] Record the existing flow inventory: caller, destination, push/sheet/local overlay,
  back/close controls, tab visibility, dismissal side effects, and unsaved changes.
- [ ] Record iOS videos for Books → Book detail → Series detail, Settings → Diagnostics,
  reader → reader settings, and library filter dismissal.
- [ ] Exercise slow swipe, reverse-and-cancel, short release, fast release, repeated back,
  open keyboard, open dialog, and reader page-turn conflicts.
- [ ] Exercise Books → detail → another tab → Books; note scroll, filters, search, loading,
  and ViewModel recreation separately from route-stack retention.
- [x] Inspect the resolved Nav3 source/artifact for the installed version before changing
  transitions or back handlers. Upstream source is useful evidence, not proof of runtime behavior.
- [x] Create a short baseline report distinguishing reproduced defects from design improvements.

**Exit:** Known failures and reference recordings, not just a list of suspected problems.

## Phase 1 — Correct tab ownership and platform back policy

Primary files:

- `feature/home/ui/src/commonMain/kotlin/com/retro99/home/ui/navigation/HomeNavigationStateHolder.kt`
- `feature/home/ui/src/commonMain/kotlin/com/retro99/home/ui/navigation/HomeNavigation.kt`
- `feature/home/ui/src/commonMain/kotlin/com/retro99/home/ui/navigation/BottomSheetSceneStrategy.kt`
- `feature/home/ui/src/commonMain/kotlin/com/retro99/home/ui/navigation/HomeNavigationViewModel.kt`
- New policy/state tests beside existing navigation tests in `feature/home/ui/src/commonTest/`.

- [x] Add a small explicit platform back policy; do not import UIKit into shared state code.
- [x] Use the same policy to resolve both the actual back action and its analytics destination.
- [x] Hoist retained decorated entries per tab, including saveable-state and ViewModel owners.
  Render only the selected tab without treating inactive tabs as popped entries.
- [x] Scope tab entries consistently, including identical route values appearing in two tabs.
  Introduce per-entry identity only where necessary; preserve existing route serialization.
- [x] Prevent tab-selection changes from receiving ordinary push/pop transitions. Use an
  explicit selection path while retaining Nav3 defaults for navigation within each tab.
- [ ] Ensure iOS root back is disabled/no-op and cannot fall through to another tab or auth route.
- [ ] Verify real pops clear their ViewModels after transition completion; tab switches do not.
- [x] Preserve profile-switch reset: clear every tab's routes and retained state/owners.
- [x] Add unit tests for platform root-back behavior, tab histories, reset, and replacement routes.
- [ ] Add composition/integration coverage for scroll restoration and ViewModel retention/cleanup;
  pure state-holder unit tests alone cannot prove decorator lifetimes.
  Saved-state, default ViewModel, pop timing, and profile-reset composition tests now pass on iOS;
  precise real-screen scroll restoration remains open (see the first-slice report).

**Exit:** Each tab resumes its destination and relevant UI state; Android behavior is unchanged;
iOS back never switches tabs; retained state is released on actual removal/profile change.

## Phase 2 — Polish interactive back and navigation chrome

- [ ] Keep Nav3's default iOS normal/pop/predictive transitions unless the baseline proves a defect.
- [ ] Verify gesture progress is continuous, cancellation is reversible, and the previous screen
  is available without reloading or performing navigation side effects during the preview.
- [ ] Resolve any delayed or duplicate pop in the intent/event path. Prefer one navigation owner
  applying the completed action, with analytics recording the applied result afterward.
- [ ] Do not add a second back recognizer alongside Nav3's existing handler.
- [ ] Standardize back versus close controls and accessibility labels across affected screens.
- [ ] Audit tab-bar, mini-player, and safe-area changes during push/pop. Keep the underlying
  scaffold stable where possible; do not independently resize it mid-gesture.
- [ ] Confirm dialogs and sheets block background-stack back navigation.
- [ ] Verify reader page turning, horizontal lists, text selection, RTL, and Reduce Motion.
- [ ] Record the same flows as Phase 0 and compare them side by side.

**Exit:** Completed swipe pops exactly once; cancelled swipe pops zero times; neither route state
nor reading/playback effects change on cancellation; no visible layout jump in tested flows.

## Phase 3 — Make sheet navigation consistent

Primary files:

- `base-ui/src/commonMain/kotlin/com/retro99/base/ui/compose/EmberBottomSheet.kt`
- `feature/home/ui/src/commonMain/kotlin/com/retro99/home/ui/navigation/BottomSheetSceneStrategy.kt`
- `feature/home/ui/src/commonMain/kotlin/com/retro99/home/ui/navigation/HomeDestination.kt`
- Affected sheet callers in books, reader, settings, and profile editing.

- [ ] Introduce a small presentation contract covering push, sheet, and full-screen modal,
  including caller context, dismissal policy, and return destination. Avoid a parallel router.
- [ ] Keep trivial local sheets local; give navigable multi-screen modal flows an explicit owner.
- [ ] Centralize shared sheet chrome/dismissal through the existing Ember abstraction, preserving
  its static e-ink presentation. Migrate direct Material sheet usages incrementally.
- [ ] Add configurable expansion/detent intent where needed instead of forcing every sheet to
  skip its partial state. Map capabilities honestly; Material anchors are not native iOS detents.
- [ ] Extract content from full-screen settings scaffolds before hosting reader settings in a sheet.
- [ ] For sheet → ordinary screen, dismiss first and apply the push after dismissal finishes.
  Capture the originating tab so a delayed callback cannot push into a different selected tab.
- [ ] For a multi-step task, keep one modal with an internal stack rather than stacking independent
  sheets. Back pops internally; Close exits the task, with unsaved-change confirmation if needed.
- [ ] Handle programmatic dismissal and gesture dismissal exactly once; block dismissal when
  losing unsaved work or interrupting a non-cancellable operation would be unsafe.
- [ ] Test keyboard avoidance, long scrolling content, scroll/drag handoff, small devices, and iPad.

**Exit:** A sheet cannot leave a hidden pushed screen behind, cause a double-pop, or accidentally
switch tabs; nested task back and whole-task dismissal have distinct behavior.

## Phase 4 — Compare a native iOS shell prototype

This phase is an internal experiment, not production migration.

Prototype files:

- `composeApp/src/iosMain/kotlin/com/retro99/parrot/MainViewController.kt`
- `composeApp/src/commonMain/kotlin/com/retro99/parrot/App.kt`
- `iosApp/iosApp/ContentView.swift`
- New single-screen factory/coordinator files, with shared renderer extraction only as needed.

- [ ] Gate the prototype with an internal build/debug switch; keep the current shell available.
- [ ] Move Koin/app initialization to a single app-lifetime bootstrap and verify initializer counts.
- [ ] Extract shared screen rendering from navigation ownership. Reuse it from Nav3 and from
  native screen factories; avoid maintaining two copies of feature callback wiring.
- [ ] Use native tabs with one native navigation stack per tab and shared Compose destinations.
  Prefer the existing SwiftUI host initially; use UIKit if a demonstrated integration issue warrants it.
- [ ] Prototype Books → Book detail, a second tab for retention/back-policy checks, and
  reader → native reader-settings sheet. Exercise the real reader's gesture conflicts.
- [ ] Keep exactly one authoritative stack per prototype flow. Native interactive pops update that
  owner only after completion; cancelled gestures never remove the shared route.
- [ ] Define per-controller ViewModel lifetimes and stable entry identity. Share app-level services;
  do not duplicate playback, sync, profile observers, or Home navigation event collectors.
- [ ] Remove Compose back/title/tab chrome where native chrome owns it; avoid duplicate padding.
- [ ] Check theme updates, safe areas, keyboard, native sheet detents, accessibility, and supported
  pre-iOS-26 versions. Native navigation does not itself require Liquid Glass or iOS 26.
- [ ] Do not assume Compose scrolling automatically drives native tab minimization or sheet
  scroll-to-expand behavior; test the integration and leave those extras off if unsupported.
- [ ] Compare gesture feel, cancellation, sheet behavior, tab retention, memory, and maintenance cost
  against the Phase 2/3 recordings.

**Decision gate:** Stay with polished Nav3 if it meets the interaction contract. Propose native
migration only if the prototype demonstrates meaningful benefits and passes lifecycle checks.
Obtain user approval before extending the native shell to all routes.

## Phase 5 — Optional follow-up after the decision

- [ ] If approved, migrate native route families in small batches with current-shell fallback.
- [ ] Explicitly design native auth/OAuth, deep links, profile reset, saved routes, and reader launch
  restoration before making the native shell the default.
- [ ] Decide separately whether reader/player should be full-screen modal or remain pushed.
- [ ] Optional tab reselection: when nested, pop to root; when already at root, scroll to top.
  Defer this until state retention works; require a clear rule for active search and unsaved work.
- [ ] Consider native tab/mini-player styling only after behavior is correct. Do not make Liquid
  Glass, automatic minimization, or redesigned screen content acceptance requirements.

## Verification and completion criteria

Run applicable module host tests and iOS simulator compilation; confirm task names before use:

```sh
./gradlew :feature:home:ui:testAndroidHostTest
./gradlew :feature:home:ui:compileKotlinIosSimulatorArm64
./gradlew :androidApp:assembleDebug
```

Add affected books/reader/settings/base-ui tests when those modules change. Build the actual iOS
app for simulator as well; shared Kotlin compilation does not validate Swift/native integration.

Manual acceptance matrix:

- iPhone simulator plus a physical iPhone for gesture feel, responsiveness, and interruption tests.
- Minimum supported iOS plus current iOS; iPad for sheet size/layout checks.
- Android gesture back and button back; e-ink/no-motion mode.
- Light/dark theme, Reduce Motion, VoiceOver, large text, keyboard, and RTL.
- Every tab: retain route, scroll, search/filter state; no ViewModel recreation just from switching.
- Slow/cancelled/fast swipe, rapid back, repeated navigation, modal dismissal, nested modal back.
- Reader page turn/text selection, ongoing audio, mini-player, current-book state, position saving.
- Profile switch, login from Settings, OAuth return/cancel, deep links, open-last-book on launch.
- Foreground/background retention and the explicitly chosen cold-launch restoration behavior.

Deliver a short evidence report per phase: changed behavior, tests run, recordings, remaining issues,
and whether the next decision gate is satisfied. No runtime verification is claimed by this plan.

## References

- [JetBrains native iOS shell tutorial](https://kotlinlang.org/docs/multiplatform/ios-liquid-glass.html)
- [JetBrains Nav3 documentation](https://kotlinlang.org/docs/multiplatform/compose-navigation-3.html)
- [Nav3 multiple-stack recipe](https://github.com/android/nav3-recipes/tree/main/app/src/main/java/com/example/nav3recipes/multiplestacks)
- [iOS Nav3 veil/unveil implementation](https://github.com/JetBrains/compose-multiplatform-core/pull/2655)
- [Apple tab-bar guidance](https://developer.apple.com/design/human-interface-guidelines/tab-bars)
- [Apple accessibility guidance](https://developer.apple.com/design/human-interface-guidelines/accessibility)
- [Medium: KMP Navigation 3](https://medium.com/@kaito_and_droid/kmp-how-to-use-jetpack-navigation-3-e8e778c3f30f)
- [Medium: bottom-tab stack patterns](https://medium.com/@sunildhiman90/mastering-compose-navigation-3-a-deep-dive-into-navigation-3-part-2-bottom-tabs-navigation-2086c28b25fe)
- [YouTube: multiple tab stacks](https://www.youtube.com/watch?v=hNzRWVr_Yvs)
- [YouTube: Nav3 transitions](https://www.youtube.com/watch?v=qJMMc9oK3X8)
- [YouTube: native Liquid Glass shell](https://www.youtube.com/watch?v=zZtth3s1GH4)

Community articles and video transcripts informed the proposals. Version-specific implementation
choices must be checked against resolved dependencies and runtime results.
