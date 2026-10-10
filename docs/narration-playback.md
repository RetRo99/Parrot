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
