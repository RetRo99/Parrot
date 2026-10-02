// generate-recap — Supabase Edge Function
//
// Turns a reading-session excerpt into a 2-3 sentence "welcome back" recap.
// This function is a thin, auditable proxy: it never persists the excerpt,
// never logs content, and hard-caps output tokens so the cost per call is
// bounded. See docs/reading-session-recap-implementation-plan.md §7.
//
// Provider: OpenCode Go (OpenAI-compatible /chat/completions) at a fixed
// URL with a server-side model allow-list; see recap.ts. Secrets and
// deploy commands are in README.md next to this file.
//
// Request:  { excerpt: string, language?: "en" | "sl" | …, lastSentence? }
// Response: { kind: "recap" | "not_enough", summary: string | null }
//
// Callers must be signed-in, non-anonymous users; see guards.ts. Each call
// consumes one unit of a per-user daily quota (consume_recap_quota RPC).

import { createClient, isAuthRetryableFetchError } from 'npm:@supabase/supabase-js@2'
import { authenticate, bearerToken, type Claims, readJsonBody } from './guards.ts'
import {
  buildMessages,
  isEnabled,
  loadConfig,
  MAX_EXCERPT_CHARS,
  MAX_HINT_CHARS,
  MIN_EXCERPT_CHARS,
  parseLanguage,
  requestRecap,
  sessionId,
} from './recap.ts'

const corsHeaders = {
  'Access-Control-Allow-Origin': '*',
  'Access-Control-Allow-Headers': 'authorization, x-client-info, apikey, content-type',
  'Access-Control-Expose-Headers': 'retry-after',
}

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
  return data.claims as Claims
}

// Runs as the caller (their JWT), so the RPC's auth.uid() is the user.
async function consumeQuota(token: string, limit: number): Promise<boolean> {
  const userClient = createClient(SUPABASE_URL!, CLIENT_KEY!, {
    auth: { autoRefreshToken: false, persistSession: false },
    global: { headers: { Authorization: `Bearer ${token}` } },
  })
  const { data, error } = await userClient.rpc('consume_recap_quota', { p_limit: limit })
  if (error) throw new Error(`quota rpc failed: ${error.code ?? 'unknown'}`)
  return data === true
}

function secondsToUtcMidnight(now: Date): number {
  const next = Date.UTC(now.getUTCFullYear(), now.getUTCMonth(), now.getUTCDate() + 1)
  return Math.max(1, Math.ceil((next - now.getTime()) / 1000))
}

function json(body: unknown, status = 200, extra: Record<string, string> = {}): Response {
  return new Response(JSON.stringify(body), {
    status,
    headers: { ...corsHeaders, 'Content-Type': 'application/json', ...extra },
  })
}

const env = (name: string) => Deno.env.get(name)

Deno.serve(async (req) => {
  if (req.method === 'OPTIONS') return new Response('ok', { headers: corsHeaders })

  if (req.method !== 'POST') return json({ error: 'Method not allowed' }, 405)

  try {
    // Kill switch first: off means no Auth calls and no provider calls.
    if (!isEnabled(env)) return json({ error: 'Recaps are disabled' }, 503)

    if (!authClient) {
      console.error('generate-recap: Supabase URL or key env missing')
      return json({ error: 'Server configuration is incomplete' }, 500)
    }

    const cfg = loadConfig(env)
    if (!cfg.ok) {
      console.error(`generate-recap: not configured (${cfg.reason})`)
      return json({ error: 'Recap provider not configured' }, 503)
    }
    const { apiKey, model, dailyLimit } = cfg.config

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
    const payload = (body.value ?? {}) as Record<string, unknown>
    // bookTitle and chapterTitles are ignored on purpose; see buildMessages.
    const excerpt = String(payload?.excerpt ?? '').slice(0, MAX_EXCERPT_CHARS)
    if (excerpt.trim().length < MIN_EXCERPT_CHARS) {
      // Do not pay for a model call on unusable input.
      return json({ error: 'Excerpt too short to summarise' }, 422)
    }
    const language = parseLanguage(payload?.language)
    if (!language) return json({ error: 'Unsupported language' }, 422)
    const lastSentence = typeof payload?.lastSentence === 'string'
      ? payload.lastSentence.slice(0, MAX_HINT_CHARS)
      : undefined

    // Consumed before the call and not refunded, so failures still count.
    const now = new Date()
    if (!(await consumeQuota(token, dailyLimit))) {
      return json({ error: 'daily recap limit reached' }, 429, {
        'Retry-After': String(secondsToUtcMidnight(now)),
      })
    }

    const outcome = await requestRecap({ fetch }, {
      apiKey,
      model,
      sessionId: await sessionId(userId, now),
      messages: buildMessages({ excerpt, lastSentence, language }),
    })
    // Metadata only — never the excerpt, prompt, summary or key.
    console.log(JSON.stringify({ ev: 'recap', status: outcome.status, model, ...outcome.log }))

    if (outcome.status === 200) return json(outcome.body)
    const extra: Record<string, string> = 'retryAfter' in outcome && outcome.retryAfter
      ? { 'Retry-After': outcome.retryAfter }
      : {}
    return json(outcome.body, outcome.status, extra)
  } catch (e) {
    // Errors only — never the excerpt, prompt or summary.
    console.error('generate-recap failed:', e instanceof Error ? e.message : e)
    return json({ error: 'Recap generation failed' }, 500)
  }
})
