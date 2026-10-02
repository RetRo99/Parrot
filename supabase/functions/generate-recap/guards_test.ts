import { assertEquals, assertRejects } from 'jsr:@std/assert@1'
import { authenticate, type Claims, type ClaimsVerifier, readJsonBody } from './guards.ts'

const USER_ID = '6f1c2a3b-4d5e-4f60-8a7b-9c0d1e2f3a4b'
const JWT = 'eyJhbGciOiJFUzI1NiJ9.eyJzdWIiOiJ4In0.c2ln'

const userClaims: Claims = {
  sub: USER_ID,
  role: 'authenticated',
  aud: 'authenticated',
  is_anonymous: false,
}

function req(authorization?: string, init: RequestInit = {}): Request {
  const headers = new Headers(init.headers)
  if (authorization !== undefined) headers.set('Authorization', authorization)
  return new Request('http://localhost/generate-recap', {
    method: 'POST',
    ...init,
    headers,
  })
}

// Records whether the verifier ran, so we can prove early rejection.
function verifier(claims: Claims | null) {
  const calls: string[] = []
  const fn: ClaimsVerifier = (token) => {
    calls.push(token)
    return Promise.resolve(claims)
  }
  return { fn, calls }
}

Deno.test('accepts a verified, signed-in user and returns its sub', async () => {
  const v = verifier(userClaims)
  assertEquals(await authenticate(req(`Bearer ${JWT}`), v.fn), USER_ID)
  assertEquals(v.calls, [JWT])
})

Deno.test('accepts a lowercase bearer scheme and array aud', async () => {
  const v = verifier({ ...userClaims, aud: ['authenticated'] })
  assertEquals(await authenticate(req(`bearer ${JWT}`), v.fn), USER_ID)
})

Deno.test('rejects malformed or non-JWT bearers without verifying', async () => {
  const bad = [
    undefined,
    '',
    'Bearer',
    'Bearer ',
    JWT,
    `Basic ${JWT}`,
    'Bearer x',
    'Bearer sb_publishable_abc123',
    'Bearer sb_secret_abc123',
    'Bearer a.b',
    'Bearer a.b.c.d',
    `Bearer ${JWT} extra`,
    `Bearer ${JWT}, Bearer ${JWT}`,
  ]
  for (const header of bad) {
    const v = verifier(userClaims)
    assertEquals(await authenticate(req(header), v.fn), null, String(header))
    assertEquals(v.calls.length, 0, String(header))
  }
})

Deno.test('rejects tokens the verifier does not accept', async () => {
  assertEquals(await authenticate(req(`Bearer ${JWT}`), verifier(null).fn), null)
})

Deno.test('rejects the anon-role and service-role JWTs', async () => {
  for (const role of ['anon', 'service_role']) {
    const v = verifier({ iss: 'supabase', role })
    assertEquals(await authenticate(req(`Bearer ${JWT}`), v.fn), null, role)
  }
})

Deno.test('rejects anonymous users', async () => {
  const v = verifier({ ...userClaims, is_anonymous: true })
  assertEquals(await authenticate(req(`Bearer ${JWT}`), v.fn), null)
})

Deno.test('rejects wrong audience, missing or non-UUID sub', async () => {
  const cases: Claims[] = [
    { ...userClaims, aud: 'other' },
    { ...userClaims, aud: undefined },
    { ...userClaims, sub: undefined },
    { ...userClaims, sub: 'not-a-uuid' },
  ]
  for (const claims of cases) {
    const v = verifier(claims)
    assertEquals(await authenticate(req(`Bearer ${JWT}`), v.fn), null)
  }
})

Deno.test('propagates verifier infrastructure errors', async () => {
  const failing: ClaimsVerifier = () => Promise.reject(new Error('auth down'))
  await assertRejects(() => authenticate(req(`Bearer ${JWT}`), failing))
})

Deno.test('parses a JSON body under the cap', async () => {
  const r = req(undefined, { body: JSON.stringify({ excerpt: 'hi' }) })
  assertEquals(await readJsonBody(r, 1024), { ok: true, value: { excerpt: 'hi' } })
})

Deno.test('rejects malformed JSON with 400', async () => {
  assertEquals(await readJsonBody(req(undefined, { body: '{nope' }), 1024), {
    ok: false,
    status: 400,
  })
  assertEquals(await readJsonBody(req(undefined), 1024), { ok: false, status: 400 })
})

Deno.test('rejects oversized declared Content-Length with 413', async () => {
  const r = req(undefined, {
    body: '{}',
    headers: { 'Content-Length': '2048' },
  })
  assertEquals(await readJsonBody(r, 1024), { ok: false, status: 413 })
})

Deno.test('rejects oversized streamed body with 413', async () => {
  // No Content-Length on a stream body, so the cap must hold while reading.
  const big = new TextEncoder().encode(`"${'a'.repeat(4096)}"`)
  const stream = new ReadableStream<Uint8Array>({
    start(c) {
      for (let i = 0; i < big.length; i += 512) c.enqueue(big.slice(i, i + 512))
      c.close()
    },
  })
  const r = req(undefined, { body: stream })
  assertEquals(await readJsonBody(r, 1024), { ok: false, status: 413 })
})
