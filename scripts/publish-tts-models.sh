#!/usr/bin/env bash
set -euo pipefail

# Re-packages the upstream sherpa-onnx TTS model archives as plain files + small
# zips (so devices never burn CPU on bzip2 extraction), generates manifest.json
# with SHA-256 checksums, and publishes everything as a GitHub release.
#
# The app downloads from the manifest (see TtsModelManager.MANIFEST_URL), and each
# published file URL is immutable per release tag, so releases are freely cacheable.
#
# Requirements: curl, tar, python3, gh (authenticated for the target repo)
#
# Usage:
#   scripts/publish-tts-models.sh [release-tag]
#
# Configuration (environment variables):
#   TTS_MODELS_REPO   GitHub repo to publish to (default: RetRo99/tts-models)
#   RELEASE_TAG       Release tag (default: models-<UTC timestamp>)

REPO="${TTS_MODELS_REPO:-RetRo99/tts-models}"
TAG="${1:-models-$(date -u +%Y%m%d%H%M%S)}"
UPSTREAM_BASE="https://github.com/k2-fsa/sherpa-onnx/releases/download/tts-models"

# modelId|upstream archive|unpacked root directory
MODELS=(
  "kokoro|kokoro-int8-en-v0_19.tar.bz2|kokoro-int8-en-v0_19"
  "supertonic|sherpa-onnx-supertonic-3-tts-int8-2026-05-11.tar.bz2|sherpa-onnx-supertonic-3-tts-int8-2026-05-11"
)

WORK_DIR="$(mktemp -d)"
trap 'rm -rf "$WORK_DIR"' EXIT
STAGE_DIR="$WORK_DIR/stage"
UNPACK_DIR="$WORK_DIR/unpack"
MANIFEST="$WORK_DIR/manifest.json"
mkdir -p "$STAGE_DIR" "$UNPACK_DIR"

UNPACKED_PAIRS=()
for spec in "${MODELS[@]}"; do
  IFS='|' read -r model_id archive root_dir <<< "$spec"
  echo "Fetching upstream archive: $archive"
  curl -fL --retry 5 --retry-all-errors --retry-delay 2 -o "$WORK_DIR/$archive" "$UPSTREAM_BASE/$archive"
  echo "Unpacking $archive"
  tar -xjf "$WORK_DIR/$archive" -C "$UNPACK_DIR"
  if [ ! -d "$UNPACK_DIR/$root_dir" ]; then
    echo "error: expected unpacked directory $root_dir not found" >&2
    exit 1
  fi
  UNPACKED_PAIRS+=("$model_id=$UNPACK_DIR/$root_dir")
done

echo "Repacking models and generating manifest.json"
python3 - "$REPO" "$TAG" "$STAGE_DIR" "$MANIFEST" "${UNPACKED_PAIRS[@]}" <<'PY'
import hashlib
import json
import os
import shutil
import sys
import zipfile
from pathlib import Path

repo, tag, stage_dir, manifest_path = sys.argv[1:5]
stage_dir = Path(stage_dir)
pairs = sys.argv[5:]

REQUIRED = {
    "kokoro": {
        "model.int8.onnx",
        "voices.bin",
        "tokens.txt",
        "espeak-ng-data",
    },
    "supertonic": {
        "duration_predictor.int8.onnx",
        "text_encoder.int8.onnx",
        "vector_estimator.int8.onnx",
        "vocoder.int8.onnx",
        "tts.json",
        "unicode_indexer.bin",
        "voice.bin",
    },
}

ASSET_BASE = f"https://github.com/{repo}/releases/download/{tag}"


def sha256(path):
    digest = hashlib.sha256()
    with open(path, "rb") as handle:
        for chunk in iter(lambda: handle.read(1 << 20), b""):
            digest.update(chunk)
    return digest.hexdigest()


def zip_directory(source, destination):
    with zipfile.ZipFile(destination, "w", zipfile.ZIP_DEFLATED, compresslevel=6) as archive:
        for path in sorted(source.rglob("*")):
            if path.is_file():
                relative = path.relative_to(source.parent).as_posix()
                archive.write(path, arcname=relative)


models = []
for pair in pairs:
    model_id, root = pair.split("=", 1)
    root = Path(root)
    names = {entry.name for entry in root.iterdir()}
    missing = REQUIRED[model_id] - names
    if missing:
        sys.exit(f"error: {model_id} is missing required entries: {sorted(missing)}")

    files = []
    for entry in sorted(root.iterdir()):
        if entry.is_dir():
            path_name = f"{entry.name}.zip"
            staged = stage_dir / f"{model_id}-{path_name}"
            zip_directory(entry, staged)
            extract_to = entry.name
        else:
            path_name = entry.name
            staged = stage_dir / f"{model_id}-{path_name}"
            shutil.copyfile(entry, staged)
            extract_to = None

        files.append({
            "url": f"{ASSET_BASE}/{staged.name}",
            "path": path_name,
            "size": staged.stat().st_size,
            "sha256": sha256(staged),
            "extractTo": extract_to,
        })
        print(f"  {model_id}/{path_name}: {staged.stat().st_size} bytes")

    entry = {
        "id": model_id,
        "version": tag.removeprefix("models-"),
        "files": files,
    }
    # Optional: size of the incremental download from the previous version,
    # e.g. UPDATE_SIZE_BYTES_KOKORO=18000000. Shown in the app's "Update available" line.
    update_size = os.environ.get(f"UPDATE_SIZE_BYTES_{model_id.upper().replace('-', '_')}")
    if update_size:
        entry["updateSizeBytes"] = int(update_size)
    models.append(entry)

manifest = {"schemaVersion": 1, "models": models}
Path(manifest_path).write_text(json.dumps(manifest, indent=2) + "\n")
print(f"Wrote {manifest_path}")
PY

echo "Publishing release $TAG to $REPO"
gh release create "$TAG" \
  --repo "$REPO" \
  --title "TTS model assets $TAG" \
  --notes "Repacked sherpa-onnx TTS models for in-app download (plain files + zips, SHA-256 verified)." \
  "$STAGE_DIR"/* \
  "$MANIFEST"

echo
echo "Published. The app fetches:"
echo "  https://github.com/$REPO/releases/latest/download/manifest.json"
echo "If your repo differs from RetRo99/tts-models, update TtsModelManager.MANIFEST_URL to match."
