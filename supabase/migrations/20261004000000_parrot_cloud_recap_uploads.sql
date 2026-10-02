-- Temporary storage for long recap excerpts. Request bodies of ~1 MB+ are
-- unreliable on Edge Functions, so the app sends a long excerpt in parts of
-- <= ~180 KB; generate-recap stores all but the last here, and the request
-- with the last part takes them back (deleting them) and generates.
-- Clients only reach this table through the two RPCs below.

create table public.recap_upload_parts (
    user_id uuid not null references auth.users(id) on delete cascade,
    upload_id uuid not null,
    part_index integer not null,
    part_count integer not null,
    content text not null,
    created_at timestamptz not null default now(),
    primary key (user_id, upload_id, part_index),
    check (part_count between 2 and 64),
    -- The last part is never stored: it arrives with the generate request.
    check (part_index between 0 and part_count - 2),
    check (char_length(content) between 1 and 200000)
);

create index recap_upload_parts_created_at_idx
    on public.recap_upload_parts (created_at);

alter table public.recap_upload_parts enable row level security;
revoke all on public.recap_upload_parts from anon, authenticated;

-- Stores one part for the caller. False when the caller may not use recaps
-- or already holds too many parts (the function answers 429 either way).
create or replace function public.put_recap_upload_part(
    p_upload_id uuid,
    p_part_index integer,
    p_part_count integer,
    p_content text
)
returns boolean
language plpgsql
security definer
set search_path = public
as $$
declare
    actor uuid := auth.uid();
    held integer;
begin
    if actor is null then
        raise exception 'Authentication required';
    end if;
    if coalesce((auth.jwt()->>'is_anonymous')::boolean, false) then
        raise exception 'Anonymous users cannot request recaps';
    end if;
    if not public.cloud_feature_enabled('recap') then
        return false;
    end if;

    -- Abandoned uploads go after an hour, here and in purge_cloud_retention.
    delete from public.recap_upload_parts
    where user_id = actor and created_at < now() - interval '1 hour';

    -- Two full uploads in flight is plenty for one person's app.
    select count(*) into held
    from public.recap_upload_parts
    where user_id = actor and upload_id <> p_upload_id;
    if held >= 128 then
        return false;
    end if;

    insert into public.recap_upload_parts as p
        (user_id, upload_id, part_index, part_count, content)
    values (actor, p_upload_id, p_part_index, p_part_count, p_content)
    on conflict (user_id, upload_id, part_index) do update
        set part_count = excluded.part_count,
            content = excluded.content,
            created_at = now();
    return true;
end;
$$;

-- Returns the caller's stored parts of an upload joined in order, and
-- deletes them. Null when any part is missing, so a retry starts over.
create or replace function public.take_recap_upload(
    p_upload_id uuid,
    p_part_count integer
)
returns text
language plpgsql
security definer
set search_path = public
as $$
declare
    actor uuid := auth.uid();
    stored integer;
    joined text;
begin
    if actor is null then
        raise exception 'Authentication required';
    end if;

    select count(*), string_agg(p.content, '' order by p.part_index)
    into stored, joined
    from public.recap_upload_parts p
    where p.user_id = actor
      and p.upload_id = p_upload_id
      and p.part_count = p_part_count;

    delete from public.recap_upload_parts
    where user_id = actor and upload_id = p_upload_id;

    -- The key and checks make indexes unique and < part_count - 1, so a
    -- full count means every stored part 0 .. part_count - 2 is there.
    if p_part_count is null or stored <> p_part_count - 1 then
        return null;
    end if;
    return joined;
end;
$$;

revoke execute on function public.put_recap_upload_part(uuid, integer, integer, text)
from public, anon, service_role;
grant execute on function public.put_recap_upload_part(uuid, integer, integer, text)
to authenticated;
revoke execute on function public.take_recap_upload(uuid, integer)
from public, anon, service_role;
grant execute on function public.take_recap_upload(uuid, integer) to authenticated;

-- Same as 20261003000000 plus abandoned recap uploads (> 1 hour old).
create or replace function public.purge_cloud_retention(retain_days integer default 90)
returns jsonb
language plpgsql
security definer
set search_path = public
as $$
declare
    cutoff timestamptz;
    changes_deleted integer;
    mutations_deleted integer;
    recap_deleted integer;
    recap_global_deleted integer;
    recap_uploads_deleted integer;
begin
    if auth.role() is distinct from 'service_role' then
        raise exception 'Service role required';
    end if;
    -- Offline clients replay mutations; keep idempotency records a while.
    if retain_days is null or retain_days < 30 then
        raise exception 'retain_days must be at least 30';
    end if;
    cutoff := now() - make_interval(days => retain_days);

    delete from public.sync_changes as sc
    using (
        select ranked.change_id
        from (
            select c.change_id,
                   c.created_at,
                   row_number() over (
                       partition by c.cloud_user_id, c.entity_type, c.entity_id
                       order by c.change_id desc
                   ) as newest_first
            from public.sync_changes as c
        ) as ranked
        where ranked.newest_first > 1
          and ranked.created_at < cutoff
    ) as stale
    where sc.change_id = stale.change_id;
    get diagnostics changes_deleted = row_count;

    delete from public.sync_mutations as sm
    where sm.created_at < cutoff;
    get diagnostics mutations_deleted = row_count;

    delete from public.recap_usage as ru
    where ru.day < (timezone('utc', now()) - interval '90 days')::date;
    get diagnostics recap_deleted = row_count;

    delete from public.recap_global_usage as g
    where g.day < (timezone('utc', now()) - interval '90 days')::date;
    get diagnostics recap_global_deleted = row_count;

    delete from public.recap_upload_parts as rp
    where rp.created_at < now() - interval '1 hour';
    get diagnostics recap_uploads_deleted = row_count;

    return jsonb_build_object(
        'status', 'purged',
        'sync_changes_deleted', changes_deleted,
        'sync_mutations_deleted', mutations_deleted,
        'recap_usage_deleted', recap_deleted,
        'recap_global_usage_deleted', recap_global_deleted,
        'recap_upload_parts_deleted', recap_uploads_deleted
    );
end;
$$;

revoke execute on function public.purge_cloud_retention(integer)
from public, anon, authenticated;
grant execute on function public.purge_cloud_retention(integer) to service_role;
