#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
CONFIG_DIR="${STORYTELLER_CONFIG_DIR:-$HOME/Library/Application Support/Storyteller}"
DATA_DIR="${STORYTELLER_DATA_DIR:-$HOME/Documents/Storyteller}"
SECRET_FILE="$CONFIG_DIR/STORYTELLER_SECRET_KEY.txt"

available_kib="$(df -Pk "$HOME" | awk 'NR == 2 { print $4 }')"
minimum_kib=$((10 * 1024 * 1024))
if [[ -z "$available_kib" || "$available_kib" -lt "$minimum_kib" ]]; then
    printf 'Storyteller needs about 10 GiB free for its image and data. Free space on %s before starting.\n' "$HOME" >&2
    exit 1
fi

mkdir -p "$CONFIG_DIR" "$DATA_DIR"
chmod 700 "$CONFIG_DIR"
if [[ ! -s "$SECRET_FILE" ]]; then
    openssl rand -base64 32 > "$SECRET_FILE"
    chmod 600 "$SECRET_FILE"
fi

export STORYTELLER_CONFIG_DIR="$CONFIG_DIR"
export STORYTELLER_DATA_DIR="$DATA_DIR"
export STORYTELLER_SECRET_FILE="$SECRET_FILE"

docker compose \
    --project-name storyteller-local \
    --file "$ROOT/scripts/storyteller/compose.yaml" \
    up --detach

printf 'Storyteller is starting at http://localhost:8001\n'
