# Run 5c — short Samsung check

Build source: `702360a9` (fixes `c10784fb`, `790eb981`, `69695a39`), installed with
`adb -s RFCWC0SSVDM install -r`. Samsung SM-S921B, Android 16, One UI 8.0 (`80500`),
package `com.retro99.parrot` 0.4.5 (21), captured process 2771. Local and installed APK
SHA-256 match: `f4079f754e75a66211a59afd43d3b11fd46cbac79b1bcac7b16b9d30bfe24b99`.
Every ADB invocation
explicitly targeted RFCWC0SSVDM; the other attached phone received none. No uninstall,
app-data clear, sign-out, network/ringer/permission change. A mistaken launch component
`.MainActivity` returned "Activity class does not exist"; package resolution supplied
the correct `.android.MainActivity`. This changed no app data.

The phone started at its launcher. Parrot's actual initial state differed from the
previous evidence: Supertonic F1 selected, rate 1.0, prepared chapter audio present.
The Voices sheet already said "Model terms accepted". No terms interaction was made,
no Supertonic playback/preview was requested, and Kokoro Heart was selected immediately.
Only Alice's Adventures in Wonderland (Project Gutenberg, public domain) was used.
No book/chapter titles or sentence text are retained in the process-filtered log.

## Checks (one line each)

Cold process Kokoro Heart start: PARTIAL — force-stop/relaunch, open book, Play once at 11:00:36.080; opening the reader's word warm-up had already logged "Kokoro loaded" at 10:59:58.789 (37.291 s before press), first player isPlaying=true at 11:00:36.864 (0.784 s after press); exactly one controls attempted at .174 and succeeded at .865 (duration_ms=693). This is not evidence of loading a cold model after Play; first audio timing uses the player's audible-playback signal, not an independent acoustic measurement.
System chapter boundary: FAIL — double-tap near the end of chapter I on page 10/11 at 11:03:01.303, sentence_tap attempted 11:03:01.618 / succeeded 11:03:02.857 (1240 ms); page arrived in chapter II at about 11:03:25.3, IDLE 11:03:25.428 / service destroyed .468, no chapter attempted or succeeded. Later Listening showed chapter II, Play and no sentence counter. No user chapter swipe or Stop was sent in that interval. Preserved before any further device test; no product change made to hide this result.
Kokoro chapter boundary: PASS — same near-end sentence on page 10/11, double-tap 11:06:13.299, Listening immediately showed 95 of 97; ENDED 11:06:31.907, one chapter attempted 11:06:32.435 / succeeded 11:06:32.630 (197 ms); kept reading, Listening later showed 14 then 15 of 120 in chapter II, with no stop after the chapter success until the deliberate swipe below.
Swipe to a different chapter while reading: PASS — during the running Kokoro session (including its synthesis gap), close the sheet and swipe backward by hand across chapter II → I beginning 11:07:28.809; IDLE 11:07:30.062, service destroyed .095, now-playing cleared .097; page 10/11 of chapter I and no spontaneous restart before the next explicit test start.
Stop at a sentence end, five times: NOT RUN — pending.
Double-tap inside a prepared chapter: NOT RUN — pending.
Cleanup: NOT RUN — pending.

These are app-side debug Analytics lines only, not Firebase-ingestion or Crashlytics
delivery evidence. This short run does not sign off the wider Samsung QA catalogue.
