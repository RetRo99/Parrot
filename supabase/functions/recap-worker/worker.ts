export function retrySeconds(value: string | undefined, now = Date.now()): number | null {
  if (!value) return null
  const seconds = /^\d+$/.test(value) ? Number(value) : Math.ceil((Date.parse(value) - now) / 1000)
  return Number.isFinite(seconds) ? Math.max(0, Math.min(86400, seconds)) : null
}

export function classifyOutcome(outcome: { status: number; body: unknown; retryAfter?: string }) {
  const body = outcome.body as { kind?: string; summary?: string }
  const success = outcome.status === 200
  return {
    state: success ? (body.kind === 'not_enough' ? 'not_enough' : 'completed') : 'failed',
    summary: success ? body.summary ?? null : null,
    retry: !success && [429, 502, 503, 504].includes(outcome.status),
    retryAfter: retrySeconds(outcome.retryAfter),
  }
}
