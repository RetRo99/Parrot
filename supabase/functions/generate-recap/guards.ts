// Request guards for generate-recap, kept free of Deno.serve and env reads
// so they can be unit tested with an injected verifier.

export type Claims = Record<string, unknown>

// Returns the verified claims, or null when the token is not valid.
// Throws only on infrastructure failures (e.g. Auth unreachable).
export type ClaimsVerifier = (token: string) => Promise<Claims | null>

// Bodies of ~1 MB+ hang or fail at the Edge gateway (measured 2026-10-02),
// so long excerpts arrive as upload parts; clients keep each under 180 KB.
export const MAX_BODY_BYTES = 256 * 1024

// An unread body can stall the response, so early exits drain up to this.
export const MAX_DRAIN_BYTES = 16 * 1024 * 1024

// Upload parts: 64 x 200k chars is ~12M chars, far past the 2M time budget.
export const MAX_UPLOAD_PARTS = 64
export const MAX_PART_CHARS = 200_000

// Exactly one bearer that looks like a JWT (three base64url segments).
// Rejects sb_publishable_/sb_secret_ keys, which have no dots.
const BEARER_JWT = /^Bearer ([A-Za-z0-9_-]+\.[A-Za-z0-9_-]+\.[A-Za-z0-9_-]+)$/i
const UUID = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i

/** The bearer JWT, or null if the header is missing or not JWT-shaped. */
export function bearerToken(req: Request): string | null {
  return req.headers.get('Authorization')?.trim().match(BEARER_JWT)?.[1] ?? null
}

/** Resolves the caller's user id from a verified token, or null (→ 401). */
export async function authenticate(
  req: Request,
  verify: ClaimsVerifier,
): Promise<string | null> {
  const token = bearerToken(req)
  if (!token) return null

  const claims = await verify(token)
  if (!claims) return null

  // The anon key and service JWTs carry other roles; anonymous sign-ins
  // are 'authenticated' but flagged, and must not get free model calls.
  const aud = claims.aud
  const audOk = aud === 'authenticated' ||
    (Array.isArray(aud) && aud.includes('authenticated'))
  if (claims.role !== 'authenticated' || !audOk) return null
  if (claims.is_anonymous === true) return null
  if (typeof claims.sub !== 'string' || !UUID.test(claims.sub)) return null
  return claims.sub
}

export type BodyResult =
  | { ok: true; value: unknown }
  | { ok: false; status: 400 | 413 }

async function drain(
  reader: ReadableStreamDefaultReader<Uint8Array>,
  maxBytes: number,
): Promise<void> {
  try {
    let size = 0
    while (size <= maxBytes) {
      const { done, value } = await reader.read()
      if (done) return
      size += value.byteLength
    }
    await reader.cancel()
  } catch {
    // The client went away; nothing to answer.
  }
}

/** Reads and drops an unread body (up to maxBytes) before an early reply. */
export async function discardBody(req: Request, maxBytes = MAX_DRAIN_BYTES): Promise<void> {
  if (!req.body || req.bodyUsed) return
  await drain(req.body.getReader(), maxBytes)
}

/** Reads and parses a JSON body, never buffering more than maxBytes. */
export async function readJsonBody(
  req: Request,
  maxBytes = MAX_BODY_BYTES,
): Promise<BodyResult> {
  if (!req.body) return { ok: false, status: 400 }
  const reader = req.body.getReader()
  const declared = Number(req.headers.get('Content-Length') ?? NaN)
  if (Number.isFinite(declared) && declared > maxBytes) {
    await drain(reader, MAX_DRAIN_BYTES)
    return { ok: false, status: 413 }
  }

  // Content-Length can be absent (chunked) or wrong, so count while reading.
  const chunks: Uint8Array[] = []
  let size = 0
  while (true) {
    const { done, value } = await reader.read()
    if (done) break
    size += value.byteLength
    if (size > maxBytes) {
      await drain(reader, MAX_DRAIN_BYTES - size)
      return { ok: false, status: 413 }
    }
    chunks.push(value)
  }

  const bytes = new Uint8Array(size)
  let offset = 0
  for (const chunk of chunks) {
    bytes.set(chunk, offset)
    offset += chunk.byteLength
  }
  try {
    return { ok: true, value: JSON.parse(new TextDecoder().decode(bytes)) }
  } catch {
    return { ok: false, status: 400 }
  }
}

export type UploadPart = { id: string; index: number; total: number; text: string }

export type UploadResult =
  | { kind: 'none' }
  | { kind: 'invalid' }
  | { kind: 'part'; part: UploadPart }

const isInt = (v: unknown): v is number => typeof v === 'number' && Number.isInteger(v)

/**
 * A long excerpt arrives as { upload: { id, index, total }, text } parts.
 * The last (index total - 1) is sent after the others, carries language
 * and lastSentence, and triggers generation. { excerpt } needs no upload.
 */
export function parseUploadPart(payload: Record<string, unknown>): UploadResult {
  if (!('upload' in payload)) return { kind: 'none' }
  const u = payload.upload as Record<string, unknown> | null
  const text = payload.text
  if (
    !u || typeof u !== 'object' || typeof u.id !== 'string' || !UUID.test(u.id) ||
    !isInt(u.total) || u.total < 2 || u.total > MAX_UPLOAD_PARTS ||
    !isInt(u.index) || u.index < 0 || u.index >= u.total ||
    typeof text !== 'string' || text.length < 1 || text.length > MAX_PART_CHARS ||
    'excerpt' in payload
  ) {
    return { kind: 'invalid' }
  }
  return { kind: 'part', part: { id: u.id.toLowerCase(), index: u.index, total: u.total, text } }
}
