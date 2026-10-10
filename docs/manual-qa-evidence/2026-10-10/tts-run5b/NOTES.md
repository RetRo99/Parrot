# Run 5b device pass — Samsung RFCWC0SSVDM — 2026-10-10

Cut short at the owner's request ("do minimal test"). Only the plain-start check was
attempted; the two-reader paths, the rotation count and the return through the now-playing
card were **NOT RUN**. The logout check was out of scope for this run by instruction (it
needs the owner's account). Supertonic terms were not accepted.

- **Device/build:** SM-S921B (Galaxy S24), serial `RFCWC0SSVDM`, One UI. `com.retro99.parrot`
  debug APK built from `9a08572d` at `androidApp/build/outputs/apk/debug/androidApp-debug.apk`,
  installed with `adb install -r`. PID 7820. A second device was attached
  (`192.168.1.248:5555`); every adb call in this run went through a wrapper pinned to
  `-s RFCWC0SSVDM`, and nothing was sent to it.
- **Phone left as required:** System voice, rate 1×, portrait, Library screen.
  `accelerometer_rotation` was 1 and `user_rotation` 0 before the pass and were never
  written, because the rotation check was not run.

## Checks

| # | Check | Result |
| --- | --- | --- |
| 1 | Plain start with a System voice: exactly one `attempted` and one `succeeded` per correlation id | **FAIL for a different reason than the one under test** — see below. No `tts_playback_operation` line of any kind was emitted. |
| 2 | Each two-reader path, then pause and play | NOT RUN |
| 3 | Rotation to landscape and back while reading | NOT RUN (rotation settings therefore untouched) |
| 4 | Leave the reader while it reads, return through the now-playing card | NOT RUN |

## Check 1, what was seen

Opened `Pride and Prejudice` from Continue reading. A tap in the middle of the page revealed
the control row; after the 0.9 s settle, a long press on Listen
(`input swipe 664 2113 664 2113 800`) opened the Listening sheet first try, showing
"This book has no narration — reading with your device voice.", `System voice`, `Rate 1×`
and a Play button — so read-aloud setup had run and claimed availability.

**Three separate taps on Play did nothing.** The button stayed `Play` (never `Pause`), no
audio started, and the app emitted no `tts_playback_operation` and no
`start_tts_playback` breadcrumb at all — not even `attempted`. The only app log line in the
window was an unrelated session summary, which confirms logging was working and the filter
was right:

```text
10-10 02:08:51.049  7820  7820 D čič : Analytics Event: reading_session_summary |
  Parameters: {reading_duration_ms=30012, audiobook_duration_ms=0,
  readaloud_duration_ms=0, tts_duration_ms=0, foreground_duration_ms=30012,
  background_duration_ms=0, end_reason=checkpoint, content_access=on_device}
```

No `AndroidRuntime` fatal, no exception and no Koin `NoDefinitionFoundException` appeared.
The reader reported `Page 1 of 1` for this book, which is not what a 36-page EPUB should
show and suggests the publication or the chapter did not load as expected.

**Attribution not established.** This was not checked against a build from before run 5b, so
whether it predates this run is unknown. What is known: run 5b's changes do not touch the
path between the play press and `requestPlayback` (the reporter only maps an operation
*after* the controller emits one, so an `attempted` would still have been emitted and
logged; the lease only changes who closes controllers, and a failure there would have
thrown; the voice-preparation change returns immediately for a non-neural voice like the
System voice). A silent play press is also exactly what decision 2 / TTS-F23 in run 5a was
meant to have ruled out ("A play press never does nothing silently").

This needs its own investigation with a before/after build. Carried to run 5c.

One lead for whoever picks it up: the phone's library holds a 1515-byte EPUB alongside the
real books, which is small enough to have no readable text at all. `Page 1 of 1` would fit a
book like that, and TTS-F14's "a chapter with no readable sentences" is next door. Whether
the entry opened from Continue reading was that file was not established.

`logcat-app-process.txt` holds the app-process log for the window, book titles and author
names replaced. The three multi-kilobyte `Repo local books result` library dumps are left
out: they carry no TTS information and are almost entirely book metadata.

## Not evidence of

Nothing here confirms or denies QA-BUG-0100 on the device: no start was reached, so no
correlation id could be counted. The host tests cover it
(`ReaderTtsOperationReporterTest`, `ReaderScopeWiringTest`).
