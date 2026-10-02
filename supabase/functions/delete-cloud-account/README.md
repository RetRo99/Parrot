# delete-cloud-account

Deletes the caller's cloud account: records a deletion request (freezes new
writes), removes their `book-files` objects, redacts audit rows, then deletes
the Auth user. Every step is idempotent, so a retry resumes after a failure.

Only this function records the request (`request_cloud_account_deletion_for`,
service role only, migration `20261003000100`), and only after the fresh
sign-in check. Clients can no longer call `request_cloud_account_deletion()`.

## Deploy gate

Apply migration `20261003000100` first: the function calls its RPC and reads
`confirmed_at`. Ship an app build that maps `reauthentication_required` (see
`SupabaseCloudAccountDataRepository`) before or with the function. Until most
users run it, set `DELETE_ACCOUNT_MAX_AUTH_AGE_SECONDS=86400` so older builds
are not stuck on a generic error.

## Keys

Uses `SUPABASE_SECRET_KEYS` / `SUPABASE_PUBLISHABLE_KEYS` (`default` entry),
falling back to the legacy `SUPABASE_SERVICE_ROLE_KEY` / `SUPABASE_ANON_KEY`.

| Secret | Default | Notes |
|---|---|---|
| `DELETE_ACCOUNT_MAX_AUTH_AGE_SECONDS` | `600` | Max age of the last sign-in, 60-86400 |

## API

`POST` with a signed-in user's JWT, no body.

| Status | `code` | Meaning |
|---|---|---|
| 200 | — | `{ "status": "deleted" }`, also when it was already deleted |
| 401 | `auth_required` | Missing, invalid, expired or signed-out session |
| 403 | `anonymous_not_allowed` | Anonymous accounts are rejected |
| 403 | `reauthentication_required` | Last sign-in too old; sign in again and retry |
| 500 | `deletion_incomplete` | A step failed, or Auth was rate-limited or down; retry |
| 500 | `server_misconfigured` | URL or keys missing |

Sign-in age is the newest `amr` timestamp in the JWT (refreshes keep it).
Once this function has confirmed a request, retries finish it without a
fresh sign-in. Rows with a null `confirmed_at` (old client RPC) do not count.

If Auth says the token's user is already gone (`user_not_found`, only after
the signature checks out), Storage cleanup and audit redaction still run for
the token's `sub` before the 200, since neither cascades from `auth.users`.

## Lockfile

No `deno.lock`: Deno 2.9 writes lockfile v5, which the Deno 2.1-based Edge
Runtime may not read. Imports are pinned to exact versions instead.

## Tests

```sh
cd supabase/functions/delete-cloud-account && deno test
cd ../_shared && deno test
```
