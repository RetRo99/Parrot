# Open bugs — fix plan (overview)

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development
> (recommended) or superpowers:executing-plans to implement this plan bug by bug. Steps use
> checkbox (`- [ ]`) syntax for tracking.

This lists every known open bug as of 2026-10-01 (after project 3 was merged, `009bb23c`), in the
order to fix them. Each bug is one commit (or one small set of commits), and each must leave the
app building with all tests passing.

**Already fixed, don't redo:**
- the Storyteller HTTP 409 retry loop (project 3);
- `localGeneration` being dropped (`ce0a156e`).

**Not a bug** (checked and withdrawn): "the iOS EPUB metadata extractor loads whole files". It
opens EPUBs through the Swift/Readium bridge. The `NSData` read only loads the extracted cover
image.

## Global constraints

- Kotlin style: 100-char lines, 4 spaces, no wildcard imports, trailing commas, named lambda
  params (no `it`), no semicolons.
- Tests first: kotlin.test, hand-written fakes, `// Given / // When / // Then`.
- Strings go at the bottom of `translations/src/commonMain/composeResources/values/strings.xml`
  with `tools:ignore="MissingTranslation"`.
- Use the next free `.sqm` number for each migration. Don't edit `version =` in
  `lib/database/implementation/build.gradle.kts`.
- Never write to real servers or the remote Supabase project. Skip "reproduce on a real server"
  tasks (Task 0 and the final real-server checks); they're done locally.
- Keep UI changes functional and minimal.

## B1 — iOS doesn't build on `main` (do first)

**What:** `feature/books/ui/src/commonMain/.../components/LibraryDock.kt` imports
`androidx.compose.foundation.layout.isImeVisible` and uses `WindowInsets.isImeVisible`
(line 88). That API exists only on Android, so
`:feature:books:ui:compileKotlinIosSimulatorArm64` fails with "Unresolved reference
'isImeVisible'". Everything that depends on `books/ui` (`reader/ui`, `home/ui`, `composeApp`)
then can't build for iOS. It came in with `1ce03c03`.

**Fix:** use the multiplatform equivalent in common code. Replace the condition with
`WindowInsets.ime.getBottom(LocalDensity.current) > 0`, and remove the `isImeVisible` import.
Keep the same paddings (8 dp with the keyboard showing, 20 dp without).

**Verify:** `./gradlew :feature:books:ui:compileKotlinIosSimulatorArm64
:composeApp:compileKotlinIosSimulatorArm64` (macOS only; cloud sessions report it as "to verify
locally") and `./gradlew :androidApp:assembleDebug`. The Android behaviour must be unchanged:
check the dock padding with the keyboard open and closed.

## B2 — Android Auto and background listening don't update linked copies (small)

**What:** project 3 propagates positions from `ReaderViewModel` (session end and audio pause) and
`AudiobookPlayerViewModel`. `feature/reader/ui/src/androidMain/.../playback/auto/
HeadlessPlaybackSession.kt` also saves positions (`savePosition`, `buildPosition`, around lines
200–247) but never calls `PropagateToLinkedCopiesUseCase`. Listening in the car doesn't update
other servers until the book is next opened in the app.

**Fix:** call `PropagateToLinkedCopiesUseCase` where the headless session saves its final
position, and on pause, exactly as `AudiobookPlayerViewModel.propagateToLinkedCopies` does. It
respects the "Update linked copies" setting. If the session has no coroutine scope that outlives
the call, use `NonCancellable`, as the view model does.

**Tests:** extract the "should propagate now" decision into a pure function if needed. The test
covers that the final save and the pause each trigger one propagation, and that nothing happens
with the setting off.

## B3 — Audiobook positions saved per file

Follow `2026-10-01-audiobook-position-per-file-fix.md`, Tasks 1–6. Skip Tasks 0 and 7, which
need a real server.

## B4 — Audiobookshelf ebook position format

Follow `2026-10-01-audiobookshelf-ebook-location-fix.md`, Tasks 1–2. Skip Tasks 0 and 3, which
need a real server.
- Do this **after B3**: both edit `AudiobookshelfProgressTransport.kt` and add migrations.
- When this lands, project 3's guard 11 (`CopyWriteGuards`) can allow ebook writes to
  Audiobookshelf. Change that guard in the same commit and update its tests.

## B5 — Every app update wipes the local database

Follow `2026-10-01-real-database-migrations.md`.
- It needs Gradle to generate a schema snapshot from commit `1df302ab`.
- If Gradle can't run in your environment, **skip B5** and say so. It'll be done locally.
- Do it last, so its migration check covers the migrations B3 and B4 add.

## Report

For each bug:
- the commits;
- the tests added;
- what you ran and what you couldn't run;
- anywhere the plan was wrong and what you did instead.

Finally, list the exact commands to run locally:
- unit tests per changed module;
- `./gradlew :androidApp:assembleDebug`;
- `:<module>:compileKotlinIosSimulatorArm64` for each changed module.
