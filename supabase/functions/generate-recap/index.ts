// generate-recap — Supabase Edge Function
//
// Authenticated durable recap submission, lookup, consent and deletion.
// Generation runs in recap-worker; this HTTP request never calls a provider.
//
// Provider: OpenCode Go (OpenAI-compatible /chat/completions) at a fixed
// URL with a server-side model allow-list; see recap.ts. Secrets and
// deploy commands are in README.md next to this file.
//
// Contract: docs/server-backed-recaps.md. Quota is charged atomically by
// the worker's admission transaction, never by submission or lookup.

import { createClient, isAuthRetryableFetchError } from 'npm:@supabase/supabase-js@2.117.2'
import { classifyAuthError } from '../_shared/auth_errors.ts'
import {
  authenticate,
  bearerToken,
  type Claims,
  discardBody,
  parseUploadPart,
  readJsonBody,
  type UploadPart,
} from './guards.ts'
import {
  isEnabled,
  loadConfig,
  MAX_HINT_CHARS,
  MAX_INPUT_CHARS,
  MIN_EXCERPT_CHARS,
  parseLanguage,
} from './recap.ts'

// Legacy anon key, else the default new publishable key. Only used so
// getClaims can reach Auth for HS256 tokens; it grants no access itself.
function clientKey(): string | undefined {
  const anon = Deno.env.get('SUPABASE_ANON_KEY')
  if (anon) return anon
  try {
    const keys = JSON.parse(Deno.env.get('SUPABASE_PUBLISHABLE_KEYS') ?? '{}')
    return typeof keys?.default === 'string' ? keys.default : undefined
  } catch {
    return undefined
  }
}

const SUPABASE_URL = Deno.env.get('SUPABASE_URL')
const CLIENT_KEY = clientKey()
const authClient = SUPABASE_URL && CLIENT_KEY
  ? createClient(SUPABASE_URL, CLIENT_KEY, {
    auth: { autoRefreshToken: false, persistSession: false },
  })
  : null

// getClaims checks the signature against JWKS (asymmetric keys) or asks
// Auth (HS256). Invalid tokens → null; Auth outages throw → 500, not 401.
async function verifyClaims(token: string): Promise<Claims | null> {
  const { data, error } = await authClient!.auth.getClaims(token)
  if (error && isAuthRetryableFetchError(error)) throw error
  if (error || !data) return null
  // JWKS checks are local, so also ask Auth: rejects signed-out (revoked)
  // sessions and deleted users. One round trip vs a multi-second call.
  const { data: live, error: liveError } = await authClient!.auth.getUser(token)
  // Rate limits and outages throw (500) so clients never see a false 401.
  if (liveError && classifyAuthError(liveError) === 'unavailable') throw liveError
  if (liveError || live.user?.id !== data.claims.sub) return null
  return data.claims as Claims
}

// Runs as the caller (their JWT), so the RPC's auth.uid() is the user.
async function rpc(token: string, name: string, args: Record<string, unknown>) {
  const userClient = createClient(SUPABASE_URL!, CLIENT_KEY!, {
    auth: { autoRefreshToken: false, persistSession: false },
    global: { headers: { Authorization: `Bearer ${token}` } },
  })
  const { data, error } = await userClient.rpc(name, args)
  if (error) throw new RpcError(error.code ?? 'unknown')
  return data
}

class RpcError extends Error { constructor(readonly code: string) { super('database_failure') } }

async function putPart(token: string, part: UploadPart): Promise<boolean> {
  const stored = await rpc(token, 'put_recap_upload_part', {
    p_upload_id: part.id,
    p_part_index: part.index,
    p_part_count: part.total,
    p_content: part.text,
  })
  return stored === true
}

function json(body: unknown, status = 200, extra: Record<string, string> = {}): Response {
  return new Response(JSON.stringify(body), {
    status,
    headers: { 'Content-Type': 'application/json', ...extra },
  })
}

const env = (name: string) => Deno.env.get(name)

Deno.serve(async (req) => {
  const res = await handle(req)
  // Every early exit drains the body: an unread one can stall the reply.
  await discardBody(req)
  return res
})

async function handle(req: Request): Promise<Response> {
  // Only the native app calls this, so no CORS: browsers get no
  // Allow-Origin and cannot read responses. Preflights just end here.
  if (req.method === 'OPTIONS') return new Response(null, { status: 204 })

  if (req.method !== 'POST') return json({ error: 'Method not allowed' }, 405)

  try {
    if (!authClient) {
      console.error('generate-recap: Supabase URL or key env missing')
      return json({ error: 'Server configuration is incomplete' }, 500)
    }

    // verify_jwt = true is not auth: it also admits the publishable key.
    // The user id comes only from the verified token, never the body.
    const userId = await authenticate(req, verifyClaims)
    const token = bearerToken(req)
    if (!userId || !token) return json({ error: 'Unauthorized' }, 401)

    const body = await readJsonBody(req)
    if (!body.ok) {
      const error = body.status === 413 ? 'Request too large' : 'Invalid JSON body'
      return json({ error }, body.status)
    }
    const value = body.value
    const payload = (value && typeof value === 'object' ? value : {}) as Record<string, unknown>
    // Lookup, deletion and withdrawal remain available with generation off.
    if (payload.operation === 'fetch') {
      return json(await rpc(token, 'fetch_recap_jobs', {
        p_session_id: payload.sessionId ?? null, p_cloud_book_id: payload.cloudBookId ?? null,
        p_cursor: payload.cursor ?? 0, p_limit: payload.limit ?? 100,
      }))
    }
    if (payload.operation === 'consent') {
      if (typeof payload.enabled !== 'boolean') return json({ error: 'Invalid consent' }, 400)
      await rpc(token, 'set_recap_consent', { p_enabled: payload.enabled })
      return json({ ok: true })
    }
    if (payload.operation === 'delete') {
      if (typeof payload.sessionId !== 'string') return json({ error: 'Session required' }, 400)
      await rpc(token, 'delete_recap_job', { p_session_id: payload.sessionId })
      return json({ ok: true })
    }
    if (!isEnabled(env) || !loadConfig(env).ok) return json({ error: 'Recaps unavailable' }, 503)
    // Old synchronous clients cannot silently opt into durable storage.
    if (payload.consentVersion !== 2 || typeof payload.sessionId !== 'string') {
      return json({ error: 'New recap consent required' }, 400)
    }
    const upload = parseUploadPart(payload)
    if (upload.kind === 'invalid') return json({ error: 'Invalid upload part' }, 400)
    if (upload.kind === 'part' && upload.part.index < upload.part.total - 1) {
      // Earlier parts are only stored; no quota, no model call.
      if (!(await putPart(token, upload.part))) {
        return json({ error: 'recap upload refused' }, 429, { 'Retry-After': '3600' })
      }
      return json({ stored: upload.part.index }, 202)
    }

    const language = parseLanguage(payload?.language)
    if (!language) return json({ error: 'Unsupported language' }, 422)

    let raw = String(payload?.excerpt ?? '')
    if (upload.kind === 'part') {
      // Joining, deleting staging text and inserting the durable job happen
      // in ONE database transaction; a lost response cannot lose the input.
      raw = upload.part.text
    }
    // bookTitle and chapterTitles are ignored on purpose; see buildMessages.
    // Past the time budget, the most recent text matters most.
    const trimmed = raw.length > MAX_INPUT_CHARS
    const excerpt = trimmed ? raw.slice(-MAX_INPUT_CHARS) : raw
    if (upload.kind !== 'part' && excerpt.trim().length < MIN_EXCERPT_CHARS) {
      // Do not pay for a model call on unusable input.
      return json({ error: 'Excerpt too short to summarise' }, 422)
    }
    const lastSentence = typeof payload?.lastSentence === 'string'
      ? payload.lastSentence.slice(0, MAX_HINT_CHARS)
      : undefined

    const result = await rpc(token, 'submit_recap_job', {
      p_session_id: payload.sessionId, p_cloud_book_id: payload.cloudBookId ?? null,
      p_language: language, p_ended_at: payload.endedAt ?? null,
      p_position: payload.position ?? {}, p_excerpt: excerpt, p_last_sentence: lastSentence ?? null,
      p_upload_id: upload.kind === 'part' ? upload.part.id : null,
      p_part_count: upload.kind === 'part' ? upload.part.total : null,
    })
    if (result?.error === 'conflict') return json({ error: 'conflict' }, 409)
    if (result?.error === 'upload_incomplete') return json({ error: 'upload_incomplete' }, 409)
    if (result?.error === 'storage_limit') return json({ error: 'storage_limit' }, 429, { 'Retry-After': '3600' })
    return json(result, result?.state === 'queued' || result?.state === 'running' ? 202 : 200)
  } catch (e) {
    // Errors only — never the excerpt, prompt or summary.
    console.error('generate-recap: request_failed')
    if (e instanceof RpcError && e.code === '42501') return json({ error: 'Recap access denied' }, 403)
    if (e instanceof RpcError && e.code === '54000') return json({ error: 'Recap deletion limit reached' }, 429, { 'Retry-After': '3600' })
    if (e instanceof RpcError && ['22023', '22P02', '23514'].includes(e.code)) return json({ error: 'Invalid recap input' }, 400)
    return json({ error: 'Recap generation failed' }, 500)
  }
}
