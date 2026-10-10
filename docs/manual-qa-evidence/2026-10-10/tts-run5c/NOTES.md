# Run 5c — short Samsung check

Initial build source: `702360a9` (fixes `c10784fb`, `790eb981`, `69695a39`), installed with
`adb -s RFCWC0SSVDM install -r`. Samsung SM-S921B, Android 16, One UI 8.0 (`80500`),
package `com.retro99.parrot` 0.4.5 (21), captured process 2771. Local and installed APK
SHA-256 match: `f4079f754e75a66211a59afd43d3b11fd46cbac79b1bcac7b16b9d30bfe24b99`.
Final build source: `13ac0530` (docs-only checkpoint, includes ownership fix `d71e85f2`),
update-installed with `install -r`, captured process 19564. Local and installed APK
SHA-256 match: `93272d636c3617a635de038611b552f3d90a37d4907f433911808a23790b728d`
(184526207 bytes). Final requested tests/builds and exact commands are in `verification.txt`.
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

## Initial-build checks (historical results preserved)

Cold process Kokoro Heart start: PARTIAL — force-stop/relaunch, open book, Play once at 11:00:36.080; opening the reader's word warm-up had already logged "Kokoro loaded" at 10:59:58.789 (37.291 s before press), first player isPlaying=true at 11:00:36.864 (0.784 s after press); exactly one controls attempted at .174 and succeeded at .865 (duration_ms=693). This is not evidence of loading a cold model after Play; first audio timing uses the player's audible-playback signal, not an independent acoustic measurement.
System chapter boundary: FAIL — double-tap near the end of chapter I on page 10/11 at 11:03:01.303, sentence_tap attempted 11:03:01.618 / succeeded 11:03:02.857 (1240 ms); page arrived in chapter II at about 11:03:25.3, IDLE 11:03:25.428 / service destroyed .468, no chapter attempted or succeeded. Later Listening showed chapter II, Play and no sentence counter. No user chapter swipe or Stop was sent in that interval. Preserved before any further device test; no product change made to hide this result.
Kokoro chapter boundary: PASS — same near-end sentence on page 10/11, double-tap 11:06:13.299, Listening immediately showed 95 of 97; ENDED 11:06:31.907, one chapter attempted 11:06:32.435 / succeeded 11:06:32.630 (197 ms); kept reading, Listening later showed 14 then 15 of 120 in chapter II, with no stop after the chapter success until the deliberate swipe below.
Swipe to a different chapter while reading: PASS — during the running Kokoro session (including its synthesis gap), close the sheet and swipe backward by hand across chapter II → I beginning 11:07:28.809; IDLE 11:07:30.062, service destroyed .095, now-playing cleared .097; page 10/11 of chapter I and no spontaneous restart before the next explicit test start.
Stop at a sentence end, five times: PASS — live Kokoro, ENDED → IDLE at 11:11:11.078 → .170 (92 ms), 11:11:36.417 → .520 (103 ms), 11:12:01.536 → .605 (69 ms), 11:12:26.812 → .916 (104 ms), 11:12:52.013 → .116 (103 ms); zero restarted on its own, no isPlaying=true or new operation between each stop and the next explicit Play. A log watcher sent Stop on ENDED using a fresh active-layout dump after Play. One earlier automation attempt used the idle sheet's Stop position, which shifts when the sentence counter appears, and did not hit Stop; excluded, not a failure/pass, corrected before these five valid attempts.
Prepared fixture: 39-sentence front-matter chapter prepared again at 11:15:29.826,
completed 11:15:57.645, duration_ms=27819; Listening showed Ready, 654 kB.

## Final-build checks and cleanup

System chapter boundary: PASS — near-end double-tap host 11:23:13.732;
sentence_tap attempted 11:23:14.064 / succeeded 11:23:14.222 (159 ms).
Chapter attempted 11:23:44.269 / succeeded 11:23:45.436 (1167 ms), one pair;
Listening showed Pause and sentence 28 then 31 of 120 in chapter II, page 3/10.
No subsequent IDLE until the explicit Stop at 11:26:30.964 (service destroyed
11:26:31.035, now-playing cleared .037). IDLE at 11:23:43.760 precedes the
hand-off and is not a post-success stop. The earlier System FAIL is retained above;
this retest does not retroactively make that attempt a pass.

Double-tap inside a prepared chapter: PASS — Play attempted 11:21:33.313 /
succeeded 11:21:33.592 (280 ms), Listening showed sentence 1/39, page 1/4.
Two taps at (650,1445), 150 ms apart, host 11:21:38.848; sentence_tap attempted
11:21:39.190 / succeeded 11:21:39.348 (160 ms); Listening then showed 4/39 on
the same page. Zero `Kokoro synthesize start` lines from playback start through
explicit Stop 11:22:22.461. Prepared audio was reused, not regenerated.

Stop at a sentence end, five times: PASS — live Kokoro Heart at 1.0, final build,
ENDED → IDLE at 11:27:52.918 → 11:27:53.004 (86 ms),
11:28:35.793 → .876 (83 ms), 11:29:47.399 → .486 (87 ms),
11:30:43.316 → .390 (74 ms), 11:31:40.968 → 11:31:41.045 (77 ms).
Zero spontaneous restarts: no isPlaying=true or new playback operation after
each Stop until the next explicit Play; final Stop stayed stopped through cleanup.
An additional attempt's short sentence ended during the active-layout dump:
ENDED 11:30:10.163, ordinary next audio 11:30:10.555, Stop/IDLE 11:30:12.817.
That 2654 ms-delayed Stop had no post-Stop restart but is excluded from the five
rapid end-triggered checks and replaced by the 11:31:40.968 attempt. This was
automation timing, not a restart after Stop. Host press times precede some device
timestamps by about 20 ms (clock offset); elapsed timings above use device logs only.
The five initial-build checks remain preserved separately above. These stress checks
do not prove a posted callback actually arrived after Stop; the host fake covers that ordering.

Cleanup: PASS — playback stopped, System voice selected, rate 1× and Play (idle)
verified in Listening. Settings → Reader settings → Read aloud → Delete all… →
Delete all confirmed the authorized deletion of all prepared audio (5.6 MB before).
UI then said "Nothing prepared yet"; read-only
`adb -s RFCWC0SSVDM shell run-as com.retro99.parrot find files/tts-prepared -type f`
returned exit 0 and zero files. Closed settings and selected Books; Library verified.
Books, profiles, model packs and app data otherwise retained. No system setting changed.

The final-build Kokoro boundary and deliberate chapter-swipe were not repeated;
their initial-build results are explicitly labelled above, not relabelled final-build passes.
Cold-after-Play load timing and independent acoustic onset remain unmeasured.
Both exact host logcat captures were stopped after cleanup. `tts-run5c-logcat.txt`
contains only allowlisted TTS/model/service/operation lines from the two process captures;
now-playing is reduced to playing/session-presence booleans. Raw model configuration,
paths, titles, sentence text, book IDs and unrelated events are omitted. The raw logs
remain host-temporary only and are not committed.

These are app-side debug Analytics lines only, not Firebase-ingestion or Crashlytics
delivery evidence. This short run does not sign off the wider Samsung QA catalogue.
