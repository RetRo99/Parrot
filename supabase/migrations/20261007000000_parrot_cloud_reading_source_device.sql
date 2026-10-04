-- Keep this forward migration narrowly scoped: replace only the relevant pieces
-- of the current RPC definition so its ownership, revision, idempotency and
-- unrelated entity handling remain byte-for-byte unchanged.
do $migration$
declare
    function_sql text;
    old_piece text;
    new_piece text;
begin
    select pg_get_functiondef('public.push_sync_changes(jsonb, bigint)'::regprocedure)
    into function_sql;

    old_piece := $old$    reading_payload jsonb;$old$;
    new_piece := $new$    reading_payload jsonb;
    source_device_value jsonb;
    source_device_id_value uuid;
    source_device_name_value text;$new$;
    if position(old_piece in function_sql) = 0 then
        raise exception 'push_sync_changes declaration shape changed; review source-device migration';
    end if;
    function_sql := replace(function_sql, old_piece, new_piece);

    old_piece := $old$        reading_payload := null;
        session_id_value := null;$old$;
    new_piece := $new$        reading_payload := null;
        source_device_value := null;
        source_device_id_value := null;
        source_device_name_value := null;
        session_id_value := null;$new$;
    if position(old_piece in function_sql) = 0 then
        raise exception 'push_sync_changes reset shape changed; review source-device migration';
    end if;
    function_sql := replace(function_sql, old_piece, new_piece);

    old_piece := $old$        select response
        into existing_response
        from public.sync_mutations as stored_mutation
        where stored_mutation.cloud_user_id = actor
          and stored_mutation.mutation_id = mutation_id_value;$old$;
    new_piece := $new$        -- source_device is display-only metadata. Validate and normalize it
        -- independently from the book ownership and revision checks below.
        if item_rejection is null
            and entity_type_value = 'reading_position'
            and mutation_payload ? 'source_device'
            and mutation_payload -> 'source_device' <> 'null'::jsonb
        then
            source_device_value := mutation_payload -> 'source_device';
            if jsonb_typeof(source_device_value) <> 'object'
                or coalesce(jsonb_typeof(source_device_value -> 'id'), '') <> 'string'
                or char_length(source_device_value ->> 'id') > 36
                or (
                    source_device_value ? 'name'
                    and source_device_value -> 'name' <> 'null'::jsonb
                    and coalesce(jsonb_typeof(source_device_value -> 'name'), '') <> 'string'
                )
                or char_length(coalesce(source_device_value ->> 'name', '')) > 80
            then
                item_rejection := 'invalid_source_device';
            else
                begin
                    source_device_id_value := (source_device_value ->> 'id')::uuid;
                exception when invalid_text_representation or numeric_value_out_of_range then
                    item_rejection := 'invalid_source_device';
                end;
            end if;

            if item_rejection is null then
                source_device_name_value := nullif(btrim(source_device_value ->> 'name'), '');
                source_device_value := jsonb_build_object(
                    'id', source_device_id_value::text,
                    'name', source_device_name_value
                );
            end if;
        end if;

        select response
        into existing_response
        from public.sync_mutations as stored_mutation
        where stored_mutation.cloud_user_id = actor
          and stored_mutation.mutation_id = mutation_id_value;$new$;
    if position(old_piece in function_sql) = 0 then
        raise exception 'push_sync_changes idempotency lookup shape changed; review source-device migration';
    end if;
    function_sql := replace(function_sql, old_piece, new_piece);

    old_piece := $old$                reading_payload := jsonb_build_object(
                    'library_book_id', library_book_id_value,
                    'position', mutation_payload -> 'position'
                );$old$;
    new_piece := $new$                reading_payload := jsonb_build_object(
                    'library_book_id', library_book_id_value,
                    'position', mutation_payload -> 'position'
                );
                if source_device_value is not null then
                    reading_payload := reading_payload || jsonb_build_object(
                        'source_device', source_device_value
                    );
                end if;$new$;
    if position(old_piece in function_sql) = 0 then
        raise exception 'push_sync_changes reading payload shape changed; review source-device migration';
    end if;
    function_sql := replace(function_sql, old_piece, new_piece);

    execute function_sql;
end;
$migration$;
