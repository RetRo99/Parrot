-- pgTAP lives in the extensions schema. Test sessions started by the Supabase
-- CLI (linked-project runs) authenticate as a CLI-managed login role that has
-- no USAGE on schema extensions, so every pgTAP file failed to resolve
-- plan()/is()/ok() ("function plan(integer) does not exist"). Granting USAGE
-- to PUBLIC makes the assertion functions resolvable for any test role.

do $$
begin
    execute 'grant usage on schema extensions to public';
exception when insufficient_privilege then
    raise notice 'grant usage on schema extensions skipped: insufficient privilege';
end $$;
