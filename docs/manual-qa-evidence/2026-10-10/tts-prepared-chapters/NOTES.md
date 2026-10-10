# Prepared chapters: Step 0 comparison

## Continuation: dead Play fix

## Step 1: six-sentence format gate

- Native adapter tree `eca84193`, Samsung SM-S921B RFCWC0SSVDM; probe synthesized three real Gutenberg sentences per kind (Heart, default system voice), and played all 3 indices in both WAV and M4A via unchanged read-aloud engine with real local ExoPlayer. No title/text in retained log.
- Probe PID 17556: Kokoro WAV/M4A bytes + duration delta: 76970/11190 +104 ms; 99606/14050 +102 ms; 127086/17690 +127 ms. System: 105764/14909 +102 ms; 103340/14570 +109 ms; 141262/19364 +87 ms.
- Padding trim trial: lossless remux with encoder-delay 1024 samples and calculated padding; MediaMuxer discarded both metadata keys, duration differences unchanged on all six. Fallback selected: WAV. No risky spoken-packet removal or engine change.
- Audible joins NOT RUN (no audio-monitoring capability). Numeric duration gate fails independently. WAV fallback preserves exact input bytes and duration; no extra encoder padding.
- Probe code/build script committed in this folder; tiny test-only instrumentation APK installed via `install -r`, left installed because uninstall prohibited. Probe folder/audio removed before finish; exact early empty folder removed with `rmdir`. No unrelated data or settings changed.

- Samsung SM-S921B, Android 16, serial RFCWC0SSVDM; Parrot 0.4.5 (21), notification permission already granted. Parrot was foreground; no unrelated app interaction.
- Before fix: System voice 1×, empty opening Page 1 of 1, Play gave zero playback operations in 3 s (PID 30907).
- Fix code `071d8867`, installed with `install -r`: same book via Resume, fresh Listen bounds after middle tap and 0.9 s wait; long press opened sheet. Play moved into the first text chapter, showed Pause and Sentence 2 of 397; playback then paused.
- One attempted and one succeeded event, duration_ms 1327, PID 8576, one correlated start/terminal breadcrumb. See `dead-play-fixed-logcat.txt`; no title or text retained in log.
- Host regression failed before fix: expected one controller request, got zero. Green six-module XML counts: reader Android 440/440, iOS 324/324, settings 22/22, home 84/84, composeApp 59/59, analytics 75/75, no skips. Android assemble and iOS framework succeeded.

Samsung SM-S921B, RFCWC0SSVDM, package com.retro99.parrot. Every adb command
explicitly targeted this serial. Install -r only, no uninstall, data clear,
sign-out, network/ringer changes, or Supertonic terms acceptance.

- Baseline six-module verification: reader Android 423/423, reader iOS 319/319, settings 22/22, home 84/84, composeApp 59/59, analytics 75/75 (XML counts).
- Baseline Android assemble and iOS framework: successful.
- Initially installed APK, Continue reading, first book: Page 1 of 1; Play unchanged; zero TTS operations in 3 s (PID 22178); APK provenance not asserted.
- Built baseline e8ed4319, Continue reading, first book: Page 1 of 1; Play unchanged; zero TTS operations in 3 s (PID 25063).
- Built baseline e8ed4319, Library → details → Read, first book: Page 1 of 1; Play unchanged; zero TTS operations in 3 s (PID 25063).
- Built baseline e8ed4319, Library → details → Read, second book: Page 9 of 14; Play became Pause; attempted then succeeded, duration_ms 684 (PID 25063).
- Built 7045b62b, Library → details → Read, first book: Page 1 of 1; Play unchanged; zero TTS operations in 3 s (PID 27879).
- Built 7045b62b, Continue reading → Resume, first book: Page 1 of 1; Play unchanged; zero TTS operations in 3 s (PID 27879).
- Built 7045b62b, Library → details → Read, second book: Page 8 of 14; Play became Pause; attempted then succeeded, duration_ms 463 (PID 27879).
- Attribution: predates run 5b; root cause unproven; no product/test changes in Step 0.
- Baseline APK restored with install -r after comparison.

Book names and authors are deliberately omitted from this evidence file and
the filtered log. The feature document identifies the two comparison books.
Only operation analytics/breadcrumb lines are retained, excluding library
dumps, chapter metadata and unrelated stack traces. Acoustic output was not
independently recorded; playback confirmation is player state and engine logs.

The first second-book attempt on the old build hit an existing highlight when
tapping the page, opening Highlight instead of controls. It was closed without
editing it; tapping the page at (540,1170), then waiting 0.9 s and taking fresh
Listen bounds, opened Listening. No saved highlight was changed.

Feature checks, compression measurements and row screenshots: not run; native
codec and row are not built. Step 1 is partial, host seam only.

- Final six-module verification: reader Android 435/435 (423 original plus 12 new), reader iOS 319/319 (before 319/319), settings 22/22, home 84/84, composeApp 59/59, analytics 75/75; zero skips/errors/failures from XML.
- Final Android assemble and iOS framework: successful after encoder-core implementation.
- Final phone check: Listening shows System voice and 1×; reader closed via fresh Back bounds; Library visible; run-as test reports files/tts-prepared absent. Baseline APK restored, no prepared audio created or deleted.
