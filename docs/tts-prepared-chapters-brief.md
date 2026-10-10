# Prepared chapters: the brief

This is the standing specification for the feature. Each agent run gets a short prompt
that says where the work stands and which steps to do; everything else is here. The
running record of what has actually been built is `docs/tts-prepared-chapters.md`.

## The feature

The user presses "Prepare this chapter" in the Listening sheet. The app generates the
audio for every sentence of the chapter in the background, stores it compressed, and from
then on that chapter plays with no waiting. Slow voices take 2 to 9 seconds per sentence,
so a chapter can take 10 to 15 minutes to prepare.

Android only. iPhone has a read-aloud stub (`IosTtsController`) and must not change.

Read first: `docs/tts-investigation.md` section 1 (how the read-aloud stack works) and
`docs/tts-test-plan.md` "How each run works".

## Decided by the owner, final

- Works for whichever voice the user has selected, system voices included.
- Started by a button, one chapter at a time. No whole-book option, no automatic
  preparation.
- Audio is prepared for one voice, speed and pitch. After a change the chapter shows as
  prepared for the old setting and can be prepared again.
- Stored compressed on the phone, outside the 128 MB sentence cache, under a 1 GB limit,
  oldest prepared chapter removed first.
- Live listening always wins over background preparation.
- A later run backs prepared chapters up to Parrot Cloud. Do not build that, but store
  each chapter so it can be packaged as one file: one folder per chapter with a manifest.

## Rules for every run

- Tests first for all logic: write the test, see it fail, then build. Each step ends with
  a commit and green tests.
- Keep `docs/tts-prepared-chapters.md` current and commit it at the end of each step:
  what was built, the storage layout and manifest fields, every new screen text, every
  state the row can show, and what was not done.
- Small commits. Never `git add -A`; add files by explicit path. Never discard work: if
  time runs out, commit what exists with failing tests marked and explained.
- The bug-fix runs' tests must pass unedited. If one has to change, stop and explain why.
- No new libraries. No database migration: state lives in manifest files. minSdk is 29.
- The worktree has no `local.properties`: pass `ANDROID_HOME=$HOME/Library/Android/sdk`
  on the Gradle command line; do not create or edit config files.

## Steps

### Step 1: compressed sentence audio, decided on the phone first

Sentence audio today is PCM WAV (about 2.9 MB per minute for Kokoro). Prepared audio must
be much smaller.

- Encoding sits behind a small interface in
  `feature/reader/ui/src/androidMain/.../tts/` (WAV in, compressed file out, success or
  failure) so everything else is host-testable with a fake. The real one uses the
  platform's MediaCodec and MediaMuxer: AAC-LC, mono, in an `.m4a` file, at the WAV's
  sample rate, around 48 kbit/s.
- Before building on it, check on the phone with six real sentences (three Kokoro, three
  system voice): encode them, play them back to back through the read-aloud engine, and
  measure file sizes, each file's reported duration against the WAV's, and whether the
  join between sentences is audibly worse than with WAV (AAC adds padding at the start
  and end of each file). Write the numbers in the feature document.
- If durations are off by more than 50 ms per sentence or the joins are clearly worse,
  try trimming the encoder padding; if that does not work, keep WAV for prepared audio,
  say so in the document and the report, and continue. The feature matters more than the
  format.

### Step 2: the prepared store

A new internal class, host-testable like `TtsAudioCacheStore` (root directory and clock
injected; the Android wrapper supplies `filesDir/tts-prepared` and is a Koin `@Single`).

- Layout: one folder per book and chapter, holding `manifest.json` and one audio file per
  sentence, named by the SAME key `TtsAudioCacheStore` uses (voice, model version, rate,
  pitch, text), so playback can look a sentence up without knowing its chapter.
- Manifest: a format version, book id, server id, chapter href, voice id, model version,
  rate, pitch, created time, the ordered list of sentence keys with duration and byte
  size, a complete flag, total bytes. NO sentence text and no book or chapter title in it
  or in any file name.
- Operations: look up a sentence by key (file and duration); the state of a chapter for
  the current voice, rate and pitch (not prepared, partly prepared n of m, prepared with
  size, prepared for other settings and which); add a sentence; mark complete; delete a
  chapter; delete everything; total size; enforce the 1 GB limit by removing the oldest
  complete chapters, never the one being prepared and never one used in the last 10
  minutes.
- Safety, each with a test written first: a file is written under a temporary name and
  renamed, so a crash never leaves a half file that looks valid; a manifest that cannot
  be parsed or has an unknown version makes the chapter "not prepared" and is cleaned up,
  it never crashes; files in a chapter folder that the manifest does not list are
  removed; a partly prepared chapter is kept so preparation can resume; paths are built
  so a hostile book id or href cannot escape the root (see `TtsModelPathsTest`).

### Step 3: playback uses prepared audio, and live listening wins

In `TtsAudioGeneratorCore` (`.../tts/TtsAudioGenerator.kt`):

- Lookup order becomes: prepared store, then the sentence cache, then synthesis. A
  prepared hit returns the compressed file with its duration from the manifest. Tests
  first: a prepared sentence is served without calling the synthesizer; a change of rate,
  voice or model version misses, as it does for the cache.
- The preparation path, for one sentence: skip if already prepared; otherwise take the
  WAV from the cache or synthesize it, encode it, add it to the prepared store. A failed
  synthesis or a failed encode leaves nothing behind in either store.
- Priority. Synthesis has a single permit. Tests first: while a preparation is running, a
  live request is served as soon as the sentence in flight finishes, before any further
  preparation sentence; queued preparation sentences never starve a live request; with no
  live request, preparation proceeds at full speed. A native generation in flight cannot
  be interrupted for this; do not try.
- Do not change `TtsReadAloudEngine` unless a test shows it cannot play a prepared file.
  `findCached` (used by the word speaker) keeps looking in the sentence cache only.

### Step 4: the preparation job and its foreground service

- An app-wide `@Single` (it must outlive the reader screen) that prepares one chapter at
  a time: it is given the book id, server id, chapter href, the chapter's sentence texts
  in order, and the voice, rate and pitch; it exposes a state flow (idle, running with
  done and total, completed, failed with a reason, cancelled) and a cancel. A second
  request while one runs is refused with a clear state, not queued. The pure logic must
  be host-testable with fakes for the generator and the store.
- Tests first: sentences already prepared are skipped, so pressing the button again after
  a cancel or an app kill resumes; one sentence failing is retried once, then the job
  fails and keeps what it has; cancel stops after the sentence in flight and leaves a
  valid partial chapter; completion marks the manifest complete and enforces the size
  limit; not enough free disk space fails before starting.
- A foreground service keeps it alive in the background, modelled on
  `TtsVoicePreparationForegroundService` (same folder; declared in
  `androidApp/src/main/AndroidManifest.xml` with `foregroundServiceType` dataSync): a
  notification with progress and a Cancel action, no book or chapter title in it. Use the
  existing notification permission flow (`NotificationPermissionHandler`); a denied
  permission means preparation does not start, with the same feedback read-aloud gives.
- Do not start if the selected neural voice's pack is not downloaded or the Supertonic
  terms are not accepted; send the user to the Voices sheet the way `playTtsFromSentence`
  in `ReaderViewModel` does.
- Analytics: one event when a preparation starts and one when it ends (outcome, sentence
  count, duration, voice kind), no text, no titles, no ids. The sanitizer is fail-closed:
  add every new parameter and value to
  `lib/analytics/implementation/.../AnalyticsParameterSanitizer.kt` with a test, or it is
  silently dropped.

### Step 5: reader wiring

- `TtsController` (`feature/reader/ui/src/commonMain/.../navigator/TtsController.kt`)
  gains what the screen needs: the preparation state for the chapter on screen, prepare
  this chapter, cancel, delete this chapter's prepared audio. `IosTtsController` reports
  "not supported" and does nothing.
- The sentences come from the chapter loaded in the reader at the moment of the press.
  Preparation then continues without the reader.
- The decision "what does the row show" is a pure function in commonMain with tests in
  commonTest, in the pattern of `derivePackState` (`reader/VoicePackState.kt`,
  `VoicePackStateTest`). `ReaderViewModel` only forwards; do not add logic to it that is
  not behind a tested class (see `ReaderTtsSetup`).
- Read-aloud and preparation of the same chapter at the same time must work: sentences
  prepared so far play instantly, the rest are generated live with priority.

### Step 6: the screen

In the Listening sheet's device-voice body (`.../reader/ReaderAudioSheet.kt`,
`DeviceVoiceBody`), below the voice card, one row or card for the chapter on screen.
Follow `VoicePackCard.kt` for layout, the progress bar, the e-ink variant (`isEink`) and
accessibility labels; do not invent a new visual style.

| State | Shows | Offers |
| --- | --- | --- |
| Not prepared | It takes a while and plays instantly afterwards | Prepare this chapter |
| Preparing | "42 of 151 sentences" with a progress bar | Cancel |
| Preparing another chapter | Says so | Nothing |
| Prepared | "Ready, plays instantly" and its size | Delete, with a confirmation |
| Prepared for other settings | Which voice or speed it was made for | Prepare again |
| Partly prepared | "42 of 151 prepared" | Continue, Delete |
| Failed | One line | Retry |
| Voice not usable | Pack missing or terms not accepted | Leads to Voices |

Not shown for a book with recorded narration selected, or when read-aloud is not
available.

Also in Settings, Reader settings, Read aloud tab
(`feature/settings/ui/src/commonMain/.../ReaderSettingsTabs.kt`): one row "Prepared
audio" with the total size and "Delete all" with a confirmation; hidden on iPhone.

Text: add strings to `translations/src/commonMain/composeResources/values/strings.xml` in
the same form as the existing `reader_tts_` entries. Plain, short wording. List every new
string with its key in the feature document.

Screen tests: add cases beside `VoiceSettingsScreenTest` (commonTest) that render each
state and assert its text and actions.

### Step 7: documents

Finish `docs/tts-prepared-chapters.md`: what was built, the storage layout and manifest
fields, measured sizes and timings, every string, every state, known limits, and a
section "For the cloud backup run" saying what a later run needs to know to package and
upload one chapter.

### Step 8: device check

Device rules:

- Allowed device: the Samsung, serial `RFCWC0SSVDM`. Pass `-s RFCWC0SSVDM` on every adb
  call. `adb install -r` only; never uninstall, never clear app data, do not sign out, do
  not change network settings or the ringer. No other phone, even if others are attached.
  If it is not connected, skip the device parts and say so. Do NOT accept the Supertonic
  terms.
- Driving the reader: a tap in the middle of the page reveals the control row; wait 0.9
  seconds for it to settle before touching it. A long press on Listen
  (`input swipe X Y X Y 800`) opens the Listening sheet. Take coordinates from a fresh
  `uiautomator dump`.
- Use a Project Gutenberg book and pick a SHORT chapter (under about 60 sentences).

Checks:

1. With Kokoro "Heart": press Prepare this chapter. Record the progress text, leave the
   reader to the library, check the notification shows progress, come back. Record total
   time and the folder's size on disk (run-as the app package).
2. Play the prepared chapter from its first sentence: the log shows no "Kokoro synthesize
   start" for it. Pause, resume, skip, and double-tap a later sentence: all instant.
3. Start preparing a second chapter and, while it runs, play the first page of a third,
   unprepared chapter: narration starts within one sentence's generation time and keeps
   going.
4. Force-stop the app in the middle of a preparation, reopen the book: the row shows
   partly prepared; Continue finishes it without redoing the sentences already there
   (count the synthesize lines).
5. Change the speed by one step: the row shows prepared for the old speed. Change it
   back: prepared again.
6. Delete the prepared chapter from the row: folder gone. Prepare one again, then
   Settings, Read aloud: the total is shown; Delete all empties it.
7. With a System voice: prepare one short chapter and play it.
8. Screenshot every state of the row you reach, cropped so no book title shows.

Evidence goes to `docs/manual-qa-evidence/2026-10-10/tts-prepared-chapters/`: `NOTES.md`
with one line per check and its numbers, logcat filtered to the app process with book
titles removed. Leave the phone on a System voice at rate 1.0, on the library screen,
with all prepared audio deleted.

## Not in this feature

Any upload, download or Parrot Cloud code; server or supabase changes; a database
migration; preparing a whole book or the next chapter automatically; compressing the
ordinary sentence cache; changing the cache key format; making synthesis faster; iPhone
read-aloud; new libraries; the remaining bug-fix items TTS-F15 and TTS-F25.

## Verify

```bash
ANDROID_HOME=$HOME/Library/Android/sdk ./gradlew :feature:reader:ui:testAndroidHostTest :feature:reader:ui:iosSimulatorArm64Test :feature:settings:ui:testAndroidHostTest :feature:home:ui:testAndroidHostTest :composeApp:testAndroidHostTest :lib:analytics:implementation:testAndroidHostTest --max-workers=2 -Pkotlin.daemon.jvmargs=-Xmx6g
```

```bash
ANDROID_HOME=$HOME/Library/Android/sdk ./gradlew :androidApp:assembleDebug :composeApp:linkDebugFrameworkIosSimulatorArm64
```

Read the counts from the result files under `build/test-results`. `composeApp` has
`ReaderScopeWiringTest`, which resolves the real dependency graph: add the new `@Single`
classes to a test like it, because unit tests that build objects by hand do not catch a
missing binding. If Gradle runs out of memory, a larger heap on the command line is the
only allowed workaround.

## The report

Every run ends with the report below, filled in, in exactly this form, inside one fenced
code block, plain text only, nothing after it. Write and commit it before the device
check and the final builds, then update it. Answer "not built" for steps not reached.

```text
--- REPORT ---
Branch:
Commits this run (hash + subject, oldest first):
Steps complete this run, and steps complete in total (1-8):
Step 1: format chosen; six-sentence measurements (WAV bytes, compressed bytes, duration difference per sentence in ms); joins audibly worse than WAV (yes/no); fell back to WAV (yes/no, why):
Step 2: where prepared audio lives on disk; manifest fields; does any file name or manifest contain sentence text or a title (yes/no):
Step 3: lookup order as built; how a live request overtakes preparation, and the test that shows it; was TtsReadAloudEngine changed (yes/no, why):
Step 4: what happens on a second request while one runs; what survives an app kill; analytics events and parameters added, and the sanitizer test:
Step 5: new TtsController members; does iPhone behave exactly as before (yes/no) and how I know:
Step 6: every state of the row that was built (list); every new string key (list); settings row built (yes/no):
Tests from the bug-fix runs edited (name + why), or "none":
Per module: reader ui Android host <passed>/<total>, reader ui iOS <passed>/<total>, settings ui <passed>/<total>, home ui <passed>/<total>, composeApp <passed>/<total>, analytics <passed>/<total>
Does a test resolve the new classes from the real Koin graph (yes/no, name):
App build results (Android assemble, iOS framework):
Device used (model + serial), any command sent to another device (yes/no):
Kokoro chapter: sentences, time to prepare, size on disk, notification showed progress (yes/no):
Playing the prepared chapter: any synthesize line for it (yes/no); pause, resume, skip, double-tap (result of each):
Live playback while another chapter prepares: time to first audio, kept going (yes/no):
Force-stop and Continue: sentences already there, sentences synthesised after (numbers):
Speed change and back: what the row showed each time:
Delete from the row, settings total, Delete all (result of each):
System voice chapter prepared and played (yes/no):
States of the row seen on the phone (list), and states not reached:
State the phone was left in (voice, rate, screen, prepared audio deleted yes/no):
Why the run ended where it did (finished / out of time / blocked, and on what):
Done this run:
Not done or partly done, and why:
Tests skipped, ignored or weakened (file + name + reason), or "none":
Places the brief was ambiguous and what I chose:
Files changed outside feature/reader/ui, feature/settings/ui, lib/analytics, translations, androidApp/src/main/AndroidManifest.xml, composeApp tests and docs:
Known problems I am leaving:
--- END REPORT ---
```
