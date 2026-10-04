// Portable SQL smoke test when Docker/Supabase CLI aren't installed.
// Real PostgreSQL (WASM), pgcrypto and upstream pgmq SQL; minimal Auth/cloud
// fixture and assertion helpers. NOT a substitute for hosted/PostgREST,
// scheduler or multi-connection concurrency integration tests.
import { PGlite } from 'npm:@electric-sql/pglite@0.5.8'
import { pgcrypto } from 'npm:@electric-sql/pglite@0.5.8/contrib/pgcrypto'

Deno.test('durable recap SQL: admission, auth, quota, stale leases, deletion and pagination', async () => {
  const db = new PGlite({ extensions: { pgcrypto } })
  try {
    await db.exec(`
      create role anon; create role authenticated; create role service_role;
      create schema auth; create schema extensions;
      grant usage on schema auth,extensions to authenticated,service_role;
      create extension pgcrypto with schema extensions;
      create table auth.users(id uuid primary key,instance_id uuid,aud text,role text,email text);
      create function auth.jwt() returns jsonb language sql stable as $$
        select coalesce(nullif(current_setting('request.jwt.claims',true),'')::jsonb,'{}'); $$;
      create function auth.uid() returns uuid language sql stable as $$
        select coalesce(nullif(current_setting('request.jwt.claim.sub',true),''),auth.jwt()->>'sub')::uuid; $$;
      create function auth.role() returns text language sql stable as $$
        select coalesce(nullif(current_setting('request.jwt.claim.role',true),''),auth.jwt()->>'role'); $$;
      create table public.cloud_books(id uuid primary key,cloud_user_id uuid references auth.users on delete cascade,title text,deleted_at timestamptz);
      create table public.cloud_account_deletion_requests(cloud_user_id uuid primary key references auth.users on delete cascade);
      create table public.sync_changes(change_id bigserial primary key,cloud_user_id uuid references auth.users on delete cascade,
        entity_type text,entity_id text,operation text,payload jsonb,revision bigint,created_at timestamptz default now());
      create function extensions.no_plan() returns text language sql as $$select 'smoke assertions';$$;
      create function extensions.finish() returns text language sql as $$select 'smoke assertions passed';$$;
      create function extensions.ok(v boolean,m text) returns text language plpgsql as $$
        begin if v is distinct from true then raise exception 'Assertion failed: %',m; end if; return 'ok: '||m; end; $$;
      create function extensions.is(a anyelement,b anyelement,m text) returns text language plpgsql as $$
        begin if a is distinct from b then raise exception 'Assertion failed: % (actual %, expected %)',m,a,b; end if; return 'ok: '||m; end; $$;
      create function extensions.throws_ok(q text,c text,e text,m text) returns text language plpgsql as $$
        declare actual_c text; actual_e text;
        begin
          begin execute q; exception when others then get stacked diagnostics actual_c=returned_sqlstate,actual_e=message_text; end;
          if actual_c is null or actual_c<>c or (e is not null and actual_e<>e) then raise exception 'Assertion failed: % (error %, message %)',m,actual_c,actual_e; end if;
          return 'ok: '||m;
        end; $$;
    `)
    const pgmq = await fetch('https://raw.githubusercontent.com/pgmq/pgmq/v1.5.1/pgmq-extension/sql/pgmq.sql')
    if (!pgmq.ok) throw new Error('pgmq_fixture_unavailable')
    await db.exec(await pgmq.text())
    const read = (name: string) => Deno.readTextFile(new URL(`../migrations/${name}`, import.meta.url))
    await db.exec(await read('20261002000000_parrot_cloud_recap_usage.sql'))
    const hardened = await read('20261003000000_parrot_cloud_security_hardening.sql')
    await db.exec(hardened.slice(hardened.indexOf('create table if not exists public.cloud_feature_allowlist'), hardened.indexOf('-- Lets the client hide')))
    await db.exec(hardened.slice(hardened.indexOf('create table public.recap_settings'), hardened.indexOf('-- 8. push_sync_changes')))
    // Only the staging functions/table, not unrelated retention fixtures.
    const upload = await read('20261004000000_parrot_cloud_recap_uploads.sql')
    await db.exec(upload.slice(0, upload.indexOf('-- Same as 20261003000000')))
    const migration = await read('20261005000000_durable_recaps.sql')
    await db.exec(migration.replace('create extension if not exists pgmq;', ''))
    await db.exec(await read('20261005000100_recap_deletion_fence.sql'))
    await db.exec(await read('20261005000200_recap_deletion_limits.sql'))
    const suite = await Deno.readTextFile(new URL('./durable_recaps_test.sql', import.meta.url))
    const results = await db.exec(suite.replace('create extension if not exists pgtap with schema extensions;', ''))
    const assertions = results.flatMap(r => r.rows).filter(r => Object.values(r).some(v => typeof v === 'string' && v.startsWith('ok:')))
    console.log(`SQL smoke assertions passed: ${assertions.length}`)
  } finally { await db.close() }
})
