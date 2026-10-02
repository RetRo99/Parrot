-- Per-user daily recap counter for the generate-recap Edge Function, so a
-- leaked session can't drain the provider quota. Only consume_recap_quota
-- writes it; clients have no direct access.

create table public.recap_usage (
    user_id uuid not null references auth.users(id) on delete cascade,
    day date not null,
    count integer not null default 0 check (count >= 0),
    primary key (user_id, day)
);

alter table public.recap_usage enable row level security;
revoke all on public.recap_usage from anon, authenticated;

-- Takes one unit of today's (UTC) quota for the caller. Returns false,
-- without counting, once the caller has used p_limit units today.
create or replace function public.consume_recap_quota(p_limit integer)
returns boolean
language plpgsql
security definer
set search_path = public
as $$
declare
    actor uuid := auth.uid();
    today date := timezone('utc', now())::date;
    new_count integer;
begin
    if actor is null then
        raise exception 'Authentication required';
    end if;

    -- The Edge Function rejects these too; this covers direct RPC calls.
    if coalesce((auth.jwt()->>'is_anonymous')::boolean, false) then
        raise exception 'Anonymous users cannot request recaps';
    end if;

    if p_limit is null or p_limit < 1 then
        return false;
    end if;

    -- One statement, so concurrent calls can't both slip past the limit.
    insert into public.recap_usage as ru (user_id, day, count)
    values (actor, today, 1)
    on conflict (user_id, day) do update
        set count = ru.count + 1
        where ru.count < p_limit
    returning ru.count into new_count;

    return new_count is not null;
end;
$$;

revoke execute on function public.consume_recap_quota(integer)
from public, anon, service_role;
grant execute on function public.consume_recap_quota(integer) to authenticated;
