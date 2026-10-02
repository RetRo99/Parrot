// Request guards for generate-recap, kept free of Deno.serve and env reads
// so they can be unit tested with an injected verifier.

export type Claims = Record<string, unknown>

// Returns the verified claims, or null when the token is not valid.
// Throws only on infrastructure failures (e.g. Auth unreachable).
export type ClaimsVerifier = (token: string) => Promise<Claims | null>

// Above the current contract (8k-char excerpt + hint) even as 3-byte UTF-8.
export const MAX_BODY_BYTES = 64 * 1024

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

/** Reads and parses a JSON body, never buffering more than maxBytes. */
export async function readJsonBody(
  req: Request,
  maxBytes = MAX_BODY_BYTES,
): Promise<BodyResult> {
  const declared = Number(req.headers.get('Content-Length') ?? NaN)
  if (Number.isFinite(declared) && declared > maxBytes) {
    return { ok: false, status: 413 }
  }
  if (!req.body) return { ok: false, status: 400 }

  // Content-Length can be absent (chunked) or wrong, so count while reading.
  const reader = req.body.getReader()
  const chunks: Uint8Array[] = []
  let size = 0
  while (true) {
    const { done, value } = await reader.read()
    if (done) break
    size += value.byteLength
    if (size > maxBytes) {
      await reader.cancel()
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
