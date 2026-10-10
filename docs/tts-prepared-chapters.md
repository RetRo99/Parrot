# Prepared chapters

## Run status (2026-10-10)

Branch `tts/prepared-chapters`, based on `e8ed4319`. The supplied worktree was
detached at that commit. All work and Gradle commands run from that worktree;
no configuration files are created or edited.

Step 0 is the gate: reproduce the dead Listening-sheet Play on Samsung
`RFCWC0SSVDM`, compare `7045b62b` if it reproduces, and do not implement prepared
chapters on a reader that cannot play. Device checks are pending. Only the
Samsung is targeted; no Supertonic terms are accepted.

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

Steps 1–8 await the Step 0 gate. No cloud, server, database migration, library,
cache-key change, sentence-cache compression, automatic or whole-book
preparation, iPhone TTS, TTS-F15 or TTS-F25 changes.

## For the cloud backup run

There is no prepared chapter to package yet. When storage is implemented, a
chapter must be self-contained as one folder with a versioned manifest and
ordered audio files, so it can be packaged without reader state. Cloud backup
is not part of this run.
