import {
  createClient,
  isAuthApiError,
  type SupabaseClient,
} from 'npm:@supabase/supabase-js@2.117.2'
import { classifyAuthError } from '../_shared/auth_errors.ts'
import {
  apiKey,
  deleteCloudAccount,
  type DeletionDeps,
  type ErrorCode,
  parseMaxAuthAge,
  type UserLookup,
} from './deletion.ts'

const bucketName = 'book-files'
const pageSize = 1000

function jsonResponse(body: unknown, status = 200): Response {
  return new Response(JSON.stringify(body), {
    status,
    headers: { 'Content-Type': 'application/json' },
  })
}

function errorResponse(status: number, code: ErrorCode, error: string): Response {
  return jsonResponse({ error, code }, status)
}

// 403 user_not_found only comes after Auth verified the token signature.
// Rate limits and outages throw (retryable 500), never a 401.
async function lookupUser(admin: SupabaseClient, token: string): Promise<UserLookup> {
  const { data, error } = await admin.auth.getUser(token)
  if (error) {
    const kind = classifyAuthError(error)
    if (kind === 'unavailable') throw error
    return { kind }
  }
  if (!data.user) return { kind: 'invalid' }
  return { kind: 'user', user: { id: data.user.id, is_anonymous: data.user.is_anonymous } }
}

async function listAccountObjects(
  admin: SupabaseClient,
  accountId: string,
): Promise<string[]> {
  const root = `users/${accountId}`
  const folders = [root]
  const objectPaths: string[] = []

  while (folders.length > 0) {
    const folder = folders.pop()!
    if (folder !== root && !folder.startsWith(`${root}/`)) {
      throw new Error('Storage listing escaped the account prefix')
    }

    let offset = 0
    while (true) {
      const { data, error } = await admin.storage
        .from(bucketName)
        .list(folder, { limit: pageSize, offset })
      if (error) throw error

      for (const entry of data ?? []) {
        const path = `${folder}/${entry.name}`
        if (!entry.metadata) {
          folders.push(path)
        } else {
          if (!path.startsWith(`${root}/`)) {
            throw new Error('Storage object escaped the account prefix')
          }
          objectPaths.push(path)
        }
      }

      if ((data?.length ?? 0) < pageSize) break
      offset += pageSize
    }
  }

  return objectPaths
}

async function deleteAccountObjects(
  admin: SupabaseClient,
  accountId: string,
): Promise<void> {
  const paths = await listAccountObjects(admin, accountId)
  for (let offset = 0; offset < paths.length; offset += pageSize) {
    const { error } = await admin.storage
      .from(bucketName)
      .remove(paths.slice(offset, offset + pageSize))
    if (error) throw error
  }

  // Re-list after removal. The database write guard prevents the user from
  // adding new objects while cleanup runs, and this catches partial failures.
  const remaining = await listAccountObjects(admin, accountId)
  if (remaining.length > 0) {
    throw new Error(`Storage cleanup left ${remaining.length} objects`)
  }
}

Deno.serve(async (request: Request) => {
  if (request.method !== 'POST') {
    return errorResponse(405, 'method_not_allowed', 'Method not allowed')
  }

  const env = (name: string) => Deno.env.get(name)
  const supabaseUrl = env('SUPABASE_URL')
  const secretKey = apiKey(env, 'SUPABASE_SECRET_KEYS', 'SUPABASE_SERVICE_ROLE_KEY')
  if (!supabaseUrl || !secretKey) {
    return errorResponse(500, 'server_misconfigured', 'Server configuration is incomplete')
  }

  const clientOptions = { auth: { autoRefreshToken: false, persistSession: false } }
  const admin = createClient(supabaseUrl, secretKey, clientOptions)

  const deps: DeletionDeps = {
    lookupUser: (token) => lookupUser(admin, token),
    async deletionConfirmed(accountId) {
      const { data, error } = await admin
        .from('cloud_account_deletion_requests')
        .select('confirmed_at')
        .eq('cloud_user_id', accountId)
        .maybeSingle()
      if (error) throw error
      return data?.confirmed_at != null
    },
    async requestDeletion(accountId) {
      const { error } = await admin.rpc('request_cloud_account_deletion_for', {
        account_id: accountId,
      })
      if (error) throw error
    },
    deleteObjects: (accountId) => deleteAccountObjects(admin, accountId),
    async redactAudit(accountId) {
      const { error } = await admin.rpc('redact_cloud_account_audit_events', {
        account_id: accountId,
      })
      if (error) throw error
    },
    async purgeAudit() {
      const { error } = await admin.rpc('purge_expired_cloud_file_audit_events')
      if (error) throw error
    },
    async deleteUser(accountId) {
      const { error } = await admin.auth.admin.deleteUser(accountId)
      // A concurrent retry may have deleted the user first.
      if (error && !(isAuthApiError(error) && error.code === 'user_not_found')) throw error
    },
    nowSeconds: () => Math.floor(Date.now() / 1000),
  }

  try {
    const outcome = await deleteCloudAccount(
      request.headers.get('Authorization'),
      deps,
      parseMaxAuthAge(env('DELETE_ACCOUNT_MAX_AUTH_AGE_SECONDS')),
    )
    return jsonResponse(outcome.body, outcome.status)
  } catch (error) {
    // Log only the error name: never paths, titles, hashes, tokens, or
    // URLs (PC-plan §12).
    const detail = error instanceof Error ? error.name : 'unknown'
    console.error(`Cloud account deletion did not complete (${detail})`)
    return jsonResponse({
      error: 'Cloud account deletion did not complete; retry to resume cleanup',
      code: 'deletion_incomplete' satisfies ErrorCode,
      error_name: detail,
    }, 500)
  }
})
