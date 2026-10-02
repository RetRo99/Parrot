#!/usr/bin/env bash
set -euo pipefail

if [[ "${1:-}" == "--help" || "${1:-}" == "-h" ]]; then
    cat <<'EOF'
Usage:
  purge-audit.sh

Deletes Parrot Cloud audit events older than 180 days. Schedule this command
hourly with SUPABASE_URL and SUPABASE_SERVICE_ROLE_KEY in its environment.
EOF
    exit 0
fi
if (($#)); then
    echo "Usage: purge-audit.sh [--help]" >&2
    exit 2
fi

: "${SUPABASE_URL:?Set SUPABASE_URL}"
: "${SUPABASE_SERVICE_ROLE_KEY:?Set SUPABASE_SERVICE_ROLE_KEY}"

base_url="${SUPABASE_URL%/}"
source "$(dirname "${BASH_SOURCE[0]}")/lib/auth-headers.sh"
response="$(curl --silent --show-error --fail-with-body \
    -X POST "$base_url/rest/v1/rpc/purge_expired_cloud_file_audit_events" \
    "${auth_headers[@]}" \
    -H 'Content-Type: application/json' \
    --data '{}')"

status="$(jq -r '.status // empty' <<<"$response")"
if [[ "$status" != "purged" ]]; then
    echo "Audit retention purge returned an unexpected response" >&2
    exit 1
fi
jq -r '"Purged \(.deleted_count) expired Parrot Cloud audit events"' <<<"$response"
