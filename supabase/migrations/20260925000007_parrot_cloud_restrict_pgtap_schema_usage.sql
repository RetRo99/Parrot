-- The preceding pgTAP compatibility migration granted extensions-schema
-- access to PUBLIC. Keep the test runner working without extending that
-- privilege to application roles (PUBLIC includes anon and authenticated).

revoke usage on schema extensions from public;

do $$
begin
    if exists (select 1 from pg_roles where rolname = 'postgres') then
        grant usage on schema extensions to postgres;
    end if;
    if exists (select 1 from pg_roles where rolname = 'supabase_admin') then
        grant usage on schema extensions to supabase_admin;
    end if;
end $$;
