// Auth checks and the resumable deletion sequence for delete-cloud-account.
// No Deno.serve and no env reads, so it can be tested with fakes.

// Signing in again must be this recent to start a deletion.
export const DEFAULT_MAX_AUTH_AGE_SECONDS = 600

// Stable codes the app maps to UI states; never change their meaning.
export type ErrorCode =
  | 'auth_required'
  | 'anonymous_not_allowed'
  | 'reauthentication_required'
  | 'deletion_incomplete'
  | 'server_misconfigured'
  | 'method_not_allowed'

export type AuthUser = { id: string; is_anonymous?: boolean }

// What Auth said about the bearer: getUser checks the signature, expiry,
// and that the session still exists (revoked sessions fail).
export type UserLookup =
  | { kind: 'user'; user: AuthUser }
  | { kind: 'deleted' }
  | { kind: 'invalid' }

export type DeletionDeps = {
  // Throws only on infrastructure failures (e.g. Auth unreachable).
  lookupUser(token: string): Promise<UserLookup>
  // True only for a request this function recorded after a fresh sign-in.
  deletionConfirmed(accountId: string): Promise<boolean>
  // Service role only; clients cannot create the request row themselves.
  requestDeletion(accountId: string): Promise<void>
  deleteObjects(accountId: string): Promise<void>
  redactAudit(accountId: string): Promise<void>
  purgeAudit(): Promise<void>
  // Must treat an already-deleted user as success.
  deleteUser(accountId: string): Promise<void>
  nowSeconds(): number
}

export type Outcome =
  | { status: 200; body: { status: 'deleted' } }
  | { status: 401 | 403; body: { error: string; code: ErrorCode } }

const UUID = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i

const BEARER_JWT = /^Bearer ([A-Za-z0-9_-]+\.[A-Za-z0-9_-]+\.[A-Za-z0-9_-]+)$/i

/** The bearer JWT, or null if the header is missing or not JWT-shaped. */
export function bearerToken(authorization: string | null): string | null {
  return authorization?.trim().match(BEARER_JWT)?.[1] ?? null
}

/** Decodes a JWT payload without verifying it; only call after getUser. */
export function decodePayload(token: string): Record<string, unknown> | null {
  try {
    const part = token.split('.')[1].replace(/-/g, '+').replace(/_/g, '/')
    const padded = part + '='.repeat((4 - (part.length % 4)) % 4)
    const bytes = Uint8Array.from(atob(padded), (c) => c.charCodeAt(0))
    const value = JSON.parse(new TextDecoder().decode(bytes))
    return value && typeof value === 'object' ? value : null
  } catch {
    return null
  }
}

// iat moves on every refresh, so it says nothing about sign-in time. The
// amr timestamps are set when the user authenticated and survive refresh.
export function authenticatedAt(claims: Record<string, unknown>): number | null {
  const amr = claims.amr
  if (!Array.isArray(amr)) return null
  let latest: number | null = null
  for (const entry of amr) {
    const ts = (entry as { timestamp?: unknown })?.timestamp
    if (typeof ts === 'number' && Number.isFinite(ts)) {
      latest = latest === null ? ts : Math.max(latest, ts)
    }
  }
  return latest
}

// New key from the JSON map the runtime injects, else the legacy key, so
// the function works before and after the project drops legacy keys.
export function apiKey(
  env: (name: string) => string | undefined,
  mapName: 'SUPABASE_SECRET_KEYS' | 'SUPABASE_PUBLISHABLE_KEYS',
  legacyName: 'SUPABASE_SERVICE_ROLE_KEY' | 'SUPABASE_ANON_KEY',
): string | undefined {
  try {
    const keys = JSON.parse(env(mapName) ?? '{}')
    if (typeof keys?.default === 'string' && keys.default) return keys.default
  } catch {
    // Fall through to the legacy key.
  }
  return env(legacyName) || undefined
}

/** Parses the max-auth-age setting; bad or missing values use the default. */
export function parseMaxAuthAge(raw: string | undefined): number {
  const n = Number(raw)
  return raw && Number.isInteger(n) && n >= 60 && n <= 86_400 ? n : DEFAULT_MAX_AUTH_AGE_SECONDS
}

function reject(status: 401 | 403, code: ErrorCode, error: string): Outcome {
  return { status, body: { error, code } }
}

/**
 * Verifies the caller and runs request -> storage -> audit -> auth delete.
 * Every step is idempotent, so a retry resumes where a failure stopped.
 * Throws when a step fails; the caller maps that to a retryable 500.
 */
export async function deleteCloudAccount(
  authorization: string | null,
  deps: DeletionDeps,
  maxAuthAgeSeconds = DEFAULT_MAX_AUTH_AGE_SECONDS,
): Promise<Outcome> {
  const token = bearerToken(authorization)
  if (!token) return reject(401, 'auth_required', 'Authentication required')

  const lookup = await deps.lookupUser(token)
  if (lookup.kind === 'deleted') {
    // Auth checked the signature before saying the user is gone, so sub is
    // theirs. Finish cleanup: Storage and audit rows do not cascade.
    const sub = decodePayload(token)?.sub
    if (typeof sub !== 'string' || !UUID.test(sub)) {
      return reject(401, 'auth_required', 'A valid signed-in account is required')
    }
    await deps.deleteObjects(sub)
    await deps.redactAudit(sub)
    return { status: 200, body: { status: 'deleted' } }
  }
  if (lookup.kind === 'invalid') {
    return reject(401, 'auth_required', 'A valid signed-in account is required')
  }

  const { user } = lookup
  if (user.is_anonymous === true) {
    return reject(403, 'anonymous_not_allowed', 'Anonymous accounts cannot be deleted here')
  }
  const claims = decodePayload(token)
  if (!claims || claims.sub !== user.id) {
    return reject(401, 'auth_required', 'A valid signed-in account is required')
  }

  // Only this function confirms a request, after a fresh sign-in, so a
  // retry may finish it with an older session.
  if (!(await deps.deletionConfirmed(user.id))) {
    const authAt = authenticatedAt(claims)
    if (authAt === null || deps.nowSeconds() - authAt > maxAuthAgeSeconds) {
      return reject(403, 'reauthentication_required', 'Sign in again to delete this account')
    }
    await deps.requestDeletion(user.id)
  }

  await deps.deleteObjects(user.id)
  // Keep the approved 180-day evidence window and redact account attribution
  // without replacing operator identities in takedown/block evidence.
  await deps.redactAudit(user.id)
  await deps.purgeAudit()
  await deps.deleteUser(user.id)
  return { status: 200, body: { status: 'deleted' } }
}
