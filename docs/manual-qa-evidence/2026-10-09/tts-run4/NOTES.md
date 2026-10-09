# Run 4 device pass — voice pack downloads

Date: 2026-10-10, 00:07–00:32 local (run 4's work was done on 2026-10-09).
Build: branch `tts/investigation`, `c23c882b`, debug APK.

**Device: the Samsung SM-S921B, serial RFCWC0SSVDM, on the owner's instruction**
("test on samsung"), not the emulator. The run brief had reserved these checks for the
emulator AVD `Medium_Phone_API_37.0` because they switch the network off and on; the
emulator was stuck in a boot loop (`system_server` never brought up the activity
service, "Waited one second for activity"), a reboot and a cold boot did not clear it,
and the owner then asked for the phone. Wi-Fi and mobile data were switched off and on
with `svc`, and both were left **on**; the Kokoro pack the phone had before the run was
re-downloaded at the end, so the phone is as it was found. The third attached device
(192.168.1.248:5555) got no command.

Timings marked "≤" are upper bounds: they are measured by polling `uiautomator dump`
from the host, and one poll costs about a second.

| # | Check | Result |
| --- | --- | --- |
| 1 | Book from the in-app catalogue | PASS. Get books → Project Gutenberg → Popular → "Pride and Prejudice", EPUB 0.6 MB, in the library and opened. |
| 2 | Voices with the network on | PASS. ≤3.03 s from the tap on Change to the list, with both pack sizes: Kokoro "11 voices · 149 MB · English", Supertonic 3 "10 voices · 145 MB · English". |
| 3 | Kokoro download interrupted by the network going off | PASS, see below. |
| 4 | Retry resumes rather than starting from zero | PASS, see below. |
| 5 | Kokoro voice preview | PASS. `SherpaOnnxTts: Kokoro loaded: sampleRate=24000 speakers=11`, `Kokoro synthesize done in 10318ms, samples=322772, file=645588 bytes` for "Heart"; a second voice ("Bella") synthesised too. |
| 6 | Voices with the network off, after a force-stop | PASS. ≤3.07 s to the list, Kokoro "✓ Downloaded · works offline", sizes still shown from the cached manifest. |
| 7 | Delete the pack from its card | PASS, twice. `files/tts-models/` left holding `manifest.json` only; the card went back to "Download · 149 MB". |

Check order was 2, 5, 7, 3, 4, 6, 7 again: the pack was already installed when the run
started, so the preview and the first delete were done before the download checks.

## Check 3, the interrupted download

This connection downloads the pack in about 8 seconds (143 of 149 MB in the first 8 s),
which is why run 3b could not induce this at all. The network had to be cut 3.65 s after
the tap on Download to land inside the transfer.

- A first attempt, cutting at about 45%, missed: the poll that was meant to catch 45%
  already read 96%. That attempt installed, and the pack was deleted again.
- Second attempt, `svc wifi disable` + `svc data disable` at tap+3.65 s:
  - the card showed **"Download stopped — check your connection. Retry continues from
    where it left off."** with **Retry** and **Cancel**;
  - on disk, `files/tts-models/kokoro/20260928123911-4/` held `LICENSE` (11,358),
    `README.md` (135), `espeak-ng-data/` (extracted), `model.int8.onnx`
    (134,186,977 — complete), and **no** `voices.bin` and **no** `tokens.txt`;
  - there was no `.part` file left at that moment: the cut landed between files, after
    the big one had been verified and renamed into place. An earlier poll during the
    first attempt did catch one mid-transfer: `espeak-ng-data.zip.part`, 5,840,453 bytes;
  - no `.active` marker, so the pack correctly read as not installed.

## Check 4, the resume

Network on, then Retry. The log line that settles it — only the two missing files, not
149 MB from the start:

```
00:28:52.972 I TtsModelManager: Downloading kokoro 20260928123911-4 (2 files, 5756982 bytes)
00:28:53.238 I TtsModelManager: Prepared tokens.txt (1078 bytes) in 259ms
00:28:53.738 I TtsModelManager: Prepared voices.bin (5755904 bytes) in 500ms
00:28:53.743 D Analytics Event: tts_model_prepared | Parameters: {is_success=true, duration_ms=1485}
00:28:55.868 I SherpaOnnxTts: Kokoro loaded: sampleRate=24000 speakers=11
```

Afterwards: `.active` present, `du -sh` 152M, card "✓ Downloaded · works offline".
For contrast, the restore download at the very end of the pass, with nothing on disk,
reports all six files: `Downloading kokoro 20260928123911-4 (6 files, 148969454 bytes)`
(see `tts-logcat-app-process.txt`, 00:31:10).

## Recorded, not investigated

- **A book opened on its title page offers no voices at all.** Opening "Pride and
  Prejudice" fresh at page 1 (the title page) and going to Listening → Change showed the
  Listening sheet saying "This book has no narration — reading with your device voice",
  "Sentence 1 of 0", and a Voices sheet whose NATURAL VOICES section was **empty** — no
  Kokoro card, no Supertonic card, only a synthetic "System voice" — even though the
  Kokoro pack was installed and 152 MB were on disk. Paging forward to a text page did
  not recover it; only reopening the book from the library's Resume did. `initTts`
  returns early when `ttsController.hasReadableContent()` is false
  (`ReaderViewModel.kt:1086-1088`), leaving `ttsVoices` empty for the life of that
  screen. This is TTS-F23's area and belongs to run 5a. It cost this pass an hour: the
  empty list first looked like a regression from run 4, and was only cleared by building
  and installing the run's baseline (`8db361f1`), the seam commit (`64c0ac69`) and the
  F04 commit (`0f9732a9`) one after another on the phone — all four builds, baseline
  included, show the pack card when the book is opened through Resume, and the branch tip
  shows it in ≤3.03 s.
- With the network switched off the TCP connect fails at once, so the offline check does
  not exercise TTS-F04's five second limit; and the manifest cache was under a day old,
  so no fetch was attempted at all. The limit itself is covered by
  `TtsModelStoreTest`'s silent-host case (5030 ms against a host that accepts and never
  answers, 60010 ms before the fix).
- The Supertonic terms were not accepted, as instructed; that pack was never downloaded.

Evidence: `tts-logcat-app-process.txt` — the app process only, framework render spam
dropped, book titles replaced with `<book title removed>`.
