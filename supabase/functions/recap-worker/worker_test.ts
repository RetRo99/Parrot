import { assertEquals } from 'jsr:@std/assert@1'
import { classifyOutcome, retrySeconds } from './worker.ts'

Deno.test('completed and not-enough states persist validated provider outcomes', () => {
  assertEquals(classifyOutcome({ status: 200, body: { kind: 'recap', summary: 'Saved.' } }),
    { state: 'completed', summary: 'Saved.', retry: false, retryAfter: null })
  assertEquals(classifyOutcome({ status: 200, body: { kind: 'not_enough', summary: null } }).state, 'not_enough')
})
Deno.test('provider failures are distinct from successful jobs and bounded for retry', () => {
  for (const status of [429,502,503,504]) assertEquals(classifyOutcome({ status, body: {}, retryAfter: '3600' }).retry, true)
  assertEquals(classifyOutcome({ status: 400, body: {} }).retry, false)
})
Deno.test('provider Retry-After honors delta seconds and dates with a day cap', () => {
  assertEquals(retrySeconds('3600'), 3600)
  assertEquals(retrySeconds('9999999'), 86400)
  assertEquals(retrySeconds('bad'), null)
  assertEquals(retrySeconds('2026-10-03T00:01:00Z', Date.parse('2026-10-03T00:00:00Z')),60)
})
