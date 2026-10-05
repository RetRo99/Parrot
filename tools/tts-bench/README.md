# tts-bench

Dev-only Android app (`com.retro99.ttsbench`) that measures neural TTS speed. It never ships.

Run over adb (modes: quick, full, kokoro, word, all), then read logcat tag `TtsBench` or the CSVs
in `/sdcard/Android/data/com.retro99.ttsbench/files/`:

    ./gradlew :tools:tts-bench:installDebug
    adb shell am start -n com.retro99.ttsbench/.MainActivity --es mode quick
    adb logcat -s TtsBench

Word clips over adb (`system`, `kokoro`, `supertonic` or `all`):

    adb shell am start -n com.retro99.ttsbench/.MainActivity --es wordclips all

The app has a "Listening checks" section for the two by-ear questions: 8/6/4 steps (blind A/B/C)
and model-native speed against player time-stretch at 1.25x, 1.5x and 2.0x.

Mode `word` measures the one-word latency row for the reader's "speak word" feature: cold (first
word after engine load) and warm (5 repeats) for the system TTS engine, Supertonic (8 steps) and
Kokoro, at the app's thread defaults. Results go to `words-<stamp>.csv` and logcat
(`WORD ... cold=... warm run=...`, summary `WORDS engine cold=... warm-median=...`).

The "Word clips" section generates about 30 isolated words (including heteronyms) per engine,
each plain and with a trailing period, for the by-ear single-word check and the clipped-tail
question. Clips land under `samples/<stamp>/` and play from the section itself or Samples.
