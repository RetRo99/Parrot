import { assertEquals, assertRejects } from 'jsr:@std/assert@1.0.19'
import {
  apiKey,
  authenticatedAt,
  bearerToken,
  DEFAULT_MAX_AUTH_AGE_SECONDS,
  deleteCloudAccount,
  type DeletionDeps,
  parseMaxAuthAge,
  type UserLookup,
} from './deletion.ts'

const USER_ID = '6f1c2a3b-4d5e-4f60-8a7b-9c0d1e2f3a4b'
const NOW = 1_790_000_000

function b64url(value: unknown): string {
  return btoa(JSON.stringify(value)).replace(/\+/g, '-').replace(/\//g, '_').replace(/=+$/, '')
}

function jwt(claims: Record<string, unknown>): string {
  return `${b64url({ alg: 'ES256' })}.${b64url(claims)}.c2ln`
}

function bearer(authAgeSeconds: number | null, sub = USER_ID): string {
  const amr = authAgeSeconds === null
    ? undefined
    : [{ method: 'password', timestamp: NOW - authAgeSeconds }]
  return `Bearer ${jwt({ sub, iat: NOW, amr })}`
}

type Fake = DeletionDeps & { calls: string[] }

function fake(lookup: UserLookup, opts: { confirmed?: boolean; failAt?: string } = {}): Fake {
  const calls: string[] = []
  const step = (name: string) => {
    calls.push(name)
    if (opts.failAt === name) return Promise.reject(new Error(`${name} failed`))
    return Promise.resolve()
  }
  return {
    calls,
    lookupUser: () => Promise.resolve(lookup),
    deletionConfirmed: () => {
      calls.push('confirmed?')
      return Promise.resolve(opts.confirmed ?? false)
    },
    requestDeletion: (id) => step(`request:${id}`),
    deleteObjects: (id) => step(id === USER_ID ? 'objects' : `objects:${id}`),
    redactAudit: (id) => step(id === USER_ID ? 'redact' : `redact:${id}`),
    purgeAudit: () => step('purge'),
    deleteUser: () => step('deleteUser'),
    nowSeconds: () => NOW,
  }
}

const user: UserLookup = { kind: 'user', user: { id: USER_ID, is_anonymous: false } }
const FULL = ['confirmed?', `request:${USER_ID}`, 'objects', 'redact', 'purge', 'deleteUser']

Deno.test('fresh sign-in runs the whole sequence in order', async () => {
  const deps = fake(user)
  const out = await deleteCloudAccount(bearer(60), deps)
  assertEquals(out, { status: 200, body: { status: 'deleted' } })
  assertEquals(deps.calls, FULL)
})

Deno.test('missing or non-JWT bearer is 401 before any Auth call', async () => {
  for (const header of [null, '', 'Bearer sb_publishable_abc', 'Basic x.y.z']) {
    const deps = fake(user)
    const out = await deleteCloudAccount(header, deps)
    assertEquals(out.status, 401)
    assertEquals(deps.calls, [])
  }
})

Deno.test('revoked or invalid session is 401 auth_required', async () => {
  const deps = fake({ kind: 'invalid' })
  const out = await deleteCloudAccount(bearer(60), deps)
  assertEquals(out, {
    status: 401,
    body: { error: 'A valid signed-in account is required', code: 'auth_required' },
  })
  assertEquals(deps.calls, [])
})

Deno.test('anonymous users are rejected', async () => {
  const deps = fake({ kind: 'user', user: { id: USER_ID, is_anonymous: true } })
  const out = await deleteCloudAccount(bearer(60), deps)
  assertEquals(out.status, 403)
  assertEquals(out.body, {
    error: 'Anonymous accounts cannot be deleted here',
    code: 'anonymous_not_allowed',
  })
  assertEquals(deps.calls, [])
})

Deno.test('token subject must match the Auth user', async () => {
  const deps = fake(user)
  const out = await deleteCloudAccount(bearer(60, 'someone-else'), deps)
  assertEquals(out.status, 401)
  assertEquals(deps.calls, [])
})

Deno.test('stale sign-in needs reauthentication and changes nothing', async () => {
  const deps = fake(user)
  const out = await deleteCloudAccount(bearer(DEFAULT_MAX_AUTH_AGE_SECONDS + 1), deps)
  assertEquals(out, {
    status: 403,
    body: { error: 'Sign in again to delete this account', code: 'reauthentication_required' },
  })
  assertEquals(deps.calls, ['confirmed?'])
})

Deno.test('token without amr timestamps needs reauthentication', async () => {
  const deps = fake(user)
  const out = await deleteCloudAccount(bearer(null), deps)
  assertEquals(out.status, 403)
  assertEquals(deps.calls, ['confirmed?'])
})

Deno.test('custom max auth age is honoured', async () => {
  const deps = fake(user)
  const out = await deleteCloudAccount(bearer(900), deps, 1200)
  assertEquals(out.status, 200)
})

Deno.test('an unconfirmed request row still needs a fresh sign-in', async () => {
  // e.g. a row created through the old client RPC with a stale token.
  const deps = fake(user, { confirmed: false })
  const out = await deleteCloudAccount(bearer(86_400), deps)
  assertEquals(out, {
    status: 403,
    body: { error: 'Sign in again to delete this account', code: 'reauthentication_required' },
  })
  assertEquals(deps.calls, ['confirmed?'])
})

Deno.test('a confirmed request resumes without a fresh sign-in', async () => {
  const deps = fake(user, { confirmed: true })
  const out = await deleteCloudAccount(bearer(86_400), deps)
  assertEquals(out.status, 200)
  assertEquals(deps.calls, ['confirmed?', 'objects', 'redact', 'purge', 'deleteUser'])
})

Deno.test('a failed step throws and a retry resumes', async () => {
  const first = fake(user, { failAt: 'deleteUser' })
  await assertRejects(() => deleteCloudAccount(bearer(60), first), Error, 'deleteUser failed')
  assertEquals(first.calls, FULL)

  // Retry an hour later: the request row exists, so no reauth is needed.
  const retry = fake(user, { confirmed: true })
  const out = await deleteCloudAccount(bearer(3600), retry)
  assertEquals(out.status, 200)
  assertEquals(retry.calls, ['confirmed?', 'objects', 'redact', 'purge', 'deleteUser'])
})

Deno.test('already deleted user still gets storage and audit cleanup', async () => {
  const deps = fake({ kind: 'deleted' })
  const out = await deleteCloudAccount(bearer(86_400), deps)
  assertEquals(out, { status: 200, body: { status: 'deleted' } })
  assertEquals(deps.calls, ['objects', 'redact'])
})

Deno.test('already deleted user: a failed cleanup is retryable', async () => {
  const deps = fake({ kind: 'deleted' }, { failAt: 'objects' })
  await assertRejects(() => deleteCloudAccount(bearer(60), deps), Error, 'objects failed')
})

Deno.test('already deleted user with a non-uuid subject touches nothing', async () => {
  const deps = fake({ kind: 'deleted' })
  const out = await deleteCloudAccount(bearer(60, 'users/../x'), deps)
  assertEquals(out.status, 401)
  assertEquals(deps.calls, [])
})

Deno.test('Auth outages propagate as errors, not 401', async () => {
  const deps = fake(user)
  deps.lookupUser = () => Promise.reject(new Error('auth down'))
  await assertRejects(() => deleteCloudAccount(bearer(60), deps), Error, 'auth down')
})

Deno.test('authenticatedAt takes the latest amr timestamp', () => {
  assertEquals(authenticatedAt({ amr: [{ timestamp: 5 }, { timestamp: 9 }, { x: 1 }] }), 9)
  assertEquals(authenticatedAt({ amr: [] }), null)
  assertEquals(authenticatedAt({ amr: 'password' }), null)
  assertEquals(authenticatedAt({}), null)
})

Deno.test('bearerToken accepts only a JWT-shaped bearer', () => {
  assertEquals(bearerToken('Bearer a.b.c'), 'a.b.c')
  assertEquals(bearerToken('bearer a.b.c '), 'a.b.c')
  assertEquals(bearerToken('Bearer a.b'), null)
  assertEquals(bearerToken(null), null)
})

Deno.test('parseMaxAuthAge clamps to sane values', () => {
  assertEquals(parseMaxAuthAge(undefined), DEFAULT_MAX_AUTH_AGE_SECONDS)
  assertEquals(parseMaxAuthAge('1800'), 1800)
  assertEquals(parseMaxAuthAge('5'), DEFAULT_MAX_AUTH_AGE_SECONDS)
  assertEquals(parseMaxAuthAge('abc'), DEFAULT_MAX_AUTH_AGE_SECONDS)
  assertEquals(parseMaxAuthAge('999999'), DEFAULT_MAX_AUTH_AGE_SECONDS)
})

Deno.test('apiKey prefers the new key map and falls back to legacy', () => {
  const env = (vars: Record<string, string>) => (name: string) => vars[name]
  assertEquals(
    apiKey(
      env({
        SUPABASE_SECRET_KEYS: '{"default":"sb_secret_x"}',
        SUPABASE_SERVICE_ROLE_KEY: 'legacy',
      }),
      'SUPABASE_SECRET_KEYS',
      'SUPABASE_SERVICE_ROLE_KEY',
    ),
    'sb_secret_x',
  )
  assertEquals(
    apiKey(
      env({ SUPABASE_SECRET_KEYS: 'not json', SUPABASE_SERVICE_ROLE_KEY: 'legacy' }),
      'SUPABASE_SECRET_KEYS',
      'SUPABASE_SERVICE_ROLE_KEY',
    ),
    'legacy',
  )
  assertEquals(
    apiKey(
      env({ SUPABASE_PUBLISHABLE_KEYS: '{}' }),
      'SUPABASE_PUBLISHABLE_KEYS',
      'SUPABASE_ANON_KEY',
    ),
    undefined,
  )
})
