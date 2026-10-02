# shellcheck shell=bash disable=SC2034
# Sourced by the ops scripts. Writes the service-role headers to a 0600
# temp file read via curl -H @file, so the key never shows in ps/argv.
# Sets auth_headers=(-H @file); the file is removed on exit.

auth_header_file="$(umask 077 && mktemp "${TMPDIR:-/tmp}/parrot-ops-headers.XXXXXX")"
trap 'rm -f "$auth_header_file"' EXIT
# printf is a bash builtin, so the key is never a process argument.
printf 'apikey: %s\nAuthorization: Bearer %s\n' \
    "$SUPABASE_SERVICE_ROLE_KEY" "$SUPABASE_SERVICE_ROLE_KEY" >"$auth_header_file"
auth_headers=(-H "@$auth_header_file")
