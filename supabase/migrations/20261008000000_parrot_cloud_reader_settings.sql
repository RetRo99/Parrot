-- Reader preferences sync per setting key. TTS voice IDs and custom font
-- selections are device-specific and are rejected by the RPC as a backstop.
create table public.cloud_reader_settings (
    cloud_user_id uuid not null references auth.users(id) on delete cascade,
    setting_key text not null,
    setting_value jsonb not null,
    revision bigint not null default 1,
    updated_at timestamptz not null default timezone('utc', now()),
    primary key (cloud_user_id, setting_key),
    check (char_length(setting_key) between 1 and 128)
);

alter table public.cloud_reader_settings enable row level security;

create policy cloud_reader_settings_owner_select
on public.cloud_reader_settings for select to authenticated
using (cloud_user_id = auth.uid());

revoke all on public.cloud_reader_settings from anon, authenticated;
grant select on public.cloud_reader_settings to authenticated;

create trigger guard_cloud_account_deletion_write
before insert or update on public.cloud_reader_settings
for each row execute function public.guard_cloud_account_deletion_write();

create or replace function public.push_reader_settings(
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
    mutation_id_value uuid;
    entity_type_value text;
    operation_value text;
    entity_id_value text;
    mutation_payload jsonb;
    base_revision_value bigint;
    current_revision_value bigint;
    current_value jsonb;
    response_payload jsonb;
    result jsonb;
    results jsonb := '[]'::jsonb;
    item_rejection text;
    new_revision_value bigint;
    change_id_value bigint;
    existing_response jsonb;
begin
    if actor is null then
        raise exception 'Authentication required';
    end if;

    perform pg_advisory_xact_lock(hashtextextended(actor::text, 0));

    if jsonb_typeof(coalesce(mutations, '[]'::jsonb)) <> 'array' then
        raise exception 'mutations must be a JSON array';
    end if;
    if jsonb_array_length(coalesce(mutations, '[]'::jsonb)) > 200
        or octet_length(coalesce(mutations, '[]'::jsonb)::text) > 524288
    then
        raise exception using errcode = 'P0001', message = 'sync_batch_too_large';
    end if;

    for mutation_item in
        select value from jsonb_array_elements(coalesce(mutations, '[]'::jsonb))
    loop
        mutation_id_value := (mutation_item ->> 'mutation_id')::uuid;
        entity_type_value := mutation_item ->> 'entity_type';
        operation_value := mutation_item ->> 'operation';
        entity_id_value := mutation_item ->> 'entity_id';
        mutation_payload := mutation_item -> 'payload';
        base_revision_value := null;
        item_rejection := null;
        current_revision_value := null;
        current_value := null;
        response_payload := null;
        new_revision_value := null;
        change_id_value := null;

        if octet_length(mutation_item::text) > 8192 then
            item_rejection := 'payload_too_large';
        else
            begin
                base_revision_value := nullif(mutation_item ->> 'base_revision', '')::bigint;
            exception when invalid_text_representation or numeric_value_out_of_range then
                item_rejection := 'invalid_base_revision';
            end;
        end if;

        select response
        into existing_response
        from public.sync_mutations as stored_mutation
        where stored_mutation.cloud_user_id = actor
          and stored_mutation.mutation_id = mutation_id_value;
        if found then
            results := results || jsonb_build_array(existing_response);
            continue;
        end if;

        result := jsonb_build_object(
            'mutation_id', mutation_id_value,
            'status', 'rejected',
            'reason', 'unsupported_mutation'
        );
        if item_rejection is not null then
            result := jsonb_build_object(
                'mutation_id', mutation_id_value,
                'status', 'rejected',
                'reason', item_rejection
            );
        elsif entity_type_value = 'reader_settings'
            and operation_value = 'upsert'
        then
            if entity_id_value is null
                or entity_id_value !~ '^[A-Za-z][A-Za-z0-9_]{0,127}$'
                or mutation_payload is null
                or jsonb_typeof(mutation_payload) in ('object', 'array')
                or entity_id_value = 'tts_voice_id'
                or (
                    entity_id_value = 'font_family'
                    and mutation_payload not in (
                        '"default"'::jsonb,
                        '"serif"'::jsonb,
                        '"sans-serif"'::jsonb,
                        '"cursive"'::jsonb,
                        '"fantasy"'::jsonb,
                        '"monospace"'::jsonb,
                        '"AccessibleDfA"'::jsonb,
                        '"IA Writer Duospace"'::jsonb,
                        '"OpenDyslexic"'::jsonb,
                        '"Droid Sans"'::jsonb,
                        '"Atkinson Hyperlegible"'::jsonb,
                        '"Literata"'::jsonb,
                        '"Merriweather"'::jsonb,
                        '"Source Serif 4"'::jsonb,
                        '"Noto Sans"'::jsonb,
                        '"Noto Serif"'::jsonb
                    )
                )
            then
                result := jsonb_build_object(
                    'mutation_id', mutation_id_value,
                    'status', 'rejected',
                    'reason', 'invalid_reader_setting'
                );
            else
                select setting.revision, setting.setting_value
                into current_revision_value, current_value
                from public.cloud_reader_settings as setting
                where setting.cloud_user_id = actor
                  and setting.setting_key = entity_id_value
                for update;

                if found and (
                    base_revision_value is null
                    or base_revision_value <> current_revision_value
                ) then
                    response_payload := jsonb_build_object(
                        'setting_key', entity_id_value,
                        'value', current_value,
                        'remote_revision', current_revision_value
                    );
                    result := jsonb_build_object(
                        'mutation_id', mutation_id_value,
                        'status', 'conflict',
                        'revision', current_revision_value,
                        'payload', response_payload,
                        'reason', 'stale_reader_setting'
                    );
                elsif not found and base_revision_value is not null then
                    result := jsonb_build_object(
                        'mutation_id', mutation_id_value,
                        'status', 'conflict',
                        'reason', 'stale_reader_setting'
                    );
                else
                    new_revision_value := coalesce(current_revision_value, 0) + 1;
                    insert into public.cloud_reader_settings(
                        cloud_user_id,
                        setting_key,
                        setting_value,
                        revision
                    )
                    values (
                        actor,
                        entity_id_value,
                        mutation_payload,
                        new_revision_value
                    )
                    on conflict (cloud_user_id, setting_key) do update
                    set setting_value = excluded.setting_value,
                        revision = excluded.revision,
                        updated_at = timezone('utc', now());

                    response_payload := jsonb_build_object(
                        'setting_key', entity_id_value,
                        'value', mutation_payload,
                        'remote_revision', new_revision_value
                    );
                    insert into public.sync_changes(
                        cloud_user_id,
                        entity_type,
                        entity_id,
                        operation,
                        payload,
                        revision
                    )
                    values (
                        actor,
                        entity_type_value,
                        entity_id_value,
                        operation_value,
                        response_payload,
                        new_revision_value
                    )
                    returning change_id into change_id_value;
                    result := jsonb_build_object(
                        'mutation_id', mutation_id_value,
                        'status', 'accepted',
                        'revision', new_revision_value,
                        'payload', response_payload,
                        'change_id', change_id_value
                    );
                end if;
            end if;
        end if;

        insert into public.sync_mutations(
            cloud_user_id,
            mutation_id,
            entity_type,
            entity_id,
            response
        )
        values (
            actor,
            mutation_id_value,
            left(entity_type_value, 64),
            left(entity_id_value, 256),
            result
        );
        results := results || jsonb_build_array(result);
    end loop;
    return results;
end;
$$;

revoke execute on function public.push_reader_settings(jsonb, bigint) from public, anon;
grant execute on function public.push_reader_settings(jsonb, bigint) to authenticated;
