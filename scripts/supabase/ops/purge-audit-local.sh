#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../../.." && pwd)"
project_id="$(sed -nE 's/^project_id[[:space:]]*=[[:space:]]*"([^"]+)".*/\1/p' "$ROOT/supabase/config.toml")"
if [[ -z "$project_id" ]]; then
    echo "Could not determine the local Supabase project ID" >&2
    exit 1
fi

if [[ -n "${DOCKER_BIN:-}" ]]; then
    docker_bin="$DOCKER_BIN"
elif command -v docker >/dev/null 2>&1; then
    docker_bin="$(command -v docker)"
elif [[ -x /Applications/Docker.app/Contents/Resources/bin/docker ]]; then
    docker_bin=/Applications/Docker.app/Contents/Resources/bin/docker
else
    echo "Docker CLI is unavailable; skipping local audit purge" >&2
    exit 1
fi

container="supabase_storage_${project_id}"
container_env="$("$docker_bin" inspect "$container" --format '{{range .Config.Env}}{{println .}}{{end}}')"
service_key="$(sed -n 's/^SERVICE_KEY=//p' <<<"$container_env")"
if [[ -z "$service_key" ]]; then
    echo "Local Supabase service key was not found in $container" >&2
    exit 1
fi

SUPABASE_URL=http://127.0.0.1:54321 \
SUPABASE_SERVICE_ROLE_KEY="$service_key" \
    "$ROOT/scripts/supabase/ops/purge-audit.sh"
