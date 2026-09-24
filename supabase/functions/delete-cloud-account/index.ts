import { createClient } from 'npm:@supabase/supabase-js@2'

const bucketName = 'book-files'
const pageSize = 1000

function jsonResponse(body: unknown, status = 200): Response {
  return new Response(JSON.stringify(body), {
    status,
    headers: { 'Content-Type': 'application/json' },
  })
}

async function listAccountObjects(
  admin: ReturnType<typeof createClient>,
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
  admin: ReturnType<typeof createClient>,
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
    return jsonResponse({ error: 'Method not allowed' }, 405)
  }

  const authorization = request.headers.get('Authorization')
  if (!authorization?.startsWith('Bearer ')) {
    return jsonResponse({ error: 'Authentication required' }, 401)
  }

  const supabaseUrl = Deno.env.get('SUPABASE_URL')
  const anonKey = Deno.env.get('SUPABASE_ANON_KEY')
  const serviceRoleKey = Deno.env.get('SUPABASE_SERVICE_ROLE_KEY')
  if (!supabaseUrl || !anonKey || !serviceRoleKey) {
    return jsonResponse({ error: 'Server configuration is incomplete' }, 500)
  }

  const userClient = createClient(supabaseUrl, anonKey, {
    global: { headers: { Authorization: authorization } },
    auth: { autoRefreshToken: false, persistSession: false },
  })
  const admin = createClient(supabaseUrl, serviceRoleKey, {
    auth: { autoRefreshToken: false, persistSession: false },
  })

  try {
    const { data: userData, error: userError } = await userClient.auth.getUser()
    if (userError || !userData.user) {
      return jsonResponse({ error: 'A valid signed-in account is required' }, 401)
    }
    const accountId = userData.user.id

    const { error: requestError } = await userClient.rpc('request_cloud_account_deletion')
    if (requestError) throw requestError

    await deleteAccountObjects(admin, accountId)

    // Keep the approved 180-day evidence window and redact account attribution
    // without replacing operator identities in takedown/block evidence.
    const { error: redactError } = await admin.rpc('redact_cloud_account_audit_events', {
      account_id: accountId,
    })
    if (redactError) throw redactError

    const { error: purgeError } = await admin.rpc('purge_expired_cloud_file_audit_events')
    if (purgeError) throw purgeError

    const { error: deleteUserError } = await admin.auth.admin.deleteUser(accountId)
    if (deleteUserError) throw deleteUserError

    return jsonResponse({ status: 'deleted' })
  } catch {
    console.error('Cloud account deletion did not complete')
    return jsonResponse({ error: 'Cloud account deletion did not complete; retry to resume cleanup' }, 500)
  }
})
