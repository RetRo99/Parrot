create or replace function public.redact_cloud_account_audit_events(account_id uuid)
returns integer
language plpgsql
security definer
set search_path = public
as $$
declare
    redacted_count integer;
    target_account_id uuid := account_id;
begin
    if auth.role() is distinct from 'service_role' then
        raise exception 'Service role required';
    end if;

    update public.cloud_file_audit_events as audit
    set cloud_user_id = null,
        actor = case
            when audit.actor = target_account_id::text then 'deleted_account'
            else audit.actor
        end
    where audit.cloud_user_id = target_account_id;

    get diagnostics redacted_count = row_count;
    return redacted_count;
end;
$$;

revoke all on function public.redact_cloud_account_audit_events(uuid)
    from public, anon, authenticated, service_role;
grant execute on function public.redact_cloud_account_audit_events(uuid) to service_role;
