# Recorded narration does not play: investigation

Branch `tts/narration-playback`, from `804ac41e`. Device: Samsung **RFCWC0SSVDM**
(SM-S921B, Android 16 / API 36). Media3 **1.11.1**. DND `zen_mode=1` throughout, the
value the phone was found at.

"Recorded narration" here means a read-along book — an EPUB carrying its own audio and
SMIL media overlays, played by `AudioController` → `MediaOverlayPlayer` →
`MediaPlaybackService`. The on-device voice (read-aloud/TTS) is a different path and
works; it is not the subject.

This document is step 1 of the run: what happens, where it stops, whose fault it is, and
which commit broke it. It was written and committed before any product code changed.

## 1. What happens, in order

Book: *Arthur Golden – Memoirs of a Geisha* (Read-along, downloaded, `3c98712e-…`),
chapter one. Times from `adb logcat -v threadtime` on 2026-10-10; the filtered copy is in
`manual-qa-evidence/2026-10-10/narration-playback/`.

| Time | What |
| --- | --- |
| 22:32:27.437 | Play pressed in the Listening sheet. `AndroidAudioController.startPlaybackFromCurrentPosition: controllerPosition=0, initialPositionMs=null`. |
| 22:32:27.439 | `playInternal: START chapterHref=Memoirs_of_a_geisha_a_novel_split_005.html`; `chapterWithAudio=` the same chapter; notification permission already granted. |
| 22:32:27.443 | `ForegroundServiceController.startService()` → `MediaPlaybackService.onCreate()`. `ExoPlayerImpl: Init c1a532 [AndroidXMedia3/1.11.1]`, `MediaSessionImpl: Init f24a88a`. The session is published with `actions=7339653` — the full command set from `804ac41e`. |
| 22:32:27.651 | `playInternal: service ready, player=…ExoPlayerImpl@c1a532`. |
| 22:32:27.653 | `playInternal: calling prepareChapter(…split_005.html)`, then `prepareChapter`, `playInternal: END`. |
| 22:32:27.666 | `SERVICE setChapterClips: count=308` — the chapter's 308 SMIL clips reach the service. |
| 22:32:27.669 | `preparePlaylist: audioHrefs=1, initialTrack=0, initialPos=0`; `exoPlayer=…@c1a532`; `calling setMediaSources with 1 sources`. |
| 22:32:27.673 | `preparePlaylist: calling player.prepare()`; `SERVICE onPlaybackStateChanged: state=BUFFERING, clipsCount=308`; `player.prepare() called, playbackState=2, mediaItemCount=1`. |
| 22:32:27.678 | `preparePlaylist: completed`; `SERVICE updateLocator: fragment=id138-sentence0` — the first clip's `PlayerMessage` fires. |
| 22:32:27.679 | `prepareChapter: prepareChapterAsync returned success=true`. **This is the last step that happens.** |
| 22:32:27.746 | `MediaFocusControl: requestAudioFocus() from uid/pid 10987/16015 AA=USAGE_MEDIA/CONTENT_TYPE_SPEECH … req=1` — granted. |
| **22:32:27.759** | `E ExoPlayerImplInternal: Playback error` — `ExoPlaybackException: Unexpected runtime error`, `Caused by: java.lang.IllegalStateException: Player callback method is called from a wrong thread.` **This is the first step that does not happen: the player never reaches `STATE_READY` and `onIsPlayingChanged` is never raised.** |
| 22:32:27.759 | `MediaFocusControl: abandonAudioFocus() from uid/pid 10987/16015`. Audio focus is given straight back. |
| 22:32:27.772 | `E čič123: SERVICE onPlayerError: ERROR_CODE_FAILED_RUNTIME_CHECK`. |
| 22:32:27.775 | `SERVICE onPlaybackStateChanged: state=IDLE, clipsCount=308`. |
| 22:32:27.800 | `MediaPlaybackService.onDestroy` via `ActivityThread.handleStopService`; `MediaSessionImpl: Release`, `ExoPlayerImpl: Release`. |
| 22:32:27.836 | A second `onCreate`/`onDestroy` pair follows and the service is gone. |
| — | The sheet keeps `0:00`, `−41:44 left in chapter`, button `Play`. Platform session state was `NONE(0)` throughout, never `PLAYING(3)`. |

The whole failure takes 320 ms. Nothing is shown to the user: the button simply does not
change.

The exact stack:

```
Caused by: java.lang.IllegalStateException: Player callback method is called from a wrong thread.
    at androidx.media3.session.MediaSessionImpl.verifyApplicationThread(MediaSessionImpl.java:1556)
    at androidx.media3.session.MediaSessionImpl.getConnectedControllers(MediaSessionImpl.java:401)
    at androidx.media3.session.MediaLibrarySessionImpl.getConnectedControllers(MediaLibrarySessionImpl.java:113)
    at androidx.media3.session.MediaSession.getConnectedControllers(MediaSession.java:1044)
    at com.retro99.reader.ui.playback.MediaPlaybackService.notifyClipChanged(MediaPlaybackService.kt:688)
    at com.retro99.reader.ui.playback.MediaPlaybackService.clipScheduler$lambda$0(MediaPlaybackService.kt:131)
    at com.retro99.reader.ui.playback.ClipScheduler.scheduleClipsForTrack$lambda$0$0(ClipScheduler.kt:65)
    at androidx.media3.exoplayer.ExoPlayerImplInternal.deliverMessage(ExoPlayerImplInternal.java:2265)
    …
    at androidx.media3.exoplayer.ExoPlayerImplInternal.updatePlaybackPositions(ExoPlayerImplInternal.java:1388)
    at androidx.media3.exoplayer.ExoPlayerImplInternal.doSomeWork(ExoPlayerImplInternal.java:1469)
```

## 2. The cause

`ClipScheduler` schedules one `PlayerMessage` per SMIL clip so the service can broadcast
`CLIP_CHANGED` for text highlighting (`ClipScheduler.kt:61-72`). `ExoPlayer.createMessage`
delivers on the **playback thread** unless `setLooper` is called, and `setLooper` is never
called. The message target calls back into
`MediaPlaybackService.notifyClipChanged` (`MediaPlaybackService.kt:131`), whose first act
is to read `session.connectedControllers` (`MediaPlaybackService.kt:688`).

In media3 **1.11.0** `MediaSessionImpl.getConnectedControllers()` gained a
`verifyApplicationThread()` guard as its first statement; it is absent in 1.9.2 and
1.10.0. (Checked in the sources jars in the Gradle cache:
`media3-session-1.9.2-sources.jar` → `MediaSessionImpl.java:373` has no guard,
`1.10.0` → `:360` none, `1.11.0` and `1.11.1` → `:400-401` `verifyApplicationThread();`.)

So the call now throws. It throws from inside `ExoPlayerImplInternal.doSomeWork`, so
ExoPlayer treats it as a playback error, which is fatal: the player goes to `IDLE`,
audio focus is released, and `MediaPlaybackService` is torn down.

Because the first clip of the chapter starts at 0 ms, its message fires on the first
`doSomeWork` after `prepare()`. There is no window in which audio can play: narration
dies before the first sample.

Read-aloud is untouched by this. It does not schedule clip messages — `scheduleClipsForTrack`
is only reached from `MediaOverlayPlayer.scheduleClipsForAllTracks`
(`MediaOverlayPlayer.kt:966`), on the media-overlay path alone — so its playback thread
never calls into the session.

## 3. Whose fault: the app

Three candidates were considered.

**The book — no.** The downloaded read-along EPUB is sound. `files/ebooks/3c98712e-…_readaloud.epub`
is 616 844 512 bytes, 122 entries, 38 `.smil` files and 29 `Audio/*.mp4` files. The first
audio file of the chapter extracts to a valid ISO base media file — boxes
`ftyp` (isom/iso2/mp41) at 0, `free` at 28, `mdat` at 36, `moov` at 33 737 650 — and the
same file plays through the same code path on the older build (§4). The 308 clips parse.

**The phone's setup — no.** Notification permission is granted, audio focus is requested
and granted, the session is published with the full command set, and the same device
plays the same book on the older build with the same data, the same account and the same
DND state.

**The app — yes.** `MediaPlaybackService.kt:688` calls a main-thread-only media3 API from
the ExoPlayer playback thread.

### The second downloaded read-along book is a separate, non-app problem

*The Rajah's Diamond* (`bfe48248-…_readaloud.epub`, 549 375 bytes, 18 entries) contains
**no `.smil` and no audio at all**; its `content.opf` declares
`<meta property="media:duration">00:00:00.00</meta>`. Opening it logs
`Analytics Event: readaloud_missing_media_overlays`, and the Listening sheet says
*"This book has no narration — reading with your device voice."* The app detects this and
says so, which is the right behaviour; the fault is in the book/server data for that
title. It is therefore not a case of narration failing to play, and it is not fixed here.

## 4. Which commit broke it, and how it was found

Narration **does** play on a build of `7ffeca90` (2026-09-27), the commit whose QA evidence
records working ReadAloud playback. Built in a temporary worktree, installed over the
existing data — the old build started cleanly and **no app data had to be cleared** — and
driven through the same steps:

```
10-10 22:45:44.926 D čič123: preparePlaylist: exoPlayer=…ExoPlayerImpl@865b0a
10-10 22:45:44.934 D čič123: preparePlaylist: calling player.prepare()
10-10 22:45:44.941 D čič123: SERVICE updateLocator: fragment=id138-sentence0
10-10 22:45:44.943 D čič123: prepareChapter: prepareChapterAsync returned success=true
10-10 22:45:45.816 D čič123: SERVICE onPlaybackStateChanged: state=READY, clipsCount=308
10-10 22:45:45.819 D čič123: SERVICE onIsPlayingChanged: isPlaying=true, clipsCount=308
```

`dumpsys media_session` then showed
`state=PlaybackState {state=PLAYING(3), position=29918, …, actions=7339979}`.
Note `SERVICE updateLocator` at 22:45:44.941 — the identical wrong-thread call, from the
identical place, with no exception. The temporary worktree was removed afterwards and this
branch's build reinstalled.

So the app regressed between `7ffeca90` and the current build. The offending commit is

**`e4379f01` — "Update Kotlin, Compose, and dependencies to current stable"** (2026-10-06),

which raises `media3 = "1.9.2"` to `media3 = "1.11.1"` in `gradle/libs.versions.toml:39`.
It was found by diffing the range rather than by `git bisect`: `ClipScheduler.kt` has not
changed since it was created in `0b939294` (2026-02-27) and
`MediaPlaybackService.notifyClipChanged` is byte-identical at `7ffeca90` and at
`804ac41e`, so no app-code commit in the range can account for the change in behaviour,
while the media3 bump accounts for it exactly and the version-by-version source check
above pins the guard to 1.11.0. A per-commit device bisect would have cost about twenty
20-minute builds to re-derive what the two jars state outright; the decisive evidence is
cited instead. `e4379f01` touches only `gradle/libs.versions.toml` and lock/version files,
none of `feature/reader`, `lib/epub`, the playback service or the download path, so the
commit filter the run was given would have skipped it.

The honest statement of the defect is therefore two-part:

* the latent fault is in the app and has been there since `0b939294` —
  `MediaPlaybackService.kt:688` is called from the playback thread
  (`ClipScheduler.kt:61-72` never sets a looper);
* `e4379f01` is what made it fatal, by moving to a media3 that enforces the contract the
  app was already breaking.

The fix belongs in the app: hop to the application thread before touching the session.

## 5. What is fixed in this run

The device pass is `manual-qa-evidence/2026-10-10/narration-playback/`.

| Fix | Commit | What it does |
| --- | --- | --- |
| Clip messages on the right thread | `ae738e4b` | `ClipScheduler` calls `setLooper(player.applicationLooper)` before `send()`, so the callback that reaches the session runs on the thread the session requires. Narration plays: `state=READY`, `isPlaying=true`, session `PLAYING(3)`. |
| The playlist outliving its player | `23392830`, `949ad7f0` | `playlistNeedsRebuild` — a pure function in the shape of `mediaButtonSpecs`, host tested — now also says "rebuild" when the cached playlist was built on a player that no longer exists, and `MediaOverlayPlayer` tracks (weakly) which player that was. Without it, handing listening from the device voice back to narration left `prepareChapterAsync` seeking a brand-new, empty player: `state=NONE(0)`, `0:00`, and no press on Play recovered it. |

Only the second fault had a host test. The first is thread affinity inside media3, which
needs a real `ExoPlayer` and a `Looper` to observe; this module's host tests are pure JVM
with hand-written fakes and the project has no Robolectric and no mocking library, so any
seam that could be tested would have pushed the defective line into untested code. It was
proven on the device instead, before and after, in `ae738e4b`'s message and NOTES A6/D1.

What the device pass established, on the same read-along book: play, pause, resume, both
ten second skips, a seek-bar drag and 1× → 1.5× → 1× all work; the media notification is
posted and the service is a real foreground service (`startForegroundCount=1`), with
pause and play working from the shade and the control present on the lock screen; three
minutes locked keeps playing (21:24 → 24:30) with no idle stop — the check `804ac41e`
could not do for narration; the highlight follows the audio across a page turn
(page 9 → 10); a chapter boundary is crossed with the service kept; narration and the
device voice can be swapped in both directions with only one playing; Stop listening
removes the notification; and read-aloud on an ordinary book still plays, pauses from the
notification, resumes on **one** press and survives a minute locked.

### What is not fixed

1. **Stop listening leaves the service and the session up, paused, until the reader is
   left.** The notification goes and the service leaves the foreground, so nothing is
   shown and nothing plays; `release()` tears the rest down on leaving the reader. This is
   narration's shape of what `tts-background-playback.md` "what was not fixed" §3 records
   for read-aloud. NOTES F1.
2. **The notification's duration is the audio file's, not the chapter's** — `20:13 / 59:18`
   in the shade against `24:53 / 41:44` in the sheet at the same moment, because the
   chapter's audio file is longer than the chapter. Cosmetic. NOTES F2.
3. **`<BOOK B>`, *The Rajah's Diamond*, is an incomplete read-along on the server** (§3).
   The owner has to rebuild or re-publish that title's read-along file so the EPUB carries
   its SMIL and audio, or stop offering it as a read-along. No app change: the app already
   detects it (`readaloud_missing_media_overlays`) and tells the user *"This book has no
   narration — reading with your device voice."* NOTES F3.
4. The one-page section that does not auto-advance, recorded in
   `tts-background-playback.md` and shared with this path, was out of this run's scope and
   was not looked at.
