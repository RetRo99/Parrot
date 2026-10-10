# TTS run 3b device check — 2026-10-09

Device: Samsung SM-S921B, serial `RFCWC0SSVDM`. Debug build of `aa692706` (the four run 3b
fixes) installed with `adb install -r`; nothing was uninstalled and no app data was cleared.
No other device was sent a command, although two others were attached. The Supertonic terms
were not accepted. The ringer was not touched, so as in run 3a nothing was audible: the
check reads the playback state machine and the sheet, not sound.

File: `logcat-app.txt` — the whole run, app process only (pid 24973), book titles and the
chapter title replaced with `<book title>`.

## System voice: play, five sentences, pause, play, stop — all four worked

- 23:05:42 play: `tts_action=controls, tts_outcome=attempted` → 23:05:44 `succeeded`
  (1573 ms), `onIsPlayingChanged: isPlaying=true`. By 23:06:22 the sheet read
  "Sentence 105 of 188", so well past five sentences.
- 23:06:43 pause: `isPlaying=false` and `Analytics Event: playback_paused`.
- 23:06:53 play: `tts_action=resume` attempted → `succeeded` (5 ms), `isPlaying=true`.
- 23:07:05 stop from "Stop listening": `playback_paused`, `isPlaying=false`, and the now
  playing info went to `null`.

One attempted and exactly one terminal outcome per press, in all four.

## Word speaker: the lookup never opened — three tries, as agreed, then stopped

With narration playing (restarted at 23:07:24, `isPlaying=true`), three long presses on the
page text:

- 23:07:49 `input swipe 540 1000 540 1000 700`
- 23:08:03 `input swipe 400 1300 400 1300 700`
- 23:08:18 `input swipe 600 700 600 700 900`

None opened the word lookup. Each time the `uiautomator` dump that followed showed only the
reader page and the compact now-playing card ("Pause", "Device voice · Voice"), with no
dictionary surface and nothing selection-related in the log. Narration was undisturbed:
the sentence counter went 114 → 120 → 126 across the three tries, and no page turned.

So the word path — TTS-F08's and TTS-F13's user-visible side, and the `SpeakWordCoordinator`
fixes of this run — is **still unverified on the device**, for the same reason as runs 2 and
3a: a synthetic long press on the WebView text does not produce a text selection. It needs a
human finger (run 6). The gesture itself was not investigated.

## Voices sheet: list shown, Kokoro downloaded, no failure banner

Opened from the Listening sheet's "Change", between 23:08:30 and 23:09:00 (the sheet logs
nothing of its own, so the time is from the driving commands). The sheet rendered
"In use: System voice", the Kokoro card as "✓ Downloaded · works offline" with its eleven
voices and previews, and the Supertonic card as "Model terms not accepted yet". **No failure
banner and no retry affordance anywhere** — which is the state TTS-F18's fix is meant to
leave a fresh reader in, though nothing had failed in this session, so this is a sanity check
rather than a reproduction of the stale banner.

## State the phone was left in

System voice, rate 1×, on the Library screen, nothing playing (23:09:12 `playback_paused`,
`isPlaying=false`).
