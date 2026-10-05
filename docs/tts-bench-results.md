# TTS bench results (Phase 0)

Device: Onyx Boox Nova Air (arm64-v8a, 8 cores), not charging, thermal status 0 throughout.
Tool: `:tools:tts-bench`, debug build, run 2026-09-30. Median of 3 runs after one warm-up
pass. r = generation seconds per second of audio (lower is faster; playback needs r x speed <= 1).

Model load: Supertonic 2.5 s, warm-up 4.7-4.8 s. Kokoro (4 threads) 4.5 s load, 4.9 s warm-up.
Sample rate: Supertonic **44100 Hz**, Kokoro 24000 Hz.

| model | threads | steps | passage | gen s | audio s | r |
|---|---|---|---|---|---|---|
| supertonic | 2 | 8 | short | 12.5 | 4.6 | 2.69 |
| supertonic | 2 | 8 | medium | 27.3 | 11.1 | 2.47 |
| supertonic | 2 | 8 | long | 42.0 | 17.2 | 2.45 |
| supertonic | 2 | 6 | short | 9.7 | 4.6 | 2.08 |
| supertonic | 2 | 6 | medium | 21.2 | 11.1 | 1.91 |
| supertonic | 2 | 6 | long | 32.6 | 17.2 | 1.90 |
| supertonic | 2 | 4 | short | 6.8 | 4.6 | 1.47 |
| supertonic | 2 | 4 | medium | 15.0 | 11.1 | 1.36 |
| supertonic | 2 | 4 | long | 23.2 | 17.2 | 1.35 |
| supertonic | 4 | 8 | short | 12.0 | 4.6 | 2.58 |
| supertonic | 4 | 8 | medium | 25.4 | 11.1 | 2.29 |
| supertonic | 4 | 8 | long | 38.6 | 17.2 | 2.25 |
| supertonic | 4 | 6 | short | 9.3 | 4.6 | 2.01 |
| supertonic | 4 | 6 | medium | 19.5 | 11.1 | 1.76 |
| supertonic | 4 | 6 | long | 29.5 | 17.2 | 1.72 |
| supertonic | 4 | 4 | short | 6.4 | 4.6 | 1.38 |
| supertonic | 4 | 4 | medium | 13.6 | 11.1 | 1.23 |
| supertonic | 4 | 4 | long | 20.5 | 17.2 | 1.20 |
| supertonic | 8 | 8 | short | 12.2 | 4.6 | 2.63 |
| supertonic | 8 | 8 | medium | 27.4 | 11.1 | 2.48 |
| supertonic | 8 | 8 | long | 45.1 | 17.2 | 2.63 |
| supertonic | 8 | 6 | short | 11.1 | 4.6 | 2.38 |
| supertonic | 8 | 6 | medium | 20.6 | 11.1 | 1.86 |
| supertonic | 8 | 6 | long | 33.6 | 17.2 | 1.96 |
| supertonic | 8 | 4 | short | 6.4 | 4.6 | 1.38 |
| supertonic | 8 | 4 | medium | 14.6 | 11.1 | 1.32 |
| supertonic | 8 | 4 | long | 23.2 | 17.2 | 1.35 |
| kokoro | 4 | 0 | short | 20.5 | 3.8 | 5.35 |
| kokoro | 4 | 0 | medium | 44.3 | 8.9 | 5.00 |
| kokoro | 4 | 0 | long | 67.2 | 13.8 | 4.89 |

## Findings

- The Nova Air is slow at every setting: full-quality Supertonic (8 steps) has r of about
  2.3-2.6, so 10 min of chapter needs about 6 min of audio ready in advance.
- Steps scale generation time nearly proportionally: 8 -> 6 is about 20-25 % faster, 8 -> 4
  about 45 % faster. 4 steps at 4 threads reaches r of about 1.2, still above 1.0.
- Threads: 2 -> 4 gives only about 7-10 %. 8 threads is no better than 4 and sometimes worse.
  Keep the 2-4 cap.
- Kokoro is about twice as slow as Supertonic (r of about 5), so it cannot be the fast fallback.
- Storage: at 44.1 kHz 16-bit mono, WAV is about 5.3 MB per minute, so the 128 MB cache holds
  about 24 minutes, as the design assumed.
- Not yet done: a mid-range phone, and the two listening checks (steps, time-stretching), which
  need a person. Use the app's "Listening checks" section.

Raw per-run numbers are in the CSVs the app writes to its external files folder.

## Xiaomi 2602BPC18G (MediaTek MT6991)

Device line from the app: `Xiaomi 2602BPC18G, SoC=Mediatek MT6991, cores=8, dotprod=true`.
Same tool, debug build, run 2026-09-30, median of 3 runs after one warm-up pass, thermal status 0
throughout. Unlike the Nova Air run, this phone was **charging** during the run.

| model | threads | steps | passage | gen s | audio s | r |
|---|---|---|---|---|---|---|
| supertonic | 2 | 8 | short | 2.8 | 4.6 | 0.60 |
| supertonic | 2 | 8 | medium | 6.6 | 11.1 | 0.60 |
| supertonic | 2 | 8 | long | 10.2 | 17.2 | 0.59 |
| supertonic | 2 | 6 | short | 2.3 | 4.6 | 0.50 |
| supertonic | 2 | 6 | medium | 5.1 | 11.1 | 0.46 |
| supertonic | 2 | 6 | long | 7.8 | 17.2 | 0.45 |
| supertonic | 2 | 4 | short | 1.6 | 4.6 | 0.35 |
| supertonic | 2 | 4 | medium | 3.5 | 11.1 | 0.32 |
| supertonic | 2 | 4 | long | 5.4 | 17.2 | 0.32 |
| supertonic | 4 | 8 | short | 2.9 | 4.6 | 0.63 |
| supertonic | 4 | 8 | medium | 6.9 | 11.1 | 0.62 |
| supertonic | 4 | 8 | long | 10.5 | 17.2 | 0.61 |
| supertonic | 4 | 6 | short | 2.4 | 4.6 | 0.52 |
| supertonic | 4 | 6 | medium | 5.2 | 11.1 | 0.47 |
| supertonic | 4 | 6 | long | 8.0 | 17.2 | 0.47 |
| supertonic | 4 | 4 | short | 2.0 | 4.6 | 0.43 |
| supertonic | 4 | 4 | medium | 4.1 | 11.1 | 0.37 |
| supertonic | 4 | 4 | long | 6.6 | 17.2 | 0.38 |
| supertonic | 8 | 8 | short | 4.1 | 4.6 | 0.88 |
| supertonic | 8 | 8 | medium | 8.3 | 11.1 | 0.75 |
| supertonic | 8 | 8 | long | 12.6 | 17.2 | 0.74 |
| supertonic | 8 | 6 | short | 3.5 | 4.6 | 0.75 |
| supertonic | 8 | 6 | medium | 6.3 | 11.1 | 0.57 |
| supertonic | 8 | 6 | long | 10.1 | 17.2 | 0.59 |
| supertonic | 8 | 4 | short | 2.3 | 4.6 | 0.50 |
| supertonic | 8 | 4 | medium | 4.8 | 11.1 | 0.43 |
| supertonic | 8 | 4 | long | 7.0 | 17.2 | 0.41 |
| kokoro | 4 | 0 | short | 4.7 | 3.8 | 1.23 |
| kokoro | 4 | 0 | medium | 10.2 | 8.9 | 1.15 |
| kokoro | 4 | 0 | long | 15.4 | 13.8 | 1.12 |

### Findings

- The phone runs Supertonic faster than real time at every setting: 8 steps gives r of about
  0.6 at 2-4 threads, so it keeps up with playback with plenty of headroom.
- Steps scale as on the Nova Air: 8 -> 6 is about 20-25 % faster, 8 -> 4 about 45 % faster.
- Threads: 2 -> 4 does not help (about 3-5 % slower). 8 threads is clearly worse (r 0.74-0.88 at
  8 steps). The 2-4 cap holds here too.
- Kokoro is r of about 1.1-1.2, so it is slower than real time on this phone, roughly twice as
  slow as Supertonic at 8 steps.

## Device comparison (4 threads, long passage)

| steps | Nova Air r | Xiaomi r | Nova Air / Xiaomi |
|---|---|---|---|
| 8 | 2.25 | 0.61 | 3.7x |
| 6 | 1.72 | 0.47 | 3.7x |
| 4 | 1.20 | 0.38 | 3.2x |

The Xiaomi keeps up with real time at 8 steps (r 0.61 < 1). The Nova Air does not at any step
count.

## Listening verdict on steps (2026-09-30)

Heard on the Xiaomi's samples by one listener, using the labelled Samples screen, not the blind
check: 8 steps is the reference, **6 steps is still acceptable, 4 steps is clearly bad**.

What that means for speed: 4 steps is out, so the usable range is 6-8 steps. On the Nova Air that
is r of about 1.7-2.3 (4 threads), so it cannot keep up with real time at any acceptable quality
and needs audio generated ahead of playback. On the Xiaomi, 6 steps (r about 0.47) has plenty of
headroom, and 8 steps (r about 0.61) also keeps up.

Still open: whether 8 vs 6 is audible blind (use the blind A/B/C check), and whether the same
verdict holds on the Nova Air's audio (it should be identical, since threads do not change sound).

## One-word latency and single-word clips (2026-10-05)

Measurement for the reader's "speak word" feature (tooling commit `cc20e900`). **Xiaomi only so
far; the Nova Air one-word run is still pending.** Same tool, debug build (now arm64-only),
run 2026-10-05 19:05/19:07-19:10, thermal status 0, not charging. Words are synthesized at rate
1.0, pitch 1.0, exactly as the feature will. `warm` = 5 repeats of the same word ("hello");
`distinct` = 36 different words (the listening-check list). Cold = the first word right after
engine load, where load includes the app's warm-up pass (matching `ensureLoaded`).

Engine load: Supertonic 0.87 s load + 0.99 s warm-up, Kokoro 1.5 s + 0.95 s (4 threads both).
System engine bind 0.31 s in a cold process (14 ms when the engine is already bound):
Google TTS, voice `en-US-language`, `synthesizeToFile`.

| engine | cold | warm median (same word) | distinct words median (range) | clip audio med |
|---|---|---|---|---|
| system | 502 ms | 1-3 ms | 56 ms (35-91) | 677 ms |
| supertonic (8 steps) | 1122 ms | 1093 ms | 1091 ms (960-1143) | 1358 ms |
| kokoro | 1345 ms | 1350 ms | 1406 ms (895-2264) | 721 ms |

### Findings

- **The system voice is effectively immediate**: ~500 ms once, ~60 ms for a new word after that
  (the engine additionally caches identical text, which is why same-word repeats measure 1-3 ms).
  With the app's own WAV cache, repeat taps cost only player start.
- **Neural fixed cost is real but small on the phone**: about 1.1 s Supertonic, 1.35-1.4 s
  Kokoro, roughly independent of word length (Supertonic 0.96-1.14 s across all 36 words).
  Kokoro's outliers are digits/acronyms ("1984" 2.2 s, "HTML" 1.8 s).
- **Cold engine** (never loaded in the session) adds load + warm-up: ~1.9 s Supertonic, ~2.5 s
  Kokoro on top, so ~3 s / ~3.9 s tap-to-audio worst case on this phone before caching.
- **Padding**: Supertonic clips carry ~0.32-0.36 s leading silence and ~0.5 s trailing silence
  (median); Kokoro ~0.12/0.14 s; system ~0.02/0.21 s. Trimming the silence before caching words
  would cut Supertonic's perceived wait by ~0.3 s. Recommended for the feature.
- **No clipped endings anywhere**: all 216 clips (36 words x plain/period x 3 engines) decay to
  silence (max tail20ms/peak 0.002, ends_loud 0/216, zero full-scale samples). The trailing
  full-stop variant changes nothing measurable, so **the feature should not append a period**.
- Signal analysis (envelopes) shows sensible multi-syllable structure on the word list; digits
  and acronyms look letter-spelled rather than word-like ("1984", "HTML"), as expected.
- **Honesty note**: this is waveform analysis, not a human listening pass. Prosody quality and
  heteronym sense ("read", "wound", "record" ...) cannot be judged from the signal; the clips are
  saved on-device in the app's Word clips section for a human check.

Raw numbers: `words-<stamp>.csv` and `samples/<stamp>/samples.csv` in the app's external files
folder; 216 WAVs under `samples/20261005-190{756,800,950}/`.
