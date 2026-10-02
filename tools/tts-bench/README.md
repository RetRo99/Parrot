# tts-bench

Dev-only Android app (`com.retro99.ttsbench`) that measures neural TTS speed. It never ships.

Run over adb (modes: quick, full, kokoro, all), then read logcat tag `TtsBench` or the CSVs
in `/sdcard/Android/data/com.retro99.ttsbench/files/`:

    ./gradlew :tools:tts-bench:installDebug
    adb shell am start -n com.retro99.ttsbench/.MainActivity --es mode quick
    adb logcat -s TtsBench

The app has a "Listening checks" section for the two by-ear questions: 8/6/4 steps (blind A/B/C)
and model-native speed against player time-stretch at 1.25x, 1.5x and 2.0x.
