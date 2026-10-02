# delete-cloud-account

Deletes the caller's cloud account: records a deletion request (freezes new
writes), removes their `book-files` objects, redacts audit rows, then deletes
the Auth user. Every step is idempotent, so a retry resumes after a failure.

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
| 500 | `deletion_incomplete` | A step failed; retry to resume |
| 500 | `server_misconfigured` | URL or keys missing |

Sign-in age is the newest `amr` timestamp in the JWT (refreshes keep it).
Once a request is recorded, retries finish it without a fresh sign-in.

## Tests

```sh
cd supabase/functions
deno test delete-cloud-account/
```
