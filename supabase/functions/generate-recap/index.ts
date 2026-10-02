// generate-recap — Supabase Edge Function
//
// Turns a reading-session excerpt into a 2-3 sentence "welcome back" recap.
// This function is a thin, auditable proxy: it never persists the excerpt,
// never logs content, and hard-caps output tokens so the cost per call is
// bounded. See docs/reading-session-recap-implementation-plan.md §7.
//
// Provider is selected with RECAP_PROVIDER:
//   openai (default) — any OpenAI-compatible /chat/completions endpoint
//   gemini           — Google Gemini generateContent
//
// Secrets (supabase secrets set):
//   RECAP_API_KEY    required
//   RECAP_MODEL      optional, defaults per provider
//   RECAP_BASE_URL   optional, for openai provider (default api.openai.com)
//   RECAP_PROVIDER   optional, "openai" | "gemini"
//
// Callers must be signed-in, non-anonymous users; see guards.ts.

import { createClient, isAuthRetryableFetchError } from 'npm:@supabase/supabase-js@2'
import { authenticate, type Claims, readJsonBody } from './guards.ts'

const MAX_EXCERPT_CHARS = 8_000
const MIN_EXCERPT_CHARS = 80
const MAX_OUTPUT_TOKENS = 160
const TEMPERATURE = 0.4

const PROVIDER = Deno.env.get('RECAP_PROVIDER') ?? 'openai'
const API_KEY = Deno.env.get('RECAP_API_KEY')!
const BASE_URL = Deno.env.get('RECAP_BASE_URL') ?? 'https://api.openai.com/v1'
const MODEL = Deno.env.get('RECAP_MODEL') ??
  (PROVIDER === 'gemini' ? 'gemini-3.1-flash-lite' : 'gpt-5-nano')

const corsHeaders = {
  'Access-Control-Allow-Origin': '*',
  'Access-Control-Allow-Headers': 'authorization, x-client-info, apikey, content-type',
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

function json(body: unknown, status = 200): Response {
  return new Response(JSON.stringify(body), {
    status,
    headers: { ...corsHeaders, 'Content-Type': 'application/json' },
  })
}

Deno.serve(async (req) => {
  if (req.method === 'OPTIONS') return new Response('ok', { headers: corsHeaders })

  if (req.method !== 'POST') return json({ error: 'Method not allowed' }, 405)

  try {
    if (!authClient) {
      console.error('generate-recap: Supabase URL or key env missing')
      return json({ error: 'Server configuration is incomplete' }, 500)
    }

    // verify_jwt = true is not auth: it also admits the publishable key.
    // The user id comes only from the verified token, never the body.
    const userId = await authenticate(req, verifyClaims)
    if (!userId) return json({ error: 'Unauthorized' }, 401)

    const body = await readJsonBody(req)
    if (!body.ok) {
      const error = body.status === 413 ? 'Request too large' : 'Invalid JSON body'
      return json({ error }, body.status)
    }
    const payload = (body.value ?? {}) as Record<string, unknown>
    const excerpt = String(payload?.excerpt ?? '').slice(0, MAX_EXCERPT_CHARS)
    if (excerpt.trim().length < MIN_EXCERPT_CHARS) {
      // Do not pay for a model call on unusable input.
      return json({ error: 'Excerpt too short to summarise' }, 422)
    }

    const prompt = buildPrompt(payload, excerpt)
    const summary = (await generate(prompt)).trim()
    if (!summary) return json({ error: 'Empty recap generated' }, 502)

    return json({ summary })
  } catch (e) {
    // Errors only — never the excerpt, prompt or summary.
    console.error('generate-recap failed:', e instanceof Error ? e.message : e)
    return json({ error: 'Recap generation failed' }, 500)
  }
})

function buildPrompt(payload: any, excerpt: string): string {
  const chapters: string[] = Array.isArray(payload?.chapterTitles) ? payload.chapterTitles : []
  return [
    'You are helping a reader resume a book they were reading.',
    'Summarise ONLY the passage below in 2-3 sentences of plain prose.',
    'Write in present tense, second person ("you").',
    'No headings, no bullet points, no preamble such as "In this passage".',
    'Do not invent anything that is not in the passage.',
    'If the passage is too fragmentary to summarise, say so in one short sentence.',
    '',
    `Book: ${String(payload?.bookTitle ?? 'Untitled')}`,
    chapters.length ? `Chapters read: ${chapters.join(', ')}` : '',
    payload?.lastSentence ? `The reader stopped at: "${payload.lastSentence}"` : '',
    '',
    'Passage:',
    excerpt,
  ].filter(Boolean).join('\n')
}

async function generate(prompt: string): Promise<string> {
  return PROVIDER === 'gemini' ? generateGemini(prompt) : generateOpenAi(prompt)
}

async function generateOpenAi(prompt: string): Promise<string> {
  const res = await fetch(`${BASE_URL}/chat/completions`, {
    method: 'POST',
    headers: {
      'Content-Type': 'application/json',
      Authorization: `Bearer ${API_KEY}`,
    },
    body: JSON.stringify({
      model: MODEL,
      messages: [{ role: 'user', content: prompt }],
      max_tokens: MAX_OUTPUT_TOKENS,
      temperature: TEMPERATURE,
    }),
  })
  if (!res.ok) throw new Error(`LLM ${res.status}: ${(await res.text()).slice(0, 500)}`)
  const data = await res.json()
  return data?.choices?.[0]?.message?.content ?? ''
}

async function generateGemini(prompt: string): Promise<string> {
  const res = await fetch(
    `https://generativelanguage.googleapis.com/v1beta/models/${MODEL}:generateContent`,
    {
      method: 'POST',
      headers: {
        'Content-Type': 'application/json',
        'x-goog-api-key': API_KEY,
      },
      body: JSON.stringify({
        contents: [{ role: 'user', parts: [{ text: prompt }] }],
        generationConfig: {
          maxOutputTokens: MAX_OUTPUT_TOKENS,
          temperature: TEMPERATURE,
        },
      }),
    },
  )
  if (!res.ok) throw new Error(`LLM ${res.status}: ${(await res.text()).slice(0, 500)}`)
  const data = await res.json()
  return data?.candidates?.[0]?.content?.parts?.[0]?.text ?? ''
}
