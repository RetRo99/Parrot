-- Only delete-cloud-account records a deletion, after its fresh sign-in
-- check. A row clients could create let a stale token skip that check.
alter table public.cloud_account_deletion_requests
    add column if not exists confirmed_at timestamptz;

-- Rows from the old client RPC stay null, so they still need a fresh
-- sign-in before the function resumes them.
comment on column public.cloud_account_deletion_requests.confirmed_at is
    'Set only by request_cloud_account_deletion_for after a fresh sign-in';

revoke execute on function public.request_cloud_account_deletion()
    from public, anon, authenticated;

create or replace function public.request_cloud_account_deletion_for(account_id uuid)
returns jsonb
language plpgsql
security definer
set search_path = public
as $$
declare
    target_account_id uuid := account_id;
    requested_at_value timestamptz;
begin
    if auth.role() is distinct from 'service_role' then
        raise exception 'Service role required';
    end if;
    if target_account_id is null then
        raise exception 'Account id required';
    end if;

    -- Same lock as the write guard and sync/upload RPCs.
    perform pg_advisory_xact_lock(hashtextextended(target_account_id::text, 0));
    insert into public.cloud_account_deletion_requests as d (cloud_user_id, confirmed_at)
    values (target_account_id, now())
    on conflict (cloud_user_id) do update
        set confirmed_at = coalesce(d.confirmed_at, excluded.confirmed_at)
    returning d.requested_at into requested_at_value;

    return jsonb_build_object(
        'status', 'deleting',
        'requested_at', requested_at_value
    );
end;
$$;

revoke all on function public.request_cloud_account_deletion_for(uuid)
    from public, anon, authenticated;
grant execute on function public.request_cloud_account_deletion_for(uuid) to service_role;
