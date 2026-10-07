# generate-recap

> **Durable V2 contract:** this endpoint now submits/fetches account-owned
> jobs; provider execution runs in `recap-worker`. See
> [server-backed recaps](../../../docs/server-backed-recaps.md) for current
> API, consent, worker secrets, scheduler setup and deployment checks.
> Synchronous response/upload/quota descriptions below are historical;
> retain the provider configuration guidance, not the old client contract.

Turns a reading-session excerpt into a 2-3 sentence recap through
[DeepInfra](https://deepinfra.com/mistralai/Mistral-Nemo-Instruct-2407/api)
using `mistralai/Mistral-Nemo-Instruct-2407`.
The provider URL and the model allow-list are fixed in `recap.ts`.

> Migration is local until deployed and tested with a DeepInfra key. The model
> page lists Apache 2.0 licensing and zero content retention. Provider DPA and
> transfer safeguards still need review; update user disclosures before rollout.

## Secrets

| Name | Required | Default | Notes |
|---|---|---|---|
| `DEEPINFRA_API_KEY` | yes | — | Missing → 503; old Go key is not used |
| `RECAP_ENABLED` | yes | off | Kill switch; anything but `true` → 503 |
| `RECAP_MODEL` | no | `mistralai/Mistral-Nemo-Instruct-2407` | Only this model; stale Go values fail closed |
| `RECAP_DAILY_LIMIT` | no | `30` | Recaps per user per UTC day, 1-1000; else 503 |

All four are read per request, so changes apply without a redeploy.

```sh
supabase secrets set DEEPINFRA_API_KEY=<your-deepinfra-key> --project-ref <project-ref>
supabase secrets set RECAP_ENABLED=true --project-ref <project-ref>
supabase secrets set RECAP_MODEL=mistralai/Mistral-Nemo-Instruct-2407 --project-ref <project-ref>
supabase secrets set RECAP_DAILY_LIMIT=<n> --project-ref <project-ref>
```

Turn recaps off: `supabase secrets set RECAP_ENABLED=false --project-ref <project-ref>`.

## Deploy

For the DeepInfra migration, keep `RECAP_ENABLED=false` while setting the new
key and replacing any old `RECAP_MODEL` value. Deploy **both** `generate-recap`
and `recap-worker` because both import the shared provider code. Run an
allowlisted smoke test and check English/Slovenian output, cost, latency and
long-session truncation before broader enablement. Never paste keys into chat
or commit them. Revoke/remove the obsolete Go key after a successful rollout.

```sh
supabase functions deploy recap-worker --project-ref <project-ref>
```

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

The worker uses at most the latest 192,000 characters of the session.

- Up to 24k UTF-16 units (`CHUNK_CHARS`) it is one model call. This encodes to
  at most 72k UTF-8 bytes, conservatively below the 131,072-token context with
  prompt/output headroom, without relying on English-only token estimates.
- Longer excerpts are split at line or sentence breaks into parts of up to 24k
  chars. Each part gets short factual notes (map, 4 calls at a time), then one
  call merges the notes, in reading order, into the 2-3 sentence recap with the
  same rules (map-reduce). Notes are internal and never returned or logged.
- All calls share a 135 s budget (`DEADLINE_MS`; the Edge gateway answers 504
  at 150 s). Each call has a 60 s timeout and retries once only on 5xx/network
  with at least 10 s left; part calls stop 35 s early to leave time to merge.
  Any failed part fails the recap with that part's status.
- Time budget: past 192k chars (`MAX_INPUT_CHARS`) the most recent 192k are used
  and the log line says `trimmed`. This keeps the map stage to about eight parts.
- One recap uses one quota unit however many model calls it takes.

Historical only, not DeepInfra performance: measured on `hy3` via Go
(2026-10-02): 8k chars 3.2 s; 40k 4.0 s; 300k chars in
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
