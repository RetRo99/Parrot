# Prepared chapters: Step 0 comparison

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
