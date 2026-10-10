# Prepared chapters

## Run status (2026-10-10)

### Continuation checkpoint

Owner brief committed first as `542736b3`. The clean nested comparison
worktree was removed with `git worktree remove` after an empty `git status --short`.
All commands continue in the primary `tts-investigation` worktree.

Dead Play regression pinned before fixing: Listening-sheet Play dispatches
`StartListening` when `isListening` is false (`ReaderOverlay.kt:767–772`).
The extracted `ReaderListeningStart` initially preserves the silent
`ReaderViewModel.kt:2544` availability guard. Its setup-integrated regression
uses an ebook on an empty chapter with loaded voices and availability false:
expected exactly one controller request, actual zero. Focused run: 4/5 pass,
one intended assertion failure. Recorded narration and an unconfigured book
are pinned separately; no existing test was edited. Red checkpoint: `8fe3cfdc`.
The fix allows the request when page availability is false but voices have
loaded. Availability itself still waits for readable text; the controller owns
empty-chapter skipping and playback outcomes. Six-module verification is green:
reader Android 440/440, iOS 324/324, settings 22/22, home 84/84,
composeApp 59/59, analytics 75/75; zero failures/errors/skips from XML.
Phone baseline rechecked: the empty opening page offers System voice at 1×,
Play produces no operation. Fixed-build retest pending; builds in progress.

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

Step 1 (partial): added the small Android-source-set `TtsPreparedAudioEncoder`
interface, a success/failure result type, PCM WAV reader and encode core.
`TtsPreparedAudioEncoderTest` specifies mono PCM16 WAV parsing (including RIFF
chunks and padding), rejection of unsupported/truncated input, atomic output,
failure/cancellation cleanup, measured duration and source/output preservation.
The focused red run failed as intended: 12 tests, four failures (valid mono
PCM, odd chunk padding, successful publication, cancellation propagation).
The other rejection/safety cases passed against the fail-closed stub. The
implementation then replaced that stub without editing the tests. Six-module
verification is green: reader Android 435/435 (423 existing + 12 new), reader
iOS 319/319 (before 319/319), settings 22/22, home 84/84, composeApp 59/59,
analytics 75/75. Counts are from result XML; zero failures/errors/skips. The
existing bug-fix tests are unedited. Both baseline and post-seam final Android
APK/iOS framework builds succeeded. Phone left on the restored baseline APK,
System voice, rate 1×, Library; `files/tts-prepared` does not exist (run-as
checked). No prepared audio was created, so none needed deleting.

The core invokes an injected suspend encoding function, giving it a unique
same-directory `.part` file. Only a nonempty result with positive measured
duration is atomically renamed into the requested output. An exception or
cancellation deletes the staging file; cancellation is propagated. It rejects
an existing destination and a destination aliasing the source. It never edits
the input cache WAV. The parser reads RIFF chunk headers without allocating
from their untrusted lengths, supports mono PCM16, and validates bounds,
alignment and byte rate. Temporary names contain neither text nor titles.

No platform MediaCodec/MediaMuxer adapter or Koin binding has been built yet.
No caller uses this seam. AAC-LC mono 48 kbit/s in M4A remains a candidate, not
the chosen format: six real-sentence measurements and an acoustic join
comparison on the Samsung are mandatory before any storage/playback feature
is built on it. This run stops at the tested host seam; Step 1 is not complete.
Do not infer compression quality or gapless playback from the fake-encoder
tests. They test publication/cleanup and parsing, not the native codec.
No prepared store, manifest, UI state, screen text, resource, foreground service
or analytics event has been added. No encoding measurements have been made.

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

Step 1 is partial; Steps 2–8 remain unbuilt. The platform codec and its six
sentence checkpoint are the next work, before prepared storage or playback.
No cloud, server, database migration, library,
cache-key change, sentence-cache compression, automatic or whole-book
preparation, iPhone TTS, TTS-F15 or TTS-F25 changes.

## For the cloud backup run

There is no prepared chapter to package yet. When storage is implemented, a
chapter must be self-contained as one folder with a versioned manifest and
ordered audio files, so it can be packaged without reader state. Cloud backup
is not part of this run.

## Resume here

Keep the existing bug-fix tests unchanged. Finish the native adapter behind
`TtsPreparedAudioEncoder`, then run the six-sentence checkpoint (three real
Kokoro and three real system-voice WAVs, encoded and played through the existing
read-aloud engine). Record source/encoded byte sizes, each reported duration
delta in milliseconds, and an actual audible WAV/AAC join comparison. Try
padding trimming if needed; select WAV if AAC cannot meet the 50 ms/join gate.
Only then add the prepared store, with tests first and this document current.
The nested comparison worktree contains no authored changes and is retained,
not deleted. It is not the feature branch's working directory.
