// Sorts an Auth getUser(jwt) error so outages are not reported as 401.
// No imports, so both functions and their tests can use it directly.

// What the error says about the bearer token.
export type AuthErrorKind =
  // The token is bad, expired, or its session was signed out.
  | 'invalid'
  // The signature checked out but the user no longer exists.
  | 'deleted'
  // Rate limit, outage, or anything unexpected: retry, never sign out.
  | 'unavailable'

export type AuthErrorLike = { name?: string; status?: number; code?: string }

const INVALID_CODES = new Set([
  'bad_jwt',
  'no_authorization',
  'session_expired',
  'session_not_found',
])

export function classifyAuthError(error: AuthErrorLike): AuthErrorKind {
  // supabase-js turns session_not_found into this error, without a code.
  if (error.name === 'AuthSessionMissingError') return 'invalid'
  if (error.name === 'AuthRetryableFetchError') return 'unavailable'
  if (error.code === 'user_not_found') return 'deleted'
  if (error.code !== undefined) {
    return INVALID_CODES.has(error.code) ? 'invalid' : 'unavailable'
  }
  // Older Auth servers send no code; 401 still means a bad token.
  return error.status === 401 ? 'invalid' : 'unavailable'
}
