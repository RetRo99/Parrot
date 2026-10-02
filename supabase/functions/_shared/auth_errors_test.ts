import { assertEquals } from 'jsr:@std/assert@1.0.19'
import { classifyAuthError } from './auth_errors.ts'

Deno.test('definite token errors are invalid', () => {
  for (const code of ['bad_jwt', 'no_authorization', 'session_expired', 'session_not_found']) {
    assertEquals(classifyAuthError({ name: 'AuthApiError', status: 403, code }), 'invalid')
  }
  assertEquals(classifyAuthError({ name: 'AuthSessionMissingError', status: 400 }), 'invalid')
  assertEquals(classifyAuthError({ name: 'AuthApiError', status: 401 }), 'invalid')
})

Deno.test('a missing user is deleted', () => {
  const error = { name: 'AuthApiError', status: 403, code: 'user_not_found' }
  assertEquals(classifyAuthError(error), 'deleted')
})

Deno.test('rate limits and outages are unavailable, not 401', () => {
  const cases = [
    { name: 'AuthApiError', status: 429, code: 'over_request_rate_limit' },
    { name: 'AuthApiError', status: 429 },
    { name: 'AuthRetryableFetchError', status: 503 },
    { name: 'AuthRetryableFetchError', status: 0 },
    { name: 'AuthUnknownError', status: undefined },
    { name: 'AuthApiError', status: 400, code: 'something_new' },
  ]
  for (const error of cases) assertEquals(classifyAuthError(error), 'unavailable')
})
