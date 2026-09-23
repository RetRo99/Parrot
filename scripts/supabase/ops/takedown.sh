#!/usr/bin/env bash
set -euo pipefail

usage() {
    cat <<'EOF'
Usage:
  takedown.sh --file-id UUID --reason TEXT --actor TEXT
  takedown.sh --content-hash HASH --reason TEXT --actor TEXT [--algorithm sha-256-v1]
  takedown.sh --unblock-hash HASH --reason TEXT --actor TEXT [--algorithm sha-256-v1]

Requires SUPABASE_URL and SUPABASE_SERVICE_ROLE_KEY. The service-role key is
read from the environment and is never printed.
EOF
}

file_id=""
content_hash=""
algorithm="sha-256-v1"
reason=""
actor=""
unblock="false"

while (($#)); do
    case "$1" in
        --file-id) file_id="${2:?Missing value for --file-id}"; shift 2 ;;
        --content-hash) content_hash="${2:?Missing value for --content-hash}"; shift 2 ;;
        --unblock-hash) content_hash="${2:?Missing value for --unblock-hash}"; unblock="true"; shift 2 ;;
        --algorithm) algorithm="${2:?Missing value for --algorithm}"; shift 2 ;;
        --reason) reason="${2:?Missing value for --reason}"; shift 2 ;;
        --actor) actor="${2:?Missing value for --actor}"; shift 2 ;;
        -h|--help) usage; exit 0 ;;
        *) usage >&2; exit 2 ;;
    esac
done

: "${SUPABASE_URL:?Set SUPABASE_URL}"
: "${SUPABASE_SERVICE_ROLE_KEY:?Set SUPABASE_SERVICE_ROLE_KEY}"
: "${reason:?Set --reason}"
: "${actor:?Set --actor}"
if [[ -n "$file_id" && -n "$content_hash" ]] || [[ -z "$file_id" && -z "$content_hash" ]]; then
    usage >&2
    exit 2
fi
if [[ "$unblock" == "true" && -n "$file_id" ]]; then
    usage >&2
    exit 2
fi

base_url="${SUPABASE_URL%/}"
auth_headers=(-H "apikey: $SUPABASE_SERVICE_ROLE_KEY" -H "Authorization: Bearer $SUPABASE_SERVICE_ROLE_KEY")

rpc() {
    local name="$1"
    local body="$2"
    curl --silent --show-error --fail-with-body \
        -X POST "$base_url/rest/v1/rpc/$name" \
        "${auth_headers[@]}" -H 'Content-Type: application/json' \
        --data "$body"
}

complete_file_deletion() {
    local id="$1"
    local marked response status path payload complete_status
    payload="$(jq -cn --arg id "$id" --arg reason "$reason" --arg actor "$actor" \
        '{cloud_book_file_id:$id, reason:$reason, actor:$actor}')"
    marked="$(rpc admin_takedown_book_file "$payload")"
    status="$(jq -r '.status // empty' <<<"$marked")"
    if [[ "$status" == "rejected" ]]; then
        jq -r '.reason // "Takedown was rejected"' <<<"$marked" >&2
        return 1
    fi
    if [[ "$status" == "removed" ]]; then
        return 0
    fi
    if [[ "$status" != "deleting" ]]; then
        echo "Unexpected takedown status: $status" >&2
        return 1
    fi
    path="$(jq -r '.storage_path // empty' <<<"$marked")"
    if [[ -z "$path" ]]; then
        echo "Takedown response did not include the storage path" >&2
        return 1
    fi

    payload="$(jq -cn --arg path "$path" '{prefixes:[$path]}')"
    if ! curl --silent --show-error --fail-with-body \
        -X DELETE "$base_url/storage/v1/object/book-files" \
        "${auth_headers[@]}" -H 'Content-Type: application/json' \
        --data "$payload"; then
        response="$(rpc complete_book_file_deletion "$(jq -cn --arg id "$id" '{cloud_book_file_id:$id}')" 2>/dev/null || true)"
        complete_status="$(jq -r '.status // empty' <<<"$response" 2>/dev/null || true)"
        [[ "$complete_status" == "removed" ]] && return 0
        return 1
    fi

    response="$(rpc complete_book_file_deletion "$(jq -cn --arg id "$id" '{cloud_book_file_id:$id}')")"
    complete_status="$(jq -r '.status // empty' <<<"$response")"
    if [[ "$complete_status" != "removed" ]]; then
        jq -r '.reason // "Storage deletion is not yet complete"' <<<"$response" >&2
        return 1
    fi
}

if [[ "$unblock" == "true" ]]; then
    payload="$(jq -cn --arg algorithm "$algorithm" --arg hash "$content_hash" \
        --arg reason "$reason" --arg actor "$actor" \
        '{content_hash_algorithm:$algorithm, content_hash:$hash, reason:$reason, actor:$actor}')"
    response="$(rpc admin_unblock_content_hash "$payload")"
    jq -r '.status // "Unblock request completed"' <<<"$response"
    exit 0
fi

if [[ -n "$file_id" ]]; then
    complete_file_deletion "$file_id"
    echo "Takedown completed for file $file_id"
    exit 0
fi

payload="$(jq -cn --arg algorithm "$algorithm" --arg hash "$content_hash" \
    --arg reason "$reason" --arg actor "$actor" \
    '{content_hash_algorithm:$algorithm, content_hash:$hash, reason:$reason, actor:$actor}')"
response="$(rpc admin_block_content_hash "$payload")"
file_ids=()
offset=0
while true; do
    page="$(curl --silent --show-error --fail-with-body --get \
        "$base_url/rest/v1/cloud_book_files" "${auth_headers[@]}" \
        --data-urlencode 'select=id' \
        --data-urlencode "content_hash_algorithm=eq.$algorithm" \
        --data-urlencode "content_hash=eq.$content_hash" \
        --data-urlencode 'status=in.(available,deleting)' \
        --data-urlencode 'order=id.asc' \
        --data-urlencode 'limit=1000' \
        --data-urlencode "offset=$offset")"
    page_count="$(jq 'length' <<<"$page")"
    while IFS= read -r id; do
        [[ -n "$id" ]] && file_ids+=("$id")
    done < <(jq -r '.[].id' <<<"$page")
    (( page_count < 1000 )) && break
    offset=$((offset + page_count))
done

for id in "${file_ids[@]}"; do
    complete_file_deletion "$id"
    echo "Takedown completed for file $id"
done

echo "Hash blocked: $algorithm:$content_hash"
