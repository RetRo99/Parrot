# Outside-pause follow-up, 2026-10-10

Samsung SM-S921B, RFCWC0SSVDM; Parrot 0.4.5 (21), app PID 8103. Update installed with `adb -s RFCWC0SSVDM install -r`; no data/config/permission/network/ringer changes, no other device targeted. Supertonic was not selected and no terms were accepted during this run. All reader controls were located from fresh uiautomator trees; control reveal settled for 0.9 s before the Listen long press. Retained log is process-scoped and content-minimized; raw hierarchy/screenshots are not committed.

System outside pause → one sheet Play: BLOCKED — dispatch pause at 11:52:08.995 and again around 11:52 left the player PLAYING and sheet Pause; no outside pause was delivered, so no valid one-press resume operation pair.
System outside pause → speed change → Play: BLOCKED — cannot establish an outside-paused device session; did not substitute an in-app pause or claim this variant passed.
System outside pause → outside play: BLOCKED — cannot establish an outside-paused device session.
System chapter boundary: PASS — advanced with sentence-skip controls to 119/120, then no further skip; natural chapter attempted 11:55:26.970 / succeeded 11:55:27.164 (196 ms), continued to sentence 3 in the next chapter before deliberate in-app pause at 11:55:42.112.
Kokoro Heart outside pause → one sheet Play: BLOCKED — dispatch pause 11:56:42.738 left the sheet Pause at sentence 5/120; no valid one-press outside-pause resume trial.
Kokoro Heart outside pause → speed change → Play: BLOCKED — cannot establish an outside-paused device session.
Kokoro Heart outside pause → outside play: BLOCKED — cannot establish an outside-paused device session.
Kokoro Heart chapter boundary: PASS — advanced with sentence-skip controls to 119/120, then allowed natural completion; chapter attempted 11:58:33.832 / succeeded 11:58:36.458 (2627 ms), continued across synthesis gaps to sentence 8/151 in the next chapter.
Kokoro gap outside-pause attempt: BLOCKED — one command landed between ENDED 11:58:14.766 and synthesis done 11:58:23.034: dispatch pause host time 11:58:22.506; player became audible 11:58:23.074. Command delivery is ineffective here, so this is not evidence that an accepted outside pause restarted audio. No valid pause-in-gap trial; did not repeat an ineffective command five times.
Outside-control blocker: Parrot was the active media-button session, PLAYING, with legacy actions=176; a direct `cmd media_session monitor` pause also had no effect. Notification shade showed no Parrot transport control. No other finding investigated or media-service code changed.
Final state: verified System voice, rate 1.0 (sheet showed 1×), Stop listening used, Library visible in final fresh hierarchy. No ongoing narration retained.

Verification: reader Android host 545/545, reader iOS 357/357, composeApp Android host 64/64, zero failures/errors/skips in result XML. Requested test command and Android assembly/iOS simulator framework command both BUILD SUCCESSFUL. Local and installed APK SHA-256 match: `d136ddf360a003eb80162f4cc2a39c78884c820484b9b26893a17d915fb8b217`. All three protected regression classes pass unedited. Ten new tests ran before any engine fix: six failed and four already passed.
