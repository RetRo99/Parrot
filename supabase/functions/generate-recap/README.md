# generate-recap

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
`supabase/migrations/20261002000000_parrot_cloud_recap_usage.sql`, so apply
migrations first:

```sh
supabase db push
supabase functions deploy generate-recap --project-ref <project-ref>
```

## API

`POST` with a signed-in, non-anonymous user's JWT:

```json
{ "excerpt": "…80-8000 chars…", "language": "sl", "lastSentence": "optional" }
```

`language` is one of `en sl de fr es it hr` (region subtags are ignored),
default `en`. Any other fields, such as `bookTitle`, are ignored.

| Status | Body |
|---|---|
| 200 | `{ "kind": "recap", "summary": "…" }` or `{ "kind": "not_enough", "summary": null }` |
| 401 | Not a verified, non-anonymous user, or the session was revoked |
| 422 | Excerpt too short, or unsupported language |
| 429 | `daily recap limit reached`, or provider rate limit (both send `Retry-After`) |
| 502 | Provider error or unusable output |
| 503 | Disabled, not configured, or `recap provider unavailable` (Go key rejected) |
| 504 | Provider timed out (60 s per attempt) |

## Tests

```sh
cd supabase/functions
deno test generate-recap/
```
