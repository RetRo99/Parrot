# Prepared chapters

## Run status (2026-10-10)

Branch `tts/prepared-chapters`, based on `e8ed4319`. The supplied worktree was
detached at that commit. All work and Gradle commands run from that worktree;
no configuration files are created or edited.

Step 0 comparison is complete on Samsung `RFCWC0SSVDM` (SM-S921B). The dead
Listening-sheet Play reproduces for Pride and Prejudice via Continue reading
and Library → Read, on both the baseline APK built at `e8ed4319` and the APK
built at `7045b62b`. Each shows Page 1 of 1, Play remains Play, and no
`tts_playback_operation` or `start_tts_playback` appears in a three-second
app-process log window after pressing Play. Alice's Adventures in Wonderland
plays on both APKs: Page 9 of 14 on baseline, Page 8 of 14 on the old build;
Play becomes Pause, with attempted/succeeded operations (684 ms / 463 ms).

The failure predates run 5b. No lease reversal or product fix is made. Following
the explicit Step 0 instruction to proceed when it is older, Step 1 may start;
the feature is not claimed to fix this pre-existing empty-opening-page problem.
Root cause is not established. A relevant silent guard is
`ReaderViewModel.kt:2544` (`isTtsReadAloud` false); `ReaderTtsSetup.kt:68–72`
only marks availability after readable content. Those are code leads, not
device-confirmed state values. The scope lease cannot explain introduction of
the failure because the old build does not have it. Five tries were not needed:
the failure reproduced and the specified old-build comparison was made.

The old APK was built in a nested detached comparison worktree at
`.claude/worktrees/prepared-chapters-before-5b`; commands were invoked from the
primary worktree using Gradle `-p`, with ANDROID_HOME and no local.properties.
Both APKs were installed with `adb -s RFCWC0SSVDM install -r`; baseline restored
after comparison. No other device was targeted or Supertonic terms accepted.

## Baseline tests

The requested six-module verification command succeeded (existing test tasks
up to date). Counts read from `build/test-results` XML, not console summaries:
reader Android 423/423; reader iOS 319/319; settings 22/22; home 84/84;
composeApp 59/59; analytics 75/75. No bug-fix-run test has been edited.

## What was built

Documentation only so far. No production code, storage, manifest, UI state,
screen text, string resource, foreground service or analytics event has been
added. No encoding measurements have been made.

## Storage and manifest (required, not implemented)

Intended root: `filesDir/tts-prepared`, outside the 128 MB sentence cache.
One chapter folder under each book, with `manifest.json` and audio files named
by the existing sentence-cache key. Paths must be safe for hostile ids/hrefs.
Manifest fields: format version, book id, server id, chapter href, voice id,
model version, rate, pitch, created time, ordered sentence keys with durations
and byte sizes, complete flag, total bytes. No sentence text or titles.
The 1 GB limit evicts oldest complete chapters, excluding the active chapter
and chapters used within ten minutes. Partial chapters survive for resume.

## Button states and text (required, not implemented)

Not prepared; preparing this chapter; preparing another chapter; prepared;
prepared for other settings; partly prepared; failed; voice not usable.
No new screen strings or resource keys exist in this run yet.

## Not done

Steps 1–8 remain unbuilt. No cloud, server, database migration, library,
cache-key change, sentence-cache compression, automatic or whole-book
preparation, iPhone TTS, TTS-F15 or TTS-F25 changes.

## For the cloud backup run

There is no prepared chapter to package yet. When storage is implemented, a
chapter must be self-contained as one folder with a versioned manifest and
ordered audio files, so it can be packaged without reader state. Cloud backup
is not part of this run.
