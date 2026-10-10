# Read-aloud with the screen locked: investigation

Branch `tts/background-playback`, from `ae1c40f8`. Device: Samsung **RFCWC0SSVDM**
(SM-S921B, Android 16 / API 36). Media3 **1.11.1**. Book: *A Psalm for the
Wild-Built* (eBook, on the phone), System voice, rate 1×, pitch Normal.
DND `zen_mode=1` throughout, the value the phone was found at.

This document is step 1 of the run: what happens, why, and whether each fault
predates the recent read-aloud work (`90675a6b`, the merge before any of it). It was
written and committed before any product code changed.

## 1. What happens, in order

Times are from `/tmp`-captured `adb logcat -v threadtime` on 2026-10-10; the filtered
copy is in `manual-qa-evidence/2026-10-10/tts-background-playback/`.

| Time | What |
| --- | --- |
| 20:16:30 | Play pressed in the read-aloud bar. `MediaPlaybackService` is started with `context.startService()` (`ForegroundServiceController.kt:42`). |
| 20:16:38 | `MediaPlaybackController.onServiceCreated`; the engine attaches to the service's raw `ExoPlayer` and audio starts. |
| — | `dumpsys notification` lists **no** Parrot media notification, ever. `dumpsys activity services` shows the service with **`startForegroundCount=0`**: it is a plain background service for the whole session. |
| — | `dumpsys media_session` does publish a session: `package=com.retro99.parrot, active=true, state=PLAYING(3), actions=176, custom actions=[Previous sentence, Next sentence]`. `176 = 16+32+128` = `SKIP_TO_PREVIOUS + SKIP_TO_NEXT + SET_RATING`. **No `PLAY`, `PAUSE`, `PLAY_PAUSE`, `SEEK_TO` or `STOP`.** |
| 20:18:08 | Playing normally, sentence 171 of 300, the bar reads `Pause`. |
| 20:18:18 | Screen locked (`KEYCODE_POWER`). |
| 20:18:38 | `FirebaseSessions: App backgrounded`; `MainActivity.onStop`. Playback carries on. |
| 20:19:18.047 | The last sentence of the session starts (sentence 192). |
| **20:19:18.955** | `W ActivityManager: Stopping service due to app idle: u0a987 -2m46s758ms com.retro99.parrot/com.retro99.reader.ui.playback.MediaPlaybackService` |
| 20:19:18.961 | `MediaPlaybackService.onDestroy` (`MediaPlaybackService.kt:423`), via `ActivityThread.handleStopService` — i.e. the system stopped it, the app did not. |
| 20:19:18.965 | `MediaPlaybackController.onServiceDestroyed`; `stopForwardingFlows`. |
| 20:19:18.973–19.006 | `MediaSessionImpl: Release`, `MediaController: Release`, `ExoPlayerImpl: Release`. |
| 20:19:19.038 | `MediaFocusControl: abandonAudioFocus() from uid/pid 10987/25033`. Audio is over. |
| 20:19:53 | Unlocked. Sentence counter frozen at 192 of 300; the bar still reads `Pause`; no row for the package in `dumpsys media_session`. |
| 20:25:13 | `Pause` pressed. `W MessageQueue: Handler … sending message to a Handler on a dead thread`, with the stack `ExoTtsEnginePlayer.pause(ExoTtsEnginePlayer.kt:69)` ← `TtsReadAloudEngine.pause(TtsReadAloudEngine.kt:330)` ← `AndroidTtsController.togglePlayback(AndroidTtsController.kt:538)`. Nothing happens; the button stays `Pause`. |

The stop is **61 s after the lock**, both times it was measured (20:18:18 → 20:19:18.955
on this branch; 20:38:02 → 20:39:03.708 on the `90675a6b` build). It is not a random
kill: it is the documented rule that when an app becomes idle the system stops its
**background** services, "just as if the app had called `stopSelf()`". "Stop listening"
still works because it never touches the released player.

## 2. Cause of the missing notification

**`MediaPlaybackService.kt:698`** — `LibraryCallback` overrides the *deprecated*
two-argument `MediaSession.Callback.onConnect(session, controller)` and seeds its result
from it:

```kotlin
val defaultCommands = super.onConnect(session, controller)      // :698
…
val playerCommands = Player.Commands.Builder()
    .addAll(defaultCommands.availablePlayerCommands)            // :717
    .add(Player.COMMAND_SEEK_TO_NEXT) …
```

The default body of that deprecated overload is
`MediaSession.getDeprecatedDefaultConnectionResult()`
(`MediaSession.java:1587-1599` in media3 1.11.1), which returns
**`SessionCommands.EMPTY` and `Player.Commands.EMPTY`**. Only the non-deprecated
`onConnect(MediaSession, ControllerInfo, …)` and
`AcceptedResultBuilder(session, controllerInfo)` hand back
`DEFAULT_PLAYER_COMMANDS`. So the commands this session grants every controller —
including Media3's own internal *media notification controller* — are exactly:

* player: the four seek-to-next/previous commands the app adds, intersected with the
  player's own available commands;
* session: `CLIP_CHANGED`, `PREVIOUS_CHAPTER`, `NEXT_CHAPTER`.

Nothing else. Two consequences, both observed:

1. `actions=176` on the platform session: no play, no pause. This is why the
   lock screen and any external controller cannot pause read-aloud, and why
   TTS-F29's device check could not land an outside pause at all — the session never
   offered one.
2. `COMMAND_GET_TIMELINE` and `COMMAND_GET_CURRENT_MEDIA_ITEM` are not granted, so the
   `PlayerInfo` the notification controller receives is filtered down to an **empty
   timeline** (`PlayerInfo.java:890-906`: `canAccessTimeline` false and
   `canAccessCurrentMediaItem` false → empty). `MediaNotificationManager`
   (`MediaNotificationManager.java:377-385`) then returns false from
   `shouldShowNotification` for an empty timeline, so `updateNotification`
   (`:197-199`) calls `removeNotification()` and returns.

`removeNotification()` is also the only reason `startForeground` is never reached:
Media3 promotes the service inside `updateNotificationInternal` → `startForeground`
(`:533-540`), which is never called. Hence `startForegroundCount=0`, hence the service
is an ordinary background service, hence §3.

This is not read-aloud-specific. Recorded narration goes through the same
`MediaPlaybackService`, the same `LibraryCallback`, and the same
`ForegroundServiceController.startService()`; it has the same two faults. Checked on
the phone with *Arthur Golden – Memoirs of a Geisha* (Read-along, downloaded for the
check): the service started, the session was published with `actions=128`
(`SET_RATING` alone) and `startForegroundCount:0`, and the only Parrot notification
was the unrelated `book_downloads` one. **The premise that recorded narration shows a
media notification does not hold on this device — it does not.** There is therefore no
"what narration does that read-aloud does not": the one notification mechanism the app
has is broken for both, and making read-aloud use it is the fix for both.

(Separately, and not pursued: on that book the narration Play press created the service
and the session but playback itself never started — `state=NONE(0)`, position stuck at
`0:00 / 41:44`. Changing how recorded narration plays is out of this run's scope.)

## 3. Cause of the stop while locked

The service is never a foreground service (§2), so it is subject to the background
app-idle stop. `ForegroundServiceController.startService()` uses
`context.startService(intent)` (`ForegroundServiceController.kt:42`), which only starts
it; nothing in the app ever calls `startForeground`, and the one component that would —
Media3's `MediaNotificationManager` — bails out before it because of §2. About a minute
after the activity stops, `ActivityManager` stops the service, `onDestroy` releases the
session and the `ExoPlayer`, and audio focus is abandoned.

So §3 is a **consequence of §2**, not an independent fault. Giving the session its
proper commands is what lets Media3 post the notification, which is what calls
`startForeground`, which is what makes the service survive the screen being off.

## 4. Cause of the dead Pause button

Nothing tells the engine its player is gone. `TtsReadAloudEngine` keeps the
`ExoTtsEnginePlayer` wrapper it was handed (`TtsReadAloudEngine.kt:546`) and keeps
`isSessionRunning` true, because the flags that feed `updateSessionRunning`
(`:818-821`) are only changed by engine calls and by player callbacks — and a released
`ExoPlayer` delivers no callbacks. `MediaPlaybackController.onServiceDestroyed`
(`MediaPlaybackController.kt:532-542`) clears its own `_player`/`_session` and has no
way to reach the engine.

So after the service dies:

* `isSessionRunning` is still true and `isPlaying` is still true, so the bar shows
  `Pause`;
* `AndroidTtsController.togglePlayback` (`AndroidTtsController.kt:536-539`) takes the
  `engine.pause()` branch;
* `engine.pause()` (`TtsReadAloudEngine.kt:330`) calls `player.pause()` on the released
  player, which throws nothing and does nothing but log
  `sending message to a Handler on a dead thread`.

The button is therefore dead in both directions: it cannot pause (there is nothing
playing) and it cannot play (it never reaches the play path). `TtsEnginePlayer` has no
signal for "this player is gone", which is the smallest thing missing.

Note that the engine already copes with a *null* player: `resume()`
(`TtsReadAloudEngine.kt:339-345`) starts the sentence again when `player == null`, and
`onPlayPressed` (`TtsPlaybackAttempts.kt:128`) chooses the resume path from
`engine.currentSentence.value != null`. So once the engine learns the player went away
and drops it while keeping `currentIndex`, one press recovers with the existing code.

## 5. Which faults predate `90675a6b`

`90675a6b` ("Merge branch 'opds/phase4-screens'") is main before any of the recent
read-aloud work. Built in a temporary worktree, installed, driven through the same
steps, then removed.

| Fault | Predates `90675a6b`? | Evidence |
| --- | --- | --- |
| No media notification | **Yes** | On the `90675a6b` build: no Parrot media notification, `startForegroundCount:0`, session `actions=176`, while playing sentence 140 of 300. `git diff 90675a6b ae1c40f8 -- …/playback/` touches only `AndroidNowPlayingProvider.kt` and `ReadAloudPlayback.kt`; `MediaPlaybackService.kt` and `ForegroundServiceController.kt` are byte-identical, and the `onConnect` block diffs clean. |
| Stop while locked | **Yes** | On the `90675a6b` build: locked 20:38:02, `Stopping service due to app idle … MediaPlaybackService` 20:39:03.708 (61.7 s), frozen at sentence 172 of 300. |
| Dead Pause button | **Yes** | On the `90675a6b` build the bar still read `Pause` after the freeze, and pressing it produced the same `dead thread` warning from `ExoTtsEnginePlayer.pause` and no state change. |

**None of TTS-F25, TTS-F26 or TTS-F29 is implicated.** Those three changed *when* the
engine considers a session running; this bug is that the engine is never told the
player stopped existing, which was equally true before `isSessionRunning` existed. On
`90675a6b` the stale "playing" belief came from `_isPlaying` instead, with the same
visible result. What the recent work does contribute is the opposite of a cause: the
`player == null` handling in `resume()` means the recovery needs no new start logic.

## 6. What is fixed in this run

Four commits of product code, each with its failing test committed first. The device
pass is `manual-qa-evidence/2026-10-10/tts-background-playback/`.

| Fix | Commit | What it does |
| --- | --- | --- |
| The session's commands | `522747ed` | `LibraryCallback.onConnect` seeds its result from `ConnectionResult.AcceptedResultBuilder(session, controller)` instead of the deprecated `super.onConnect`. The session now offers play and pause (`actions` 176 → 7339979) and Media3's notification controller can see the timeline, so it posts the notification and, doing so, calls `startForeground`. |
| The notification's buttons | `522747ed` | `mediaButtonSpecs()` is a pure function, host tested: read-aloud puts previous and next sentence in the two slots beside play/pause and offers no ten second seek inside a sentence; recorded narration keeps exactly the buttons it had. |
| The chapter handover | `0d5fd910`, `673670f1` | An auto-advancing chapter end keeps the service's player and its audio, and a `stop()` with nothing loaded — which is the locator move that handover causes — leaves the service alone. Without both, the service was stopped and started again from the background, which the system refuses to promote (`startForegroundService() not allowed due to mAllowStartForeground false`), and it was then idle-stopped mid-chapter. |
| The screen never lying | `e37d63e2`, `0340dbef` | `TtsEnginePlayer` gains one signal, `onPlayerGone`, raised by the player provider when `MediaPlaybackController` says the service is going. The engine ends the session but keeps the sentence, so `isSessionRunning` and `isPlaying` go false, the button shows Play, a late callback from the old player starts nothing, and one press starts the same sentence again through `resume()`'s existing no-player path. |

What the device pass established, with a System voice and with Kokoro "Heart":
three minutes locked keeps reading (9 → 63 and 141 → 183 of 347); the notification is on
the lock screen and in the shade with previous sentence, pause/play and next sentence;
pause and play from it work and play resumes the same sentence; pause from the
notification then **one** press on Play in the app continues — the device check TTS-F29
never got; a chapter boundary is crossed with the screen off and the service is never
destroyed; Stop listening removes the notification, the session and the service; and
`am stopservice` during playback leaves the button showing Play with one press
restarting it.

### What is not fixed

1. **Recorded narration still does not play on this phone.** It prepares its chapter and
   then nothing happens — `state=NONE(0)`, position stuck at `0:00`. Identical on the
   pre-fix build, so it is not from this run, and changing how recorded narration plays
   was out of scope. Its session does now carry the full command set.
2. **A one-page section does not auto-advance.** A seven-sentence, one-page back-matter
   section was read to its end twice and no next-chapter attempt followed.
   `ReaderSyncCoordinator.onChapterCompleted` waits for a locator whose href differs
   from the completed one, which such a section seems not to produce. Multi-page
   chapters do advance. Shared with the recorded-narration path.
3. **A chapter end that does not auto-advance now leaves the service up.** That is the
   price of keeping it through the handover: the notification can sit showing a finished
   chapter until the app is idle-stopped or the user presses Stop listening.
4. **Noise at every handover.** The playlist swap takes the player through `IDLE`, so
   Media3 calls `startForegroundService` on a service that is already foreground and the
   system logs a refusal. Playback is unaffected.
5. The cosmetic voice label, recorded as asked and not fixed: the read-aloud bar says
   "Device voice · Heart" for a Kokoro voice while the Listening sheet says
   "Kokoro · Heart" (and "Device voice · F1" against "F1 · Natural" for Supertonic).
