# Supertonic 3 INT8: reduce time to produce a chunk

**Status:** Technical implementation plan; no runtime changes implemented.
**Scope:** Android Sherpa-ONNX Supertonic synthesis. Optimize the time to generate **the same text chunk** and save its WAV. Keep the current model, voice, language, 1.0× speed, and **8 generation steps** for the primary comparison. Do not change playback scheduling, chunk boundaries, preview UI, or iOS behavior in this work.

## Baseline and metric

In the supplied Nova Air run, a 253-character chunk took 37.4 s and a 162-character chunk took 26.3 s; the earlier logic timed out on the first at approximately 40.5 s. These are measurements from the reported run, **not** cross-device expectations. The two observations do not establish a fixed per-call overhead, a real-time factor, or the cause of slowness.

**Primary metric:** elapsed monotonic time from immediately before `OfflineTts.generateWithConfig(text, config)` to its return for the **253-character first chunk**, with the engine already loaded. Also measure the 162-character chunk, WAV saving, time waiting for `generationMutex`/`TtsAudioGenerator` semaphore, engine loading and warm-up, and end-to-end cache-miss `synthesize()` separately. Distinguish a warm engine, a freshly loaded engine, and a cache hit; do not mix them in the primary result. Total synthesis and power/thermal behavior are secondary metrics. Playback time is not the result of this work.

## Phase 1: instrument and establish a reproducible baseline

1. Add monotonic, low-overhead timings in `feature/reader/ui/src/androidMain/kotlin/com/retro99/reader/ui/tts/SupertonicOnnxSynthesizer.kt`: `ensureLoaded()`/warm-up; mutex wait; native generation; WAV save. In `TtsAudioGenerator.kt`, distinguish cache hit, semaphore wait, and actual synthesis. Do not log preview text or other private content. Keep profiling builds separate from ordinary release timing runs.
2. Fix the benchmark inputs to the **exact original strings**, not merely 253/162-character substitutes. Record voice ID, model manifest/version, AAR/native runtime version, speed, steps, selected thread count/provider, Android version, ABI, cache state, thermal state, audio duration (`samples / sampleRate`), and whether warm-up completed. The current synthesizer explicitly sets `numSteps = 8` and uses `availableProcessors().coerceIn(2, 4)`; preserve the current behavior as control.
3. Obtain ORT operator/session profiles for the four Supertonic graphs (duration predictor, text encoder, vector estimator repeated eight times, vocoder) in a diagnostic build. Sherpa v1.13.8's provider-config file supports `ProfilingFilePrefix` and `SessionConfig.*` passthrough; first verify this mechanism and resulting profile files with the **bundled AAR**. Compare the profile overhead against unprofiled measurements. A model-level profile is needed before claiming that estimator, vocoder, graph setup, or thread spinning dominates.

## Phase 2: improve the existing CPU/INT8 path

1. Expose a **test-only** way to select `OfflineTtsModelConfig.numThreads` when constructing `OfflineTts` in `SupertonicOnnxSynthesizer.kt`. The choice is made at engine creation, so recreate the engine between configurations. Sweep a bounded, device-aware set (for example 1, 2, 3, 4, 6, 8 where supported, plus the existing heuristic); do not assume an out-of-range/default value such as `0` works in this Sherpa build without verifying it. Run identical warm and sustained tests. Four winning over two and eight on Nova Air is a data point, **not** a hard-coded cross-device default.
2. If profiles and thermal/power observations warrant it, A/B CPU session options through Sherpa's `provider = "cpu:<config-file>"` mechanism. Test the baseline against reduced ORT intra-op spinning (`SessionConfig.session.intra_op.allow_spinning=0`) and, separately, supported spinning-duration options. Confirm that options were accepted in the bundled runtime. Measure **latency and power**: reducing spinning can lower energy yet increase latency; do not assume a percentage improvement. Keep memory-pattern/CPU-arena defaults unless profiles identify a reason to change them.
3. Consider CPU affinity only as an **optional, later experiment** after thread-count results. Detect topology generically, verify permitted cores and the ORT affinity-string format, and keep an unpinned baseline/fallback. `availableProcessors()` is not a reliable count of fast cores. Do not bake in a Nova Air core map or assume affinity wins on every scheduler/SoC.
4. Select a production policy only from cross-device results. Prefer a small, robust default/fallback over an expensive calibration on every device. If on-device tuning is justified, bound its candidate count and duration, do it outside the Play critical path, persist by device/runtime/model version, and revalidate when that combination changes. Calibration time, battery, and thermal disturbance count as costs.

## Phase 3: optional runtime/provider comparison

- Keep the current bundled Sherpa/ORT CPU path as control. Test `xnnpack` only if available in the **shipped native build** and if profiling shows substantial supported work in the model's four graphs. ORT warns that XNNPACK has a separate thread pool and operator-dependent CPU fallback; Sherpa v1.13.8 registers it without explicit XNNPACK pool sizing in its session code. Compare first-chunk latency, fallback/partitioning, cold initialization, memory, output audio, and sustained power. Availability alone is not a speedup.
- Treat `nnapi` as an opt-in experiment, not a generic default: Android API, drivers, dynamic shapes, supported operators, and fallback vary by device. Avoid FP16 relaxation because speech-quality preservation is required. Fall back cleanly to CPU on unsupported devices or regressions.
- Evaluate a **compatible, pinned** Sherpa AAR/native-ORT upgrade only as a separate A/B with unchanged model assets, text and generation parameters. Verify JNI/API and ABI compatibility, output correctness, model loading, and cold/warm performance before adoption. Do not infer that a newer version must be faster. Changing to FP32, an alternate runtime, or a different model is outside the primary INT8 comparison and requires its own quality/memory/download review.

## Experiment controls and release gate

Run repeated, randomized A/B trials across at least the Nova Air plus different Android performance tiers/SoC families. Control charging/power mode, foreground/background load, thermal state, and cache; include cold and sustained runs. Report per-device median and p90 for first-chunk **native generation** and cache-miss **WAV-ready** time, as well as total two-chunk generation, generated audio duration, memory, and energy/thermal trend. Do not extrapolate another device's RTF to this device or derive RTF from assumed speaking rate.

Verify successful synthesis, no skipped/repeated words, same voice and 1.0× duration, **8 steps**, and no audible quality regression on representative text. Threading and provider changes can alter floating-point results, so do not require bit-identical WAVs as the sole quality gate. Retain the existing CPU configuration as a fallback. Ship only a configuration with a repeatable first-chunk improvement across the target devices and no unacceptable device-specific latency, stability, quality, or power regression; if no candidate meets that gate, report the negative result rather than guessing a universal thread count.

## Explicitly out of scope

Do not shorten/split the first chunk as evidence of *faster generation of the same chunk*, parallelize chunks, start playback before all synthesis completes, alter preview timeouts, reduce steps below 8, or raise speech speed above 1.0. Such changes may improve perceived latency or total wall time, but answer a different question.

## References

- [Sherpa-ONNX v1.13.8 Supertonic synthesis loop](https://github.com/k2-fsa/sherpa-onnx/blob/v1.13.8/sherpa-onnx/csrc/offline-tts-supertonic-impl.cc)
- [Sherpa-ONNX v1.13.8 session and provider configuration](https://github.com/k2-fsa/sherpa-onnx/blob/v1.13.8/sherpa-onnx/csrc/session.cc)
- [ONNX Runtime mobile provider guidance](https://onnxruntime.ai/docs/tutorials/mobile/)
- [ONNX Runtime XNNPACK thread-pool guidance](https://onnxruntime.ai/docs/execution-providers/Xnnpack-ExecutionProvider.html)
