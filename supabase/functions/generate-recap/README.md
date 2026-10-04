# generate-recap

> **Durable V2 contract:** this endpoint now submits/fetches account-owned
> jobs; provider execution runs in `recap-worker`. See
> [server-backed recaps](../../../docs/server-backed-recaps.md) for current
> API, consent, worker secrets, scheduler setup and deployment checks.
> Synchronous response/upload/quota descriptions below are historical;
> retain the provider configuration guidance, not the old client contract.

Turns a reading-session excerpt into a 2-3 sentence recap through
[OpenCode Go](https://opencode.ai/docs/go/) (`hy3` by default).
The provider URL and the model allow-list are fixed in `recap.ts`.

> Terms caveat: OpenCode Go is designed for coding-agent traffic and
> personal use; this function is for a single personal, non-commercial app.

## Secrets

| Name | Required | Default | Notes |
|---|---|---|---|
| `OPENCODE_GO_API_KEY` | yes | — | Missing → 503 |
| `RECAP_ENABLED` | yes | off | Kill switch; anything but `true` → 503 |
| `RECAP_MODEL` | no | `hy3` | Only `hy3`, `glm-5.3-flash` or `mimo-v2.6-flash`; else 503 |
| `RECAP_DAILY_LIMIT` | no | `30` | Recaps per user per UTC day, 1-1000; else 503 |

All four are read per request, so changes apply without a redeploy.

```sh
supabase secrets set OPENCODE_GO_API_KEY=<your-opencode-go-key> --project-ref <project-ref>
supabase secrets set RECAP_ENABLED=true --project-ref <project-ref>
supabase secrets set RECAP_MODEL=<hy3|glm-5.3-flash|mimo-v2.6-flash> --project-ref <project-ref>
supabase secrets set RECAP_DAILY_LIMIT=<n> --project-ref <project-ref>
```

Turn recaps off: `supabase secrets set RECAP_ENABLED=false --project-ref <project-ref>`.

## Deploy

The function needs the `consume_recap_quota` RPC from
`supabase/migrations/20261002000000_parrot_cloud_recap_usage.sql` and the
upload RPCs from `20261004000000_parrot_cloud_recap_uploads.sql`, so apply
migrations first. Since `20261003000000_parrot_cloud_security_hardening.sql`,
only accounts in `cloud_feature_allowlist` (feature `recap`) get recaps, and
`recap_settings.global_daily_limit` caps all users together (default 500/day).
`recap_settings.per_user_daily_limit` (default 30) caps `RECAP_DAILY_LIMIT`;
the lower one wins. All refusals return the same 429 as the per-user limit.

```sh
supabase db push
supabase functions deploy generate-recap --project-ref <project-ref>
```

## API

`POST` with a signed-in, non-anonymous user's JWT:

```json
{ "excerpt": "…at least 80 chars…", "language": "sl", "lastSentence": "optional" }
```

Bodies over 256 KiB get 413: bodies of ~1 MB+ hang or fail at the Edge gateway
(measured 2026-10-02: 200 KB fine; 1-10 MB no response; 20 MB+ gateway 502).
Every early reply first drains the body (up to 16 MB) so it isn't stalled.
A longer excerpt is uploaded in parts of at most 200k chars (the app keeps
each body under 180 KB), all with one random upload id:

```json
{ "upload": { "id": "<uuid>", "index": 0, "total": 3 }, "text": "…part…" }
{ "upload": { "id": "<uuid>", "index": 1, "total": 3 }, "text": "…part…" }
{ "upload": { "id": "<uuid>", "index": 2, "total": 3 }, "text": "…last…", "language": "sl", "lastSentence": "optional" }
```

Parts before the last are stored by `put_recap_upload_part` (202, no quota,
no model call) in `recap_upload_parts`: owned by the user, no client table
access, at most 64 parts per upload and 128 held per user. The last part is
sent after the others: `take_recap_upload` returns the stored parts joined in
order and deletes them, then the request continues as a plain `{ excerpt }`
one. A missing part gives 409 and the parts are dropped, so the app starts a
new upload. Abandoned parts are deleted after an hour (on the user's next
upload and by `purge_cloud_retention`).

The excerpt is everything the user read in the session; there is no cap.

- Up to 250k chars (`CHUNK_CHARS`, ~57k English / ~100k dense Slovenian tokens,
  well inside `hy3`'s 192k input) it is one model call.
- Longer excerpts are split at line or sentence breaks into parts of up to 250k
  chars. Each part gets short factual notes (map, 4 calls at a time), then one
  call merges the notes, in reading order, into the 2-3 sentence recap with the
  same rules (map-reduce). Notes are internal and never returned or logged.
- All calls share a 135 s budget (`DEADLINE_MS`; the Edge gateway answers 504
  at 150 s). Each call has a 60 s timeout and retries once only on 5xx/network
  with at least 10 s left; part calls stop 35 s early to leave time to merge.
  Any failed part fails the recap with that part's status.
- Time budget: past 2M chars (`MAX_INPUT_CHARS`, ~20 h of reading, ~9 parts) the
  most recent 2M are used and the log line says `trimmed`.
- One recap uses one quota unit however many model calls it takes.

Measured on `hy3` via Go (2026-10-02): 8k chars 3.2 s; 40k 4.0 s; 300k chars in
one call 11.4 s; a whole novel (690k chars, 3 parts + merge) 14.2 s; 2M chars
(9 parts + merge) 31.2 s.

`language` is one of `en sl de fr es it hr` (region subtags are ignored),
default `en`. Any other fields, such as `bookTitle`, are ignored.

| Status | Body |
|---|---|
| 200 | `{ "kind": "recap", "summary": "…", "model": "hy3" }` or `{ "kind": "not_enough", "summary": null, "model": "hy3" }` (`model` is informational and optional for clients) |
| 202 | Upload part stored: `{ "stored": <index> }` |
| 400 | Invalid JSON, or an invalid upload part |
| 401 | Not a verified, non-anonymous user |
| 409 | `Upload incomplete`: a stored part is missing; upload again |
| 413 | Body over 256 KiB |
| 422 | Excerpt too short, or unsupported language |
| 429 | `daily recap limit reached`, provider rate limit, or `recap upload refused` (all send `Retry-After`) |
| 502 | Provider error or unusable output |
| 503 | Disabled, not configured, or `recap provider unavailable` (Go key rejected) |
| 504 | Provider timed out (60 s per attempt, 135 s per recap) |

A revoked (signed-out) session or a deleted user also gets 401.

## Tests

```sh
cd supabase/functions/generate-recap
deno test --node-modules-dir=none
```
