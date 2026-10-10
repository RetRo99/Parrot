# Prepared chapters: device check (step 8)

Device: Samsung SM-S921B, serial `RFCWC0SSVDM`, the only device any command was sent to
(a Xiaomi was also attached and never addressed). Installed with `adb install -r`; nothing
was uninstalled, no app data cleared, no account, network or ringer change, and the
Supertonic terms were **not** accepted. The test-only probe app from an earlier run was
neither used nor reinstalled. Build: this branch's `androidApp:assembleDebug`.

Book: *Alice's Adventures in Wonderland* (Project Gutenberg, public domain) for the Kokoro
and System-voice checks, plus *Pride and Prejudice* (Gutenberg) for the cancel and
partly-prepared checks. Book and chapter titles are removed from the logcat, and the
screenshots are cropped to the row.

A note on chapter length: both Gutenberg EPUBs put many printed chapters in one spine
item, and read-aloud prepares the loaded spine item. *Pride and Prejudice* is one item of
397 sentences; Alice's chapters are 97 to 120. The short chapter used for the full journey
is Alice's front matter item (title page and contents, 39 sentences), which is under the
brief's 60.

## Checks

1. **Kokoro "Heart", prepare a chapter.** 39 sentences. Row went
   "1 of 39 sentences" → "Ready, plays instantly · 654 kB" in about 85 seconds
   (≈2.2 s/sentence; these are short front-matter lines). The notification read
   "Preparing chapter audio / N of 39 sentences" with a Cancel action and no book or
   chapter title. Leaving the reader to the library kept it running and the notification
   kept counting up (observed on the 397-sentence run: "9 of 397" on the library screen,
   "20 of 397" in the shade). On disk after this chapter plus a 15-sentence partial:
   1,270 kB (`run-as … du -s files/tts-prepared`); after the 97-sentence chapter as well,
   4,878 kB, which matches the rows' 654 kB + 3.5 MB.
   Notification Cancel: tapped in the shade during the 397-sentence run; the service
   stopped, the notification went away, and the partial chapter was kept (26 then 28
   `.m4a` files, "28 of 397 sentences prepared" in the row) — it stopped after the
   sentence in flight, as designed.

2. **Play the prepared chapter.** Zero `Kokoro synthesize start` lines for it
   (the same marker appears 59 times in the preparation capture, so its absence is
   meaningful). Pause → Play came back as Pause immediately; skip forward and eight skip
   backs moved the sentence counter with no synthesis; playback ran through to the end of
   the chapter and only then synthesized, because narration carried on into the next,
   unprepared chapter. **Double-tap was tried but landed on that following unprepared
   chapter**, so "double-tap a later sentence inside the prepared chapter" is not verified
   here; the other three were.

3. **Live playback while another chapter prepares.** While the 97-sentence chapter was
   preparing (55 of 97), Play was pressed on an unprepared 120-sentence chapter. From the
   log: the preparation sentence in flight finished at 10:13:04.468, the first live
   synthesis started at 10:13:07.751 and finished in 3,984 ms, so the first audio came
   within one sentence's generation time of the press. Narration kept going (sentence 3 of
   120) and preparation continued to 70 of 97 in the meantime.

4. **Force-stop and Continue.** `am force-stop` at "70 of 97". Reopening the book showed
   "71 of 97 sentences prepared" with Continue and Delete (the sentence in flight had been
   added). Continue ran to "Ready, plays instantly · 3.5 MB" with exactly **25**
   `Kokoro synthesize start` lines — the 26 missing sentences, one of which came from the
   ordinary sentence cache — and none for the 71 already on disk.

5. **Speed change.** At 1.1× the row read "Made for Heart (US female) at 1×" and offered
   Prepare again. Back at 1× it read "28 of 397 sentences prepared" again, and the audio
   was never touched.

6. **Delete.** Delete… on the row asked "Delete prepared audio?"; confirming left zero
   files under `files/tts-prepared` and the row back to "Preparing takes a while…".
   Settings → Reader settings → Read aloud showed "PREPARED AUDIO / 4.6 MB on this device /
   Delete all…"; confirming "Delete all prepared audio?" left "Nothing prepared yet" and an
   empty folder.

7. **System voice.** With System voice selected, the same 39-sentence chapter showed
   "Made for Heart (US female) at 1×" → Prepare again → "Ready, plays instantly · 660 kB"
   in about 47 seconds, and played instantly (sentence 17 → 23 of 39 in eight seconds) with
   no encoder and no neural synthesis in the log. The preparation capture contains 39
   MediaCodec/MPEG4Writer sessions, one per sentence: the production encoder really encodes
   rather than copying the WAV.

8. **Screenshots** (cropped to the row, no book title): `row-not-prepared.png`,
   `row-preparing.png`, `row-preparing-another.png`, `row-partly-prepared.png`,
   `row-other-settings.png`, `row-ready.png`, `row-delete-confirmation.png`,
   `notification-progress.png`, `settings-prepared-audio.png`,
   `settings-delete-all-confirmation.png`.
   States **not reached on the phone**: "failed" (nothing failed in any run) and "voice not
   usable" (it would have meant deleting the 149 MB Kokoro pack or accepting the Supertonic
   terms, which the device rules forbid). Both are covered by host tests.

## Files for listening

`listen/sentence-01.m4a`, `listen/sentence-02.m4a`, `listen/sentence-03.m4a` are the first
three prepared sentences, in order, of Alice's Chapter I ("Down the Rabbit-Hole") — Project
Gutenberg, public domain — copied with `run-as` straight out of the chapter folder, so they
are exactly what the app plays. Sentence 1 is the chapter heading (1,066 ms, 7,290 bytes),
sentence 2 is the long opening sentence beginning "Alice was beginning to get very tired of
sitting by her sister on the bank…" (10,922 ms, 67,350 bytes) and sentence 3 follows it
(4,224 ms, 26,530 bytes); the byte sizes are the manifest's. Kokoro "Heart"
(`kokoro:0`, model `20260928123911-4`) at rate 1.0 and pitch 1.0. Play them back to back to
judge the joins: that is the one thing in this feature no agent has been able to assess.
No audio from any other book was copied.

## Phone left as

System voice, rate 1×, library screen, `files/tts-prepared` empty (checked with `run-as`).
A Samsung system-update dialog appeared once mid-run and was dismissed with the up
navigation; no update was installed and no setting was changed.
