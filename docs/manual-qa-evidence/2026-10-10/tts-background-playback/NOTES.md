# Read-aloud with the screen locked — device pass, 2026-10-10

Device: Samsung **RFCWC0SSVDM** (SM-S921B, Android 16 / API 36). Every `adb` call carried
`-s RFCWC0SSVDM`. **No command was sent to the Xiaomi** (`192.168.1.248:5555`), which was
attached throughout. Do Not Disturb was `zen_mode=1` at the start and at the end; it was
never changed. `POST_NOTIFICATIONS` was granted throughout.

Book for read-aloud: `<BOOK A>` (eBook, on the phone). Logs are redacted of book,
author and chapter titles.

Builds, in order:
- **B0** = `ae1c40f8` (this branch's base, the bug as reported) — `log-before-any-fix.txt`
- **B1** = a build of `90675a6b` (main before any recent read-aloud work) — `log-old.txt`
- **B2** = `522747ed` (the session-commands fix) — `log-fix.txt`
- **B3** = `0d5fd910` (plus the chapter handover) — `log-step4.txt`
- **B4** = `673670f1` (plus the stop-with-nothing-loaded fix) — `log-final.txt`

## Part A — Step 1: reproduce and attribute (build B0)

A1  READ-ALOUD HAS NO MEDIA NOTIFICATION: confirmed. Playing from 20:16:38 onwards,
    `dumpsys notification` listed no Parrot media notification at any point, and
    `dumpsys activity services` showed `MediaPlaybackService` with
    **`startForegroundCount=0`** — a plain background service for the whole session.
A2  THE SESSION OFFERS NO PLAY OR PAUSE: `dumpsys media_session` gave
    `state=PLAYING(3), actions=176, custom actions=[Previous sentence, Next sentence]`.
    176 = 16+32+128 = SKIP_TO_PREVIOUS + SKIP_TO_NEXT + SET_RATING. No PLAY, PAUSE,
    PLAY_PAUSE, SEEK_TO or STOP. This is why TTS-F29's device check could never land an
    outside pause.
A3  FREEZE AFTER LOCK: reproduced on the **first** try. Playing sentence 171 of 300,
    bar read `Pause`; locked 20:18:18. Last sentence started 20:19:18.047. Then:
      20:19:18.955 `W ActivityManager: Stopping service due to app idle: u0a987 … MediaPlaybackService`
      20:19:18.961 `MediaPlaybackService.onDestroy` via `ActivityThread.handleStopService`
      20:19:18.973–19.006 `MediaSessionImpl: Release`, `MediaController: Release`, `ExoPlayerImpl: Release`
      20:19:19.038 `MediaFocusControl: abandonAudioFocus() from uid/pid 10987/25033`
    61 s after the lock. Unlocked 20:19:53: counter frozen at 192 of 300, bar still read
    `Pause`, no row for the package in `dumpsys media_session`.
    (A second lock 20:20:37–20:23:52 stayed frozen at 192, as the earlier run saw.)
A4  WAS IT A FOREGROUND SERVICE: **no**. `startForegroundCount=0`, no `isForeground=true`,
    `startForeground` never called anywhere on the read-aloud path.
A5  DEAD PAUSE BUTTON: pressed at 20:25:13. Log:
    `W MessageQueue: Handler … sending message to a Handler on a dead thread` with
    `ExoTtsEnginePlayer.pause(ExoTtsEnginePlayer.kt:69)` ←
    `TtsReadAloudEngine.pause(TtsReadAloudEngine.kt:330)` ←
    `AndroidTtsController.togglePlayback(AndroidTtsController.kt:538)`. No state change;
    the button stayed `Pause`. "Stop listening" did recover, as reported.
A6  RECORDED NARRATION, SAME FAULTS: `<BOOK B>` (Read-along) was downloaded for the
    comparison. Its Play created the service and published a session, with
    `startForegroundCount:0`, `actions=128` (SET_RATING alone) and **no media
    notification** — only the unrelated `book_downloads` one. So the premise that
    recorded narration shows a media notification does not hold on this phone.
    Separately, and not this run's business: narration itself never started playing
    (`state=NONE(0)`, stuck at `0:00 / 41:44`) — see D2.

## Part B — Step 1: does it predate 90675a6b (build B1)

B1  NOTIFICATION SHOWN: **no**. Playing sentence 140 of 300 at 20:37:40,
    `startForegroundCount=0`, session `actions=176`, no Parrot media notification.
B2  FREEZE AFTER LOCK: **yes**. Locked 20:38:02;
    `Stopping service due to app idle: u0a987 … MediaPlaybackService` 20:39:03.708
    (61.7 s); unlocked 20:39:43 frozen at sentence 172 of 300.
B3  DEAD PAUSE: **yes**. Bar read `Pause`; pressed 20:40:00 and the same
    `dead thread` warning appeared with no state change.
B4  So **all three faults predate `90675a6b`**, and none of TTS-F25/F26/F29 is
    implicated. `git diff 90675a6b ae1c40f8 -- …/playback/` touches only
    `AndroidNowPlayingProvider.kt` and `ReadAloudPlayback.kt`; `MediaPlaybackService.kt`
    and `ForegroundServiceController.kt` are byte-identical and the `onConnect` block
    diffs clean.
B5  The temporary worktree at `/tmp/parrot-90675a6b` was removed with
    `git worktree remove --force`; `git worktree list` no longer shows it. This
    branch's build was reinstalled afterwards.

## Part C — Step 4: prove the fix on the phone

### C1 Three minutes with the screen off

C1  SYSTEM VOICE (B4): sentence **9 → 63 of 347**, still reading. Locked 21:45:14,
    unlocked 21:48:24 (3 min 10 s). `isForeground=true`, `startForegroundCount=1`,
    bar read `Pause`. Zero `Stopping service due to app idle` lines for `u0a987` in the
    whole B4 log.
C1  KOKORO "HEART" (B4): sentence **141 → 183 of 347**, still reading. Locked 21:56:45,
    unlocked 22:00:00 (3 min 15 s). `isForeground=true`, `startForegroundCount=1`.
C1  Earlier, on B2: sentence 175 → 264 of 300 across 20:57:13–21:00:20, also still
    reading, which is where the fix was first seen to work.

### C2 The notification itself

C2  LOCK SCREEN: shown. `C1-lockscreen-notification.png` — the media pill at the bottom
    of the lock screen with previous, pause, next and the chapter title.
C2  SHADE: shown. `C2-shade-notification.png` — one media card under "Live
    notifications" with the chapter title, the book title, a chapter progress bar
    (06:33 / 11:49) and previous / pause / next. Nothing beyond what the
    recorded-narration notification shows: it is the same `DefaultMediaNotificationProvider`
    on the same metadata.
C2  `dumpsys notification`: `id=1001 channel=default_channel_id actions=3
    category=transport groupKey=media3_group_key flags=ONLY_ALERT_ONCE|NO_CLEAR|FOREGROUND_SERVICE`.
C2  SESSION ACTIONS: `7339979` where B0 had `176`. Decoded: STOP, PAUSE, PLAY_PAUSE,
    SEEK_TO, REWIND, FAST_FORWARD, PREPARE*, SET_* — i.e. play and pause are offered.
C2  Button bounds dumped from the shade: `Previous sentence [347,774][426,853]`,
    `Pause [500,774][579,853]`, `Next sentence [653,774][732,853]`.

### C3 Driving it from the notification

C3  PAUSE from the notification (21:49:02): worked. The notification button flipped to
    `Play` and the session went `PAUSED(2)`. **The app's own bar also showed `Play`** —
    the screen did not lie.
C3  PLAY from the notification (21:50:15) after a 12 s wait: continued **on the same
    sentence**, 81 → 81, bar back to `Pause`.
C3  NEXT from the notification: delivered — `onCustomCommand: com.retro99.NEXT_CHAPTER`
    → `navigateToNextChapter()` per press, 5 in the run. Four presses 1 s apart moved
    98 → 103 (four skips forward plus the playback it also resumes).
C3  PREVIOUS from the notification: delivered — `onCustomCommand:
    com.retro99.PREVIOUS_CHAPTER` → `navigateToPreviousChapter()` per press, 6 in the
    run. Four presses 1 s apart moved 98 → 97, i.e. net backwards against playback that
    is running forward at the same time. An exact ±1 could not be read off the counter,
    because a skip also lifts the pause and starts playing, so the sentence advances
    again while the dump is taken; the per-press command and the restart at the adjacent
    index are in the log (e.g. 21:52:25.464 PREVIOUS_CHAPTER → READY and
    `isPlaying=true` at 21:52:25.598 with no new synthesis, the clip already cached).

### C4 Notification pause, then ONE press on Play in the app

C4  PASS — this is the device check TTS-F29 never got. Paused from the notification at
    21:49:02; the app's bar read `Play` at sentence 73 of 347. **One** press at
    21:49:27 → `Pause`, sentence 76, session `PLAYING(3)`.

### C5 Chapter boundary with the screen off

C5  PASS on B4. Locked 21:35:52 playing sentence 499 of 587 of `<CHAPTER 8>`.
    21:40:40.620 the chapter's audio ended; 21:40:41.152 `tts_action=chapter,
    tts_outcome=attempted`; 21:40:41.796 `succeeded, duration_ms=643`; the next section
    then played until 21:40:55. The service was created once (21:35:24) and **was never
    destroyed**: no `onDestroy`, no `Stopping service due to app idle`.
C5  On B3 the same boundary already crossed but the service was still taken down by the
    stop that follows the handover: `state=IDLE` 21:29:01.329, `onDestroy` 21:29:01.348,
    then ten `startForegroundService() not allowed due to mAllowStartForeground false`
    lines and `startForegroundCount=0`. That is what the last fix removes.
C5  On B0 the same boundary was fatal: `onDestroy` 21:02:10.123, service created again
    21:02:11.732, twenty-odd `mAllowStartForeground false` refusals, then
    `Stopping service due to app idle` 21:03:09.998 — dead 58 s into the new chapter,
    eight sentences in.
C5  A residual, harmless noise: at the handover the playlist swap takes the player
    through `IDLE`, and Media3 then calls `startForegroundService` on a service that is
    already foreground, which the system logs as refused. Playback is unaffected and the
    service stays foreground, because it is never destroyed.

### C6 Stop listening

C6  PASS. "Stop listening" at 22:00:38 → `dumpsys activity services` has **no**
    `MediaPlaybackService` row, `dumpsys media_session` has **no** row for the package,
    and the shade has no Parrot card (`C5-shade-after-stop-listening.png`). The only
    `id=1001` lines left in `dumpsys notification` are under "Notification attention
    state", which is history, not the live list.

### C7 The player taken away, the gentlest way available

C7  PASS. Playing sentence 106 of 347; `adb shell am stopservice
    com.retro99.parrot/com.retro99.reader.ui.playback.MediaPlaybackService` at 21:54:42
    → "Service stopped", no service row. The app's bar **immediately showed `Play`** and
    kept sentence 106. **One** press at 21:55:03 started it again: `Pause`, sentence 108,
    `isForeground=true`, `startForegroundCount=1`, notification back. No
    `dead thread` warning anywhere in the B4 log.

### C8 Recorded narration

C8  NOT ESTABLISHED, and not a regression. `<BOOK B>` (Read-along, downloaded) prepares
    its chapter — `playInternal: service ready`, `prepareChapter`, `SERVICE
    setChapterClips: count=308`, `prepareChapterAsync returned success=true` — and then
    nothing: no player state change, `state=NONE(0)`, position stuck at `0:00 / 41:44`,
    button `Play`, `startForegroundCount=0`. Identical on build B0 before any change
    (20:32:06 and 20:32:56), so it is not caused by this run. Its session does now carry
    the full command set (`actions=7339653` against `128` on B0). Changing how recorded
    narration plays was out of scope, so it was recorded and left alone.

## Part D — recorded, not fixed

D1  COSMETIC, as the brief asked: the read-aloud bar labels a neural voice
    "Device voice · <name>" while the Listening sheet calls it by its pack.
    Seen with Kokoro at 21:56: the bar read **"Device voice · Heart"** while the sheet
    read **"In use: Kokoro · Heart"**. The brief reported the same for a Supertonic
    voice ("Device voice · F1" against "F1 · Natural").
D2  Recorded narration does not start playing on this phone (C8). Pre-existing.
D3  A one-page back-matter section does not auto-advance: `<BACK MATTER>` (7 sentences,
    Page 1 of 1) was read to its end at 21:40:55 and 21:15:57 and no chapter attempt
    followed, so read-aloud simply stopped. A multi-page chapter does advance (C5).
    `ReaderSyncCoordinator.onChapterCompleted` waits for a locator whose href differs
    from the completed one, which such a section seems not to produce. Shared with the
    recorded-narration path; not touched.
D4  After a chapter end that does not auto-advance, the service is now kept rather than
    stopped, so the notification can sit there showing a finished chapter until the app
    is idle-stopped or the user presses Stop listening. This is the price of C5 and is
    noted as a known problem rather than fixed.

## State left behind

Phone: on the **Library** screen. Voice **System voice**, rate **1×**, pitch **Normal**,
sleep timer **Off**. Do Not Disturb **`zen_mode=1`**, the value it was found at and never
changed. No `MediaPlaybackService` running, no media session, no Parrot notification in
the shade. Kokoro (149 MB) and Supertonic (145 MB) packs untouched and still installed.

Changed on the phone by this run, beyond playback: two read-along books were
**downloaded** for the narration comparison — `<BOOK C>` and `<BOOK B>` — and were left
downloaded. `<BOOK A>`'s reading position moved from 66 % to about 75 % by read-aloud
itself. The app was reinstalled five times (B0, B1, B2, B3, B4).

No command was sent to the Xiaomi.
