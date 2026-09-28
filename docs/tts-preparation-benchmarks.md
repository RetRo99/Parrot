# TTS voice-pack preparation: old flow vs. new flow

Measured 2026-09-28 on emulator `Medium_Phone_Second` (ARM64, Apple Silicon host), Kokoro
pack, cold start (model state cleared before each run). Timestamps parsed from logcat
(`-v epoch`), same tags both runs: `TtsModelManager`, `SherpaOnnxTts`.

**Environment caveat:** the emulator runs ARM64 code translated on a fast host. The old
flow's bzip2 extraction is CPU-bound, so this is a *best case* for the old flow — a real
budget phone stretches the extraction phase into minutes (the original ~20-minute
complaint). The new flow is network-bound and behaves the same everywhere.

## Results

| Phase | Old flow (`.tar.bz2` + on-device extract) | New flow (manifest + per-file download) |
|---|---|---|
| Transfer | 98.4 MB, 1 archive | 148.97 MB, 6 files + manifest |
| Download | 10.6 s | 15.2 s (sum of per-file, incl. SHA-256 verify) |
| "Preparing"/extract | **124.1 s** (bzip2, single-threaded) | **~0.3 s** (only `espeak-ng-data.zip`, 9 MB) |
| Engine load | 1.2 s | 0.9 s |
| UI/service overhead | ~0.2 s | ~4.3 s (manifest fetch + service start) |
| **Total: tap → voice ready** | **135.9 s** | **21.6 s** (**6.3× faster**) |
| Extraction share of total | **91 %** | ~1 % |

### New flow, per file (from `Prepared … in Xms` log lines)

| File | Size | Time |
|---|---|---|
| `model.int8.onnx` | 134.2 MB | 10.37 s |
| `espeak-ng-data.zip` | 9.0 MB | 3.84 s (download + unzip) |
| `voices.bin` | 5.8 MB | 0.92 s |
| `LICENSE` | 11 KB | 0.48 s |
| `tokens.txt` | 1 KB | 0.43 s |
| `README.md` | 135 B | 0.35 s |

Per-file times include SHA-256 verification. Files transfer sequentially today; the
per-file model makes parallel transfers trivial to add later if latency-bound links need it.

## What the numbers say

- The 20-minute experience was almost entirely **bzip2 decompression on-device**, not the
  network. Eliminating the archive eliminated 124 of 136 seconds — *even on hardware that
  is unusually kind to the old flow*.
- The trade-off is bandwidth: uncompressed per-file assets transfer **~51 % more bytes**
  (149 MB vs 98 MB) because bzip2 was compressing the int8 ONNX by ~26 %. On slow networks
  the new flow's download phase is longer, but it is resumable per file (HTTP Range +
  `.part` files), so interruptions no longer cost a full restart — the old flow deleted
  partial progress on any error.
- Time-to-first-audio is now purely a function of connection speed, which users understand;
  a CPU-bound "Preparing 47 %" phase that ignores their Wi-Fi speed did not scale.

## Failure behavior observed

The first old-flow attempt ran with broken emulator DNS and failed after **10 s** with
`GaiException` → UI showed *"Download failed. Check your connection."* + Retry. Clean
failure handling in both flows; noted here because the interrupted run also demonstrated
that the old flow leaves no resumable state behind.

## Not exercised in this session (covered by unit tests, worth a live pass later)

- Resume across app restart mid-download (Range + `.part`)
- Checksum-mismatch → automatic re-download of the affected file
- Model version upgrade (`.version` marker mismatch → clean re-download)

## Reproducing

1. `scripts/publish-tts-models.sh` — repacks upstream sherpa-onnx archives into plain
   files + zips with a `manifest.json`, publishes to `RetRo99/tts-models` releases.
2. Old flow: build the pre-change revision, clear `files/tts-models` + `cache/`, tap
   Download, capture `logcat -v epoch -s TtsModelManager:* SherpaOnnxTts:*`.
3. Markers: `Downloading … model` → `Model download: 100 %` → `Extracting …` →
   `Extraction finished` → `Kokoro loaded` (old); `Downloading … (N files, M bytes)` →
   `Prepared <file> … in Xms` → `Kokoro loaded` (new).
