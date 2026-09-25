create table public.library_group_sync_revisions (
    cloud_user_id uuid primary key references auth.users(id) on delete cascade,
    revision bigint not null default 0 check (revision >= 0),
    updated_at timestamptz not null default timezone('utc', now())
);

create table public.library_group_decisions (
    cloud_user_id uuid not null references auth.users(id) on delete cascade,
    decision_id uuid not null,
    mutation_id uuid not null,
    revision bigint not null check (revision > 0),
    decision_type text not null check (decision_type in ('merge', 'split', 'preferences')),
    target_group_id uuid not null,
    payload jsonb not null,
    created_at timestamptz not null default timezone('utc', now()),
    primary key (cloud_user_id, decision_id),
    unique (cloud_user_id, mutation_id),
    unique (cloud_user_id, revision)
);

create index library_group_decisions_account_target
on public.library_group_decisions(cloud_user_id, target_group_id, revision);

alter table public.library_group_sync_revisions enable row level security;
alter table public.library_group_decisions enable row level security;

create policy library_group_sync_revisions_owner_select
on public.library_group_sync_revisions for select to authenticated
using (cloud_user_id = auth.uid());

create policy library_group_decisions_owner_select
on public.library_group_decisions for select to authenticated
using (cloud_user_id = auth.uid());

revoke all on public.library_group_sync_revisions from anon, authenticated;
revoke all on public.library_group_decisions from anon, authenticated;

create or replace function public.push_library_group_decisions(
    mutations jsonb,
    client_cursor bigint default 0
)
returns jsonb
language plpgsql
security definer
set search_path = public
as $$
declare
    actor uuid := auth.uid();
    mutation_item jsonb;
    mutation_payload jsonb;
    mutation_id_text text;
    mutation_id_value uuid;
    decision_id_value uuid;
    entity_type_value text;
    operation_value text;
    decision_type_value text;
    target_group_id_value uuid;
    retained_group_id_value uuid;
    current_revision_value bigint;
    new_revision_value bigint;
    member_item jsonb;
    member_key_value text;
    member_keys text[] := array[]::text[];
    media_type_value text;
    preferred_media_member_value jsonb;
    seen_decision_response jsonb;
    result jsonb;
    results jsonb := '[]'::jsonb;
    invalid_reason text;
begin
    if actor is null then
        raise exception 'Authentication required';
    end if;
    if jsonb_typeof(coalesce(mutations, '[]'::jsonb)) <> 'array' then
        raise exception 'mutations must be a JSON array';
    end if;

    -- Match the shared mutation push lock so account revisions and feed cursors
    -- cannot move past a decision that has not committed yet.
    perform pg_advisory_xact_lock(hashtextextended(actor::text, 0));

    insert into public.library_group_sync_revisions(cloud_user_id, revision)
    values (actor, 0)
    on conflict (cloud_user_id) do nothing;
    select revision into current_revision_value
    from public.library_group_sync_revisions
    where cloud_user_id = actor
    for update;

    for mutation_item in
        select value
        from jsonb_array_elements(coalesce(mutations, '[]'::jsonb)) with ordinality as batch(value, ordinal)
        order by ordinal
    loop
        mutation_payload := mutation_item -> 'payload';
        mutation_id_text := mutation_item ->> 'mutation_id';
        mutation_id_value := null;
        if mutation_id_text is not null then
            begin
                mutation_id_value := mutation_id_text::uuid;
            exception when invalid_text_representation then
                mutation_id_value := null;
            end;
        end if;
        entity_type_value := mutation_item ->> 'entity_type';
        operation_value := mutation_item ->> 'operation';
        if mutation_id_value is null then
            result := jsonb_build_object(
                'mutation_id', coalesce(mutation_id_text, ''),
                'status', 'rejected',
                'reason', 'invalid_mutation_id'
            );
            results := results || jsonb_build_array(result);
            continue;
        end if;

        -- Keep idempotency lookup ahead of payload validation so replays of a
        -- valid mutation ID always receive the first persisted response.
        select response into seen_decision_response
        from public.sync_mutations
        where cloud_user_id = actor and mutation_id = mutation_id_value;
        if found then
            results := results || jsonb_build_array(seen_decision_response);
            continue;
        end if;

        decision_id_value := null;
        target_group_id_value := null;
        retained_group_id_value := null;
        invalid_reason := null;
        if jsonb_typeof(mutation_payload) = 'object' then
            begin
                decision_id_value := nullif(mutation_payload ->> 'decision_id', '')::uuid;
                target_group_id_value := nullif(
                    mutation_payload ->> 'target_group_id',
                    ''
                )::uuid;
                retained_group_id_value := nullif(
                    mutation_payload ->> 'retained_group_id',
                    ''
                )::uuid;
            exception when invalid_text_representation then
                invalid_reason := 'invalid_group_decision_uuid';
            end;
        end if;
        decision_type_value := mutation_payload ->> 'decision_type';
        member_keys := array[]::text[];

        if invalid_reason is null and (
            entity_type_value is distinct from 'library_group_decision'
            or operation_value is distinct from 'upsert'
            or jsonb_typeof(mutation_payload) is distinct from 'object'
            or jsonb_typeof(mutation_payload -> 'version') is distinct from 'number'
            or mutation_payload ->> 'version' is distinct from '1'
            or decision_id_value is null
            or decision_id_value <> mutation_id_value
            or target_group_id_value is null
            or decision_type_value is null
            or decision_type_value not in ('merge', 'split', 'preferences')
            or nullif(btrim(mutation_payload ->> 'created_at'), '') is null
        ) then
            invalid_reason := 'invalid_group_decision';
        end if;

        if invalid_reason is null and mutation_payload ?|
            array['local_profile_id', 'profile_id', 'connection_id', 'url', 'path', 'local_path']
        then
            invalid_reason := 'non_portable_member_reference';
        end if;

        if invalid_reason is null then
            if exists (
                select 1
                from jsonb_object_keys(mutation_payload) as payload_key(key)
                where payload_key.key not in (
                    'version',
                    'decision_id',
                    'decision_type',
                    'target_group_id',
                    'retained_group_id',
                    'members',
                    'retained_members',
                    'preferred_metadata_member',
                    'preferred_media_members',
                    'created_at'
                )
            ) then
                invalid_reason := 'unknown_group_decision_field';
            end if;
        end if;

        if invalid_reason is null
            and jsonb_typeof(mutation_payload -> 'members') is distinct from 'array'
        then
            invalid_reason := 'members_required';
        end if;
        if invalid_reason is null and jsonb_typeof(
            coalesce(mutation_payload -> 'retained_members', '[]'::jsonb)
        ) <> 'array' then
            invalid_reason := 'retained_members_invalid';
        end if;

        if invalid_reason is null and jsonb_typeof(coalesce(
            mutation_payload -> 'preferred_media_members',
            '{}'::jsonb
        )) is distinct from 'object' then
            invalid_reason := 'preferred_media_members_invalid';
        end if;

        if invalid_reason is null then
            for member_item in
                select value from jsonb_array_elements(mutation_payload -> 'members')
                union all
                select value from jsonb_array_elements(
                    coalesce(mutation_payload -> 'retained_members', '[]'::jsonb)
                )
            loop
                if jsonb_typeof(member_item) is distinct from 'object' then
                    invalid_reason := 'non_portable_member_reference';
                    exit;
                elsif (select count(*) from jsonb_object_keys(member_item)) <> 4
                    or jsonb_typeof(member_item -> 'adapter_id') is distinct from 'string'
                    or jsonb_typeof(member_item -> 'backend_id') is distinct from 'string'
                    or jsonb_typeof(member_item -> 'account_id') is distinct from 'string'
                    or jsonb_typeof(member_item -> 'native_book_id') is distinct from 'string'
                    or nullif(btrim(member_item ->> 'adapter_id'), '') is null
                    or nullif(btrim(member_item ->> 'backend_id'), '') is null
                    or nullif(btrim(member_item ->> 'account_id'), '') is null
                    or nullif(btrim(member_item ->> 'native_book_id'), '') is null
                then
                    invalid_reason := 'non_portable_member_reference';
                    exit;
                end if;
                member_key_value := jsonb_build_array(
                    member_item ->> 'adapter_id',
                    member_item ->> 'backend_id',
                    member_item ->> 'account_id',
                    member_item ->> 'native_book_id'
                )::text;
                if member_key_value = any(member_keys) then
                    invalid_reason := 'duplicate_member_reference';
                    exit;
                end if;
                member_keys := array_append(member_keys, member_key_value);
            end loop;
        end if;

        if invalid_reason is null then
            if decision_type_value = 'merge' and (
                jsonb_array_length(mutation_payload -> 'members') < 2
                or jsonb_array_length(coalesce(mutation_payload -> 'retained_members', '[]'::jsonb)) <> 0
                or retained_group_id_value is not null
            ) then
                invalid_reason := 'invalid_merge_members';
            elsif decision_type_value = 'split' and (
                jsonb_array_length(mutation_payload -> 'members') = 0
                or jsonb_array_length(coalesce(mutation_payload -> 'retained_members', '[]'::jsonb)) = 0
                or retained_group_id_value is null
                or retained_group_id_value = target_group_id_value
            ) then
                invalid_reason := 'invalid_split_members';
            elsif decision_type_value = 'preferences' and
                jsonb_array_length(mutation_payload -> 'members') = 0 then
                invalid_reason := 'invalid_preference_members';
            elsif decision_type_value = 'preferences'
                and coalesce(
                    mutation_payload -> 'preferred_metadata_member',
                    'null'::jsonb
                ) = 'null'::jsonb
                and (select count(*) from jsonb_object_keys(coalesce(
                    mutation_payload -> 'preferred_media_members',
                    '{}'::jsonb
                ))) = 0 then
                invalid_reason := 'preference_required';
            end if;
        end if;

        if invalid_reason is null
            and mutation_payload -> 'preferred_metadata_member' <> 'null'::jsonb
            and not (mutation_payload -> 'members') @>
                jsonb_build_array(mutation_payload -> 'preferred_metadata_member')
        then
            invalid_reason := 'preferred_member_not_selected';
        end if;

        if invalid_reason is null then
            for media_type_value, preferred_media_member_value in
                select key, value
                from jsonb_each(coalesce(
                    mutation_payload -> 'preferred_media_members',
                    '{}'::jsonb
                ))
            loop
                if nullif(btrim(media_type_value), '') is null
                    or jsonb_typeof(preferred_media_member_value) is distinct from 'object'
                then
                    invalid_reason := 'preferred_media_member_invalid';
                    exit;
                elsif (select count(*) from jsonb_object_keys(
                    preferred_media_member_value
                )) <> 4
                    or jsonb_typeof(preferred_media_member_value -> 'adapter_id')
                        is distinct from 'string'
                    or jsonb_typeof(preferred_media_member_value -> 'backend_id')
                        is distinct from 'string'
                    or jsonb_typeof(preferred_media_member_value -> 'account_id')
                        is distinct from 'string'
                    or jsonb_typeof(preferred_media_member_value -> 'native_book_id')
                        is distinct from 'string'
                    or nullif(btrim(preferred_media_member_value ->> 'adapter_id'), '') is null
                    or nullif(btrim(preferred_media_member_value ->> 'backend_id'), '') is null
                    or nullif(btrim(preferred_media_member_value ->> 'account_id'), '') is null
                    or nullif(btrim(preferred_media_member_value ->> 'native_book_id'), '') is null
                then
                    invalid_reason := 'preferred_media_member_invalid';
                    exit;
                end if;
                if not (mutation_payload -> 'members') @>
                    jsonb_build_array(preferred_media_member_value)
                then
                    invalid_reason := 'preferred_media_member_not_selected';
                    exit;
                end if;
            end loop;
        end if;

        if invalid_reason is not null then
            result := jsonb_build_object(
                'mutation_id', mutation_id_value,
                'status', 'rejected',
                'reason', invalid_reason
            );
        else
            new_revision_value := current_revision_value + 1;
            insert into public.library_group_decisions(
                cloud_user_id,
                decision_id,
                mutation_id,
                revision,
                decision_type,
                target_group_id,
                payload
            ) values (
                actor,
                decision_id_value,
                mutation_id_value,
                new_revision_value,
                decision_type_value,
                target_group_id_value,
                mutation_payload
            );
            update public.library_group_sync_revisions
            set revision = new_revision_value,
                updated_at = timezone('utc', now())
            where cloud_user_id = actor;
            current_revision_value := new_revision_value;

            insert into public.sync_changes(
                cloud_user_id,
                entity_type,
                entity_id,
                operation,
                payload,
                revision
            ) values (
                actor,
                'library_group_decision',
                decision_id_value::text,
                operation_value,
                mutation_payload,
                new_revision_value
            );
            result := jsonb_build_object(
                'mutation_id', mutation_id_value,
                'status', 'accepted',
                'revision', new_revision_value,
                'payload', mutation_payload
            );
        end if;

        insert into public.sync_mutations(
            cloud_user_id,
            mutation_id,
            entity_type,
            entity_id,
            response
        ) values (
            actor,
            mutation_id_value,
            coalesce(entity_type_value, ''),
            coalesce(mutation_payload ->> 'target_group_id', ''),
            result
        );
        results := results || jsonb_build_array(result);
    end loop;
    return results;
end;
$$;

revoke execute on function public.push_library_group_decisions(jsonb, bigint) from public, anon;
grant execute on function public.push_library_group_decisions(jsonb, bigint) to authenticated;
