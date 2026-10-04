import { createClient } from 'npm:@supabase/supabase-js@2.117.2'
import { generateRecap, isEnabled, loadConfig } from '../generate-recap/recap.ts'
import { readJsonBody } from '../generate-recap/guards.ts'
import { classifyOutcome } from './worker.ts'

const env = (name: string) => Deno.env.get(name)
function adminKey(): string | undefined {
  try { return JSON.parse(env('SUPABASE_SECRET_KEYS') ?? '{}').default ?? env('SUPABASE_SERVICE_ROLE_KEY') }
  catch { return env('SUPABASE_SERVICE_ROLE_KEY') }
}

// This endpoint is not a user endpoint. No publishable key or user JWT
// authorizes processing. Compare fixed-size hashes without logging headers.
async function authorized(req: Request): Promise<boolean> {
  const expected = env('RECAP_WORKER_SECRET')
  const received = req.headers.get('X-Recap-Worker')
  if (!expected || expected.length < 32 || !received || received.length > 512) return false
  const digest = async (v: string) => new Uint8Array(await crypto.subtle.digest('SHA-256', new TextEncoder().encode(v)))
  const [a, b] = await Promise.all([digest(expected), digest(received)])
  let difference = 0
  for (let i = 0; i < a.length; i++) difference |= a[i] ^ b[i]
  return difference === 0
}

Deno.serve(async req => {
  if (req.method !== 'POST' || !await authorized(req)) return new Response(null, { status: 401 })
  const key = adminKey(), url = env('SUPABASE_URL')
  if (!key || !url) return new Response(null, { status: 503 })
  const admin = createClient(url, key, { auth: { autoRefreshToken: false, persistSession: false } })
  const rpc = async (name: string, args: Record<string, unknown> = {}) => {
    const { data, error } = await admin.rpc(name, args)
    if (error) throw new Error('database_failure')
    return data
  }
  try {
    const body = await readJsonBody(req, 1024)
    if (!body.ok) return new Response(null, { status: body.status })
    if ((body.value as Record<string, unknown>)?.mode === 'cleanup') {
      // Cleanup/withdrawal must work even with the provider kill switch off.
      await rpc('cleanup_recap_jobs')
      await rpc('purge_cloud_retention', { retain_days: 90 })
      return Response.json({ ok: true })
    }
    if (!isEnabled(env)) return new Response(null, { status: 503 })
    const config = loadConfig(env)
    if (!config.ok) return new Response(null, { status: 503 })
    const job = await rpc('claim_recap_job', { p_limit: config.config.dailyLimit })
    if (!job) return Response.json({ processed: 0 })
    const outcome = await generateRecap({ fetch, deadlineMs: 120_000 }, {
      apiKey: config.config.apiKey, model: config.config.model,
      sessionId: `recap-${job.id}`, excerpt: job.excerpt,
      lastSentence: job.lastSentence ?? undefined, language: job.language,
    })
    const result = classifyOutcome(outcome)
    const stored = await rpc('finish_recap_job', {
      p_id: job.id, p_lease: job.lease, p_message_id: job.messageId,
      p_state: result.state, p_summary: result.summary,
      p_model: config.config.model, p_retry: result.retry, p_retry_after: result.retryAfter,
    })
    // No content or raw error objects in logs or pg_net's retained responses.
    console.log(JSON.stringify({ ev: 'recap_worker', status: outcome.status, stored }))
    return Response.json({ processed: stored ? 1 : 0 })
  } catch {
    console.error('recap_worker: processing_failed')
    // Leave the message for lease expiry; never acknowledge unsaved results.
    return new Response(null, { status: 500 })
  }
})
