#!/usr/bin/env bash
set -euo pipefail

usage() {
    cat <<'EOF'
Usage:
  gc-orphans.sh [--batch-size 1..500]

Requires SUPABASE_URL and SUPABASE_SERVICE_ROLE_KEY. Run on a schedule (for
example, every 15 minutes). Storage objects are removed through the Storage API.
EOF
}

batch_size=100
while (($#)); do
    case "$1" in
        --batch-size) batch_size="${2:?Missing value for --batch-size}"; shift 2 ;;
        -h|--help) usage; exit 0 ;;
        *) usage >&2; exit 2 ;;
    esac
done

if [[ ! "$batch_size" =~ ^[0-9]+$ ]] || ((batch_size < 1 || batch_size > 500)); then
    echo "--batch-size must be between 1 and 500" >&2
    exit 2
fi
: "${SUPABASE_URL:?Set SUPABASE_URL}"
: "${SUPABASE_SERVICE_ROLE_KEY:?Set SUPABASE_SERVICE_ROLE_KEY}"

base_url="${SUPABASE_URL%/}"
source "$(dirname "${BASH_SOURCE[0]}")/lib/auth-headers.sh"

# Never run two workers concurrently: a stale claim must not delete an object a
# user re-reserved in the meantime.
lock_file="${TMPDIR:-/tmp}/gc-orphans.lock"
exec 9>"$lock_file"
if ! flock -n 9; then
    echo "Another gc-orphans.sh run holds $lock_file; exiting" >&2
    exit 0
fi

rpc() {
    local name="$1"
    local body="$2"
    curl --silent --show-error --fail-with-body --max-time 60 \
        -X POST "$base_url/rest/v1/rpc/$name" \
        "${auth_headers[@]}" -H 'Content-Type: application/json' \
        --data "$body"
}

claim="$(rpc gc_orphan_book_files "$(jq -cn --argjson count "$batch_size" '{batch_size:$count}')")"
claim_id="$(jq -r '.claim_id // empty' <<<"$claim")"
if [[ -z "$claim_id" ]]; then
    echo "GC claim response did not include a claim id" >&2
    exit 1
fi

removed=0
while IFS= read -r file; do
    file_id="$(jq -r '.cloud_book_file_id' <<<"$file")"
    storage_path="$(jq -r '.storage_path' <<<"$file")"
    object_exists="$(jq -r '.object_exists' <<<"$file")"

    # Re-validate the claim (with lease keepalive) immediately before deletion.
    # A reaped claim or a re-reserved slot must skip deletion entirely.
    confirm="$(rpc confirm_orphan_book_file_gc "$(jq -cn --arg id "$file_id" --arg claim "$claim_id" \
        '{cloud_book_file_id:$id, claim_id:$claim}')")"
    confirm_status="$(jq -r '.status // empty' <<<"$confirm")"
    if [[ "$confirm_status" != "confirmed" ]]; then
        reason="$(jq -r '.reason // "claim no longer valid"' <<<"$confirm")"
        echo "Skipping file $file_id: $reason" >&2
        continue
    fi

    if [[ "$object_exists" == "true" ]]; then
        payload="$(jq -cn --arg path "$storage_path" '{prefixes:[$path]}')"
        http_status="$(curl --silent --show-error --output /dev/null --write-out '%{http_code}' --max-time 300 \
            -X DELETE "$base_url/storage/v1/object/book-files" \
            "${auth_headers[@]}" -H 'Content-Type: application/json' \
            --data "$payload")"
        if [[ ! "$http_status" =~ ^2[0-9][0-9]$ && "$http_status" != "404" ]]; then
            echo "Storage API deletion failed for file $file_id (HTTP $http_status)" >&2
            exit 1
        fi
    fi

    response="$(rpc complete_orphan_book_file_gc "$(jq -cn --arg id "$file_id" --arg claim "$claim_id" \
        '{cloud_book_file_id:$id, claim_id:$claim}')")"
    status="$(jq -r '.status // empty' <<<"$response")"
    if [[ "$status" != "removed" && "$status" != "skipped" ]]; then
        reason="$(jq -r '.reason // "unexpected GC response"' <<<"$response")"
        echo "GC did not complete for file $file_id: $reason" >&2
        exit 1
    fi
    removed=$((removed + 1))
done < <(jq -c '.files[]?' <<<"$claim")

# Reap objects that outlived their file row (narrow TOCTOU); paths embed the
# file id so they can never be re-reserved. No claim handshake applies.
untracked="$(rpc list_untracked_book_file_objects "$(jq -cn --argjson count "$batch_size" '{batch_size:$count}')")"
untracked_removed=0
while IFS= read -r object; do
    storage_path="$(jq -r '.storage_path' <<<"$object")"
    payload="$(jq -cn --arg path "$storage_path" '{prefixes:[$path]}')"
    http_status="$(curl --silent --show-error --output /dev/null --write-out '%{http_code}' --max-time 300 \
        -X DELETE "$base_url/storage/v1/object/book-files" \
        "${auth_headers[@]}" -H 'Content-Type: application/json' \
        --data "$payload")"
    if [[ ! "$http_status" =~ ^2[0-9][0-9]$ && "$http_status" != "404" ]]; then
        echo "Storage API deletion failed for untracked object $storage_path (HTTP $http_status)" >&2
        exit 1
    fi
    untracked_removed=$((untracked_removed + 1))
done < <(jq -c '.objects[]?' <<<"$untracked")

echo "Orphan GC removed $removed file(s) and $untracked_removed untracked object(s)"
