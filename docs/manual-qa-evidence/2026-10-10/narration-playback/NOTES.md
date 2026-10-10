# Recorded narration on the device — 2026-10-10

Device: Samsung **RFCWC0SSVDM** (SM-S921B, Android 16 / API 36). Media3 1.11.1.
DND `zen_mode=1` throughout, the value the phone was found at. No command was sent to
any other device.

Books are redacted here and in the logs:

* `<BOOK A>` — the read-along book with real audio (38 SMIL files, 29 `Audio/*.mp4`),
  downloaded. Narration is the subject of this run.
* `<BOOK B>` — the other downloaded "read-along", whose EPUB has no SMIL and no audio.
* `<BOOK C>` — an ordinary eBook, for the read-aloud regression check.

Builds:

* **B0** — `804ac41e` (this branch's start), the fault as found.
* **B1** — `7ffeca90` (2026-09-27), built in a temporary worktree, installed over the
  existing data. **No app data was cleared**; the old build opened the newer database and
  the library fine. The worktree was removed afterwards.
* **B2** — B0 + `ae738e4b` (clip messages on the application thread).
* **B3** — B2 + `949ad7f0` (rebuild the playlist when the player has been replaced).

Logs: `logcat-01-repro-fault.txt` (B0), `logcat-02-build-7ffeca90-plays.txt` (B1),
`logcat-03-after-clip-thread-fix.txt` (B2), `logcat-04-after-playlist-fix-and-readaloud.txt`
(B3). Filtered to the app's own tags and the media/audio system lines, book titles and
in-book hrefs replaced as above. Repeated position-forwarding and session-state lines were
dropped; the state readings below are from `dumpsys media_session` at the times given.

## Part A — Step 1: the fault on B0

A1  22:32:27.437 Play pressed in the Listening sheet for `<BOOK A>`, chapter one.
    `startPlaybackFromCurrentPosition: controllerPosition=0, initialPositionMs=null`.
A2  22:32:27.439–.651 `playInternal: START` → service created → `ExoPlayerImpl: Init
    c1a532 [AndroidXMedia3/1.11.1]` → `playInternal: service ready`. The session is
    published with `actions=7339653`, the full command set from `804ac41e`.
A3  22:32:27.666–.673 `SERVICE setChapterClips: count=308`, `preparePlaylist:
    audioHrefs=1`, `setMediaSources with 1 sources`, `player.prepare() called,
    playbackState=2, mediaItemCount=1`, `SERVICE onPlaybackStateChanged: state=BUFFERING`.
A4  22:32:27.678–.679 `SERVICE updateLocator: fragment=id138-sentence0` — the first clip's
    `PlayerMessage` fires — then `prepareChapter: prepareChapterAsync returned
    success=true`. **This is the last step that happens.**
A5  22:32:27.746 `MediaFocusControl: requestAudioFocus() … AA=USAGE_MEDIA/
    CONTENT_TYPE_SPEECH req=1` — granted.
A6  22:32:27.759 **FIRST STEP THAT DOES NOT HAPPEN: the player never reaches READY.**
    `E ExoPlayerImplInternal: Playback error` / `ExoPlaybackException: Unexpected runtime
    error` / `Caused by: java.lang.IllegalStateException: Player callback method is called
    from a wrong thread.` at `MediaSessionImpl.verifyApplicationThread
    (MediaSessionImpl.java:1556)` ← `MediaSessionImpl.getConnectedControllers(:401)` ←
    `MediaPlaybackService.notifyClipChanged(MediaPlaybackService.kt:688)` ←
    `MediaPlaybackService.clipScheduler$lambda$0(MediaPlaybackService.kt:131)` ←
    `ClipScheduler.scheduleClipsForTrack$lambda$0$0(ClipScheduler.kt:65)` ←
    `ExoPlayerImplInternal.deliverMessage(:2265)` ← `…updatePlaybackPositions(:1388)` ←
    `…doSomeWork(:1469)`.
A7  22:32:27.759 `abandonAudioFocus()`; 22:32:27.772 `SERVICE onPlayerError:
    ERROR_CODE_FAILED_RUNTIME_CHECK`; 22:32:27.775 `state=IDLE`; 22:32:27.800
    `onDestroy()` via `ActivityThread.handleStopService`, `MediaSessionImpl: Release`,
    `ExoPlayerImpl: Release`. Whole failure: **320 ms**.
A8  Visible result: sheet stays `0:00`, `−41:44 left in chapter`, button `Play`. Platform
    session state `NONE(0)` throughout; it is never `PLAYING(3)`. Nothing is shown to the
    user.

## Part B — Step 1: is it the book, the setup, or the app

B1  THE BOOK IS SOUND. `<BOOK A>`'s downloaded EPUB: 616 844 512 bytes, 122 entries,
    38 `.smil`, 29 `Audio/*.mp4`. The chapter's audio file extracts to a valid ISO base
    media file — boxes `ftyp` (isom/iso2/mp41) at 0, `free` at 28, `mdat` at 36,
    `moov` at 33 737 650. Checked by pulling the file with
    `adb exec-out run-as com.retro99.parrot cat …`, then `unzip -l`/`unzip -v` and a
    box walk. 308 clips parse. The same file plays on B1 (C1 below).
B2  THE SETUP IS SOUND. Notification permission granted, audio focus requested and
    granted (A5), session published with the full command set (A2), and the same phone
    plays the same book on B1 with the same data, account and DND state.
B3  `<BOOK B>` IS A DIFFERENT, NON-APP PROBLEM. Its downloaded EPUB is 549 375 bytes,
    18 entries, **no `.smil` and no audio**, `content.opf` declaring
    `<meta property="media:duration">00:00:00.00</meta>`. 22:36:50.774 opening it logs
    `Analytics Event: readaloud_missing_media_overlays`, and the Listening sheet says
    *"This book has no narration — reading with your device voice."* The app detects it
    and says so. Owner action, not an app fix — see `docs/narration-playback.md` §3.
C1  **NARRATION PLAYS ON B1 (`7ffeca90`).** 22:45:44.926 `preparePlaylist:
    exoPlayer=…ExoPlayerImpl@865b0a` → `player.prepare()` → 22:45:44.941 `SERVICE
    updateLocator: fragment=id138-sentence0` (the identical wrong-thread call, from the
    identical place, with **no** exception) → 22:45:45.816 `state=READY` → 22:45:45.819
    `onIsPlayingChanged: isPlaying=true`. `dumpsys media_session`:
    `state=PLAYING(3), position=29918, actions=7339979`. Paused from the app at 22:46 and
    the build was replaced.
C2  So the app regressed between `7ffeca90` and `804ac41e`. `ClipScheduler.kt` has not
    changed since `0b939294` (2026-02-27) and `notifyClipChanged` is byte-identical at
    both ends of the range, so no app-code commit explains it; `e4379f01` (2026-10-06)
    raises `media3 1.9.2 → 1.11.1`, and `verifyApplicationThread()` was added to
    `MediaSessionImpl.getConnectedControllers()` in **1.11.0** — absent in the cached
    `1.9.2` (`:373`) and `1.10.0` (`:360`) sources jars, present in `1.11.0`/`1.11.1`
    (`:400-401`). No per-commit bisect was run; see `docs/narration-playback.md` §4 for
    why, and note `e4379f01` touches none of the paths the run's commit filter allowed.

## Part C — Step 3: narration on B2/B3

D1  PLAY — B2, 22:54:47.845 `preparePlaylist … mediaItemCount=1`, 22:54:48.598
    `state=READY`, 22:54:48.602 `isPlaying=true`; session `state=PLAYING(3),
    position=63303`. No `wrong thread` line anywhere in the B2 log (`grep -c` = 0).
D2  PAUSE — 22:55:40, `PLAYING(3) 96289` → `PAUSED(2) 96881`, and still `96881` six
    seconds later.
D3  RESUME — 22:55:46, `PAUSED(2) 96881` → `PLAYING(3) 102769`.
D4  SKIP BACK 10 s — 22:56:03, `111901` → `103393`. SKIP FORWARD 10 s — 22:56:06,
    `103393` → `116313`, and a second press → `128373`. (Each with ~1.5–2 s of real time
    on top.)
D5  SEEK BAR DRAGGED — 22:56:49, `167363` → `1159551` (≈19:19, about 46 % of 41:44),
    still `PLAYING(3)`, advancing to `1162483`.
D6  SPEED — 22:57:09, `speed=1.0` → `speed=1.5` → back to `speed=1.0`, playing throughout.
D7  NOTIFICATION — id=1001, `category=transport`, `groupKey=media3_group_key`,
    `actions=5`, `flags=…|FOREGROUND_SERVICE`, and `MediaPlaybackService … isForeground=true
    foregroundId=1001 startForegroundCount=1`. In the shade: previous chapter, back 10,
    **pause**, forward 10, next chapter, with cover art, chapter and book title.
    **This is the narration check `804ac41e` could not do.**
D8  PAUSE AND PLAY FROM THE NOTIFICATION — 22:58:12, `PLAYING(3) 1239958` →
    `PAUSED(2) 1240251` → `PLAYING(3) 1243172`.
D9  LOCK SCREEN — the media control is on the lock screen with back 10, pause, forward 10
    and cover art; `PLAYING(3) 1264229` while locked.
D10 THREE MINUTES LOCKED, STILL PLAYING — locked 22:58:40.
    `22:59:01 PLAYING(3) 1284080` → `23:00:01 1344208` → `23:01:01 1404395` →
    `23:02:07 PLAYING(3) 1470600`. That is **21:24 → 24:30**. Service still
    `isForeground=true`; no `Stopping service due to app idle` line in the log at all.
D11 HIGHLIGHT FOLLOWS ACROSS A PAGE TURN — locators advance one sentence at a time
    (`id138-sentence114` 23:02:09 … `sentence118` 23:02:26, `sentence154-157`
    23:04:47–53). Page `9 of 20` at 23:03:18 → `10 of 20` at 23:03:39 with narration
    unbroken, and the highlight is on the new page (the sentence being read is highlighted
    in mid-page, bar reading `Narration · 26:21 / 41:44`).
D12 CHAPTER BOUNDARY — seeked to `2498708` (41:38 of 41:44) at 23:05:20; 23:05:31.680
    `preparePlaylist: audioHrefs=2`, 23:05:31.921 `playInternal: START
    chapterHref=<BOOK A CHAPTER 6>.html`, 23:05:31.965 `state=READY, clipsCount=342`,
    and the session position restarts and advances (`9058` → `57106` over the next
    minute). The sheet reads `chapter two`. The player instance is unchanged
    (`@38ee471`), so the service was kept through the handover.
D13 NARRATION → DEVICE VOICE — 23:07:48. `onIsPlayingChanged: isPlaying=false,
    clipsCount=342` then `TtsRouter: synthesize voice=null engine=SYSTEM`,
    `state=READY, clipsCount=0`, `isPlaying=true`. Only one plays at a time.
D14 DEVICE VOICE → NARRATION — **FAILED ON B2.** 23:08:41 TTS stopped, `playInternal:
    START … fragmentId=parrot-sentence-84`, then `onDestroy()` at .849 and `onCreate()`
    at .891 — the service is replaced mid-handover — and `prepareChapterAsync returned
    success=true` at .957 without any `preparePlaylist` line. Session
    `state=NONE(0), position=0`. A press on Play at 23:09:52 did the same thing again, so
    the state was unrecoverable. Cause and fix: `949ad7f0`; the failing test committed
    first is `23392830`.
D15 DEVICE VOICE → NARRATION — **PASSES ON B3.** 23:18:49.215 `preparePlaylist:
    exoPlayer=…ExoPlayerImpl@5645f77`, `setMediaSources with 2 sources`,
    `player.prepare() called, playbackState=2, mediaItemCount=2`, 23:18:49.391
    `state=READY, clipsCount=342`, 23:18:49.394 `isPlaying=true`; session
    `PLAYING(3) 9138` → `17993`. The round trip narration → device voice → narration now
    works in both directions, one at a time (D13 re-checked at 23:18:24).
D16 STOP LISTENING — 23:20:47, `onIsPlayingChanged: isPlaying=false`. The media
    notification is gone (`dumpsys notification` has **no** record for the package) and
    the service is no longer foreground. The `ServiceRecord` and the media session do
    **remain**, paused, until the reader is left: 23:22:03.340 `release: stopping service
    (playback not active)`, 23:22:03.356 `onDestroy()`, after which both are gone
    (service records 0, sessions 0). See E1.

## Part D — Step 3: read-aloud regression check on `<BOOK C>` (B3)

E0  The sheet for `<BOOK C>` says *"This book has no narration — reading with your device
    voice."*, as it should for an ordinary eBook.
E1  PLAY — 23:23:22.764 `onIsPlayingChanged: isPlaying=true, clipsCount=0`,
    `TtsRouter: synthesize voice=null engine=SYSTEM`; session `PLAYING(3) 510407`.
    Notification posted: id=1001, `category=transport`, `actions=3`.
E2  PAUSE FROM THE NOTIFICATION — 23:24:06, `PLAYING(3) 542816` → `PAUSED(2) 543572`.
E3  ONE PRESS ON PLAY IN THE APP — 23:24:27, button showed `Play`, one press →
    `PLAYING(3) 542570`. One press, as `522747ed` intended.
E4  ONE MINUTE LOCKED — locked 23:24:48; `23:25:54 PLAYING(3) 609414`. No idle stop.
E5  Read-aloud is unaffected by both fixes, as expected: `ClipScheduler` is only used on
    the media-overlay path (`MediaOverlayPlayer.scheduleClipsForAllTracks`) and
    `playlistNeedsRebuild` only by `MediaOverlayPlayer`.

## Findings recorded and not fixed

F1  **Stop listening leaves the service and the session up, paused, until the reader is
    left** (D16). The notification does go and the service leaves the foreground, so
    nothing is shown to the user and nothing is playing; `release()` tears the rest down
    on leaving the reader. Narration's shape of the thing `docs/tts-background-playback.md`
    "what was not fixed" §3 already records for read-aloud. Not touched.
F2  **The notification's duration is the audio file's, not the chapter's.** At D7 the
    shade read `20:13 / 59:18` while the sheet read `24:53 / 41:44` for the same moment:
    the chapter's audio file is longer than the chapter. Cosmetic; not touched.
F3  `<BOOK B>` is an incomplete read-along on the server (B3 above). The app already tells
    the user. Owner action.

## State the phone was left in

Library screen, 21 books, the same three downloads as at the start (`<BOOK A>`
read-along, `<BOOK B>` read-along, `<BOOK C>` eBook), same Storyteller account signed in,
nothing playing, no Parrot notification and no Parrot media session, device voice left on
**System voice, rate 1×, pitch Normal**. B3 (this branch's build) is installed.
No app data was cleared at any point in the run.
