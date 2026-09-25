-- Preserve book tombstones, while allowing explicit, revision-checked reimports.
-- A whole-book tombstone also removes the remote reading-position row.
create or replace function public.push_sync_changes(
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
    book_deleted_at_value timestamptz;
    intentional_reimport_value boolean;
    current_payload jsonb;
    cloud_book_id_value uuid;
    cloud_user_id_value uuid;
    content_hash_value text;
    content_hash_algorithm_value text;
    title_value text;
    author_value text;
    format_value text;
    metadata_value jsonb;
    library_book_id_value text;
    reading_payload jsonb;
    new_revision_value bigint;
    change_id_value bigint;
    existing_response jsonb;
    result jsonb;
    results jsonb := '[]'::jsonb;
begin
    if actor is null then
        raise exception 'Authentication required';
    end if;

    -- Sequence allocation is not commit ordering. Serializing push and pull
    -- per account prevents a later committed change from hiding an earlier
    -- transaction behind an advanced cursor.
    perform pg_advisory_xact_lock(hashtextextended(actor::text, 0));

    if jsonb_typeof(coalesce(mutations, '[]'::jsonb)) <> 'array' then
        raise exception 'mutations must be a JSON array';
    end if;

    for mutation_item in
        select value from jsonb_array_elements(coalesce(mutations, '[]'::jsonb))
    loop
        cloud_book_id_value := null;
        cloud_user_id_value := null;
        current_revision_value := null;
        book_deleted_at_value := null;
        intentional_reimport_value := false;
        current_payload := null;
        library_book_id_value := null;
        reading_payload := null;
        new_revision_value := null;
        change_id_value := null;
        mutation_id_value := (mutation_item ->> 'mutation_id')::uuid;
        entity_type_value := mutation_item ->> 'entity_type';
        operation_value := mutation_item ->> 'operation';
        entity_id_value := mutation_item ->> 'entity_id';
        mutation_payload := mutation_item -> 'payload';
        base_revision_value := nullif(mutation_item ->> 'base_revision', '')::bigint;
        intentional_reimport_value := coalesce(
            (mutation_payload ->> 'intentional_reimport')::boolean,
            false
        );

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

        if operation_value = 'upsert'
            and entity_type_value = 'library_book'
        then
            content_hash_value := coalesce(
                mutation_payload ->> 'content_hash',
                mutation_payload ->> 'contentHash'
            );
            content_hash_algorithm_value := coalesce(
                mutation_payload ->> 'content_hash_algorithm',
                mutation_payload ->> 'contentHashAlgorithm',
                'sha-256-v1'
            );
            title_value := mutation_payload ->> 'title';
            author_value := mutation_payload ->> 'author';
            format_value := mutation_payload ->> 'format';
            metadata_value := coalesce(mutation_payload -> 'metadata', '{}'::jsonb);
            if mutation_payload ? 'metadata_json'
                and nullif(mutation_payload ->> 'metadata_json', '') is not null
            then
                metadata_value := (mutation_payload ->> 'metadata_json')::jsonb;
            end if;

            if content_hash_value is null or title_value is null or format_value is null then
                result := jsonb_build_object(
                    'mutation_id', mutation_id_value,
                    'status', 'rejected',
                    'reason', 'book_identity_required'
                );
            else
                select id, revision, deleted_at
                into cloud_book_id_value, current_revision_value, book_deleted_at_value
                from public.cloud_books
                where cloud_user_id = actor
                  and content_hash_algorithm = content_hash_algorithm_value
                  and content_hash = content_hash_value
                for update;

                if cloud_book_id_value is null then
                    insert into public.cloud_books(
                        cloud_user_id,
                        content_hash,
                        content_hash_algorithm,
                        title,
                        author,
                        format,
                        metadata
                    )
                    values (
                        actor,
                        content_hash_value,
                        content_hash_algorithm_value,
                        title_value,
                        author_value,
                        format_value,
                        metadata_value
                    )
                    returning id, revision into cloud_book_id_value, new_revision_value;
                elsif book_deleted_at_value is not null
                    and intentional_reimport_value
                    and base_revision_value = current_revision_value
                then
                    new_revision_value := current_revision_value + 1;
                    update public.cloud_books
                    set title = title_value,
                        author = author_value,
                        format = format_value,
                        metadata = metadata_value,
                        revision = new_revision_value,
                        updated_at = timezone('utc', now()),
                        deleted_at = null
                    where id = cloud_book_id_value;
                elsif book_deleted_at_value is not null
                then
                    select jsonb_build_object(
                        'library_book_id',
                        content_hash_algorithm || ':' || content_hash,
                        'cloud_book_id', id,
                        'content_hash', content_hash,
                        'content_hash_algorithm', content_hash_algorithm,
                        'title', title,
                        'author', author,
                        'format', format,
                        'metadata_json', metadata::text,
                        'remote_revision', revision,
                        'deleted_at', deleted_at
                    )
                    into current_payload
                    from public.cloud_books
                    where id = cloud_book_id_value;
                    result := jsonb_build_object(
                        'mutation_id', mutation_id_value,
                        'status', 'conflict',
                        'cloud_book_id', cloud_book_id_value,
                        'revision', current_revision_value,
                        'payload', current_payload,
                        'reason', 'book_deleted'
                    );
                elsif base_revision_value is null
                    or base_revision_value <> current_revision_value
                then
                    select jsonb_build_object(
                        'library_book_id', mutation_payload ->> 'library_book_id',
                        'cloud_book_id', cloud_book_id_value,
                        'content_hash', content_hash,
                        'content_hash_algorithm', content_hash_algorithm,
                        'title', title,
                        'author', author,
                        'format', format,
                        'remote_revision', revision,
                        'deleted_at', deleted_at
                    )
                    into current_payload
                    from public.cloud_books
                    where id = cloud_book_id_value;
                    result := jsonb_build_object(
                        'mutation_id', mutation_id_value,
                        'status', 'conflict',
                        'cloud_book_id', cloud_book_id_value,
                        'revision', current_revision_value,
                        'payload', current_payload
                    );
                else
                    new_revision_value := current_revision_value + 1;
                    update public.cloud_books
                    set title = title_value,
                        author = author_value,
                        format = format_value,
                        metadata = metadata_value,
                        revision = new_revision_value,
                        updated_at = timezone('utc', now()),
                        deleted_at = null
                    where id = cloud_book_id_value;
                end if;

                if result ->> 'status' <> 'conflict'
                    and cloud_book_id_value is not null
                    and new_revision_value is not null
                then
                    select jsonb_build_object(
                        'library_book_id', mutation_payload ->> 'library_book_id',
                        'cloud_book_id', cloud_book_id_value,
                        'content_hash', content_hash_value,
                        'content_hash_algorithm', content_hash_algorithm_value,
                        'title', title_value,
                        'author', author_value,
                        'format', format_value,
                        'remote_revision', new_revision_value,
                        'deleted_at', null
                    ) into current_payload;
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
                        cloud_book_id_value::text,
                        operation_value,
                        current_payload,
                        new_revision_value
                    )
                    returning change_id into change_id_value;
                    result := jsonb_build_object(
                        'mutation_id', mutation_id_value,
                        'status', 'accepted',
                        'cloud_book_id', cloud_book_id_value,
                        'revision', new_revision_value,
                        'change_id', change_id_value
                    );
                end if;
            end if;
        elsif operation_value = 'upsert'
            and entity_type_value = 'reading_position'
        then
            content_hash_value := coalesce(
                nullif(mutation_payload ->> 'content_hash', ''),
                nullif(mutation_payload ->> 'contentHash', '')
            );
            content_hash_algorithm_value := coalesce(
                nullif(mutation_payload ->> 'content_hash_algorithm', ''),
                nullif(mutation_payload ->> 'contentHashAlgorithm', ''),
                'sha-256-v1'
            );
            library_book_id_value := coalesce(
                nullif(mutation_payload ->> 'library_book_id', ''),
                nullif(mutation_payload ->> 'libraryBookId', ''),
                case
                    when content_hash_value is not null then
                        content_hash_algorithm_value || ':' || content_hash_value
                end
            );
            if nullif(mutation_payload ->> 'cloud_book_id', '') is not null then
                cloud_book_id_value :=
                    (mutation_payload ->> 'cloud_book_id')::uuid;
            elsif content_hash_value is not null then
                select id
                into cloud_book_id_value
                from public.cloud_books
                where cloud_user_id = actor
                  and content_hash_algorithm = content_hash_algorithm_value
                  and content_hash = content_hash_value;
            else
                cloud_book_id_value := null;
            end if;
            select cloud_user_id, deleted_at
            into cloud_user_id_value, book_deleted_at_value
            from public.cloud_books
            where id = cloud_book_id_value;
            if cloud_user_id_value is distinct from actor then
                result := jsonb_build_object(
                    'mutation_id', mutation_id_value,
                    'status', 'rejected',
                    'reason', 'cloud_book_not_owned'
                );
            elsif book_deleted_at_value is not null then
                select change_row.revision, change_row.payload
                into current_revision_value, current_payload
                from public.sync_changes change_row
                where change_row.cloud_user_id = actor
                  and change_row.entity_type = 'reading_position'
                  and change_row.entity_id = cloud_book_id_value::text
                  and change_row.operation = 'delete'
                  and change_row.payload->>'is_deleted' = 'true'
                order by change_row.change_id desc
                limit 1;
                if current_revision_value is null then
                    current_revision_value := 1;
                    current_payload := jsonb_build_object(
                        'library_book_id', library_book_id_value,
                        'cloud_book_id', cloud_book_id_value,
                        'deleted_at', book_deleted_at_value,
                        'remote_revision', current_revision_value,
                        'is_deleted', true
                    );
                end if;
                result := jsonb_build_object(
                    'mutation_id', mutation_id_value,
                    'status', 'conflict',
                    'cloud_book_id', cloud_book_id_value,
                    'revision', current_revision_value,
                    'payload', current_payload,
                    'reason', 'book_deleted'
                );
            elsif library_book_id_value is null then
                result := jsonb_build_object(
                    'mutation_id', mutation_id_value,
                    'status', 'rejected',
                    'reason', 'library_book_identity_required'
                );
            elsif not mutation_payload ? 'position' then
                result := jsonb_build_object(
                    'mutation_id', mutation_id_value,
                    'status', 'rejected',
                    'reason', 'position_required'
                );
            else
                reading_payload := jsonb_build_object(
                    'cloud_book_id', cloud_book_id_value,
                    'library_book_id', library_book_id_value,
                    'position', mutation_payload -> 'position'
                );
                select revision, payload
                into current_revision_value, current_payload
                from public.reading_positions
                where cloud_book_id = cloud_book_id_value
                for update;
                if current_revision_value is null then
                    select change_row.revision, change_row.payload
                    into current_revision_value, current_payload
                    from public.sync_changes change_row
                    where change_row.cloud_user_id = actor
                      and change_row.entity_type = 'reading_position'
                      and change_row.entity_id = cloud_book_id_value::text
                      and change_row.operation = 'delete'
                      and change_row.payload->>'is_deleted' = 'true'
                    order by change_row.change_id desc
                    limit 1;
                end if;
                if current_revision_value is not null
                    and (
                        base_revision_value is null
                        or base_revision_value <> current_revision_value
                    )
                then
                    result := jsonb_build_object(
                        'mutation_id', mutation_id_value,
                        'status', 'conflict',
                        'cloud_book_id', cloud_book_id_value,
                        'revision', current_revision_value,
                        'payload', current_payload
                    );
                else
                    new_revision_value := coalesce(current_revision_value, 0) + 1;
                    insert into public.reading_positions(
                        cloud_book_id,
                        cloud_user_id,
                        payload,
                        revision
                    )
                    values (
                        cloud_book_id_value,
                        actor,
                        reading_payload,
                        new_revision_value
                    )
                    on conflict (cloud_book_id) do update
                    set payload = excluded.payload,
                        revision = excluded.revision,
                        updated_at = timezone('utc', now());
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
                        cloud_book_id_value::text,
                        operation_value,
                        reading_payload,
                        new_revision_value
                    )
                    returning change_id into change_id_value;
                    result := jsonb_build_object(
                        'mutation_id', mutation_id_value,
                        'status', 'accepted',
                        'cloud_book_id', cloud_book_id_value,
                        'revision', new_revision_value,
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
            entity_type_value,
            entity_id_value,
            result
        );
        results := results || jsonb_build_array(result);
    end loop;
    return results;
end;
$$;

revoke execute on function public.push_sync_changes(jsonb, bigint) from public, anon;
grant execute on function public.push_sync_changes(jsonb, bigint) to authenticated;


-- A book-removal mutation is accepted only after its Cloud file associations
-- have completed their own Storage deletion handshake. Delete progress in the
-- same transaction as the book tombstone so no server position survives it.
create or replace function public.delete_cloud_books(
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
    cloud_book_id_value uuid;
    book_row public.cloud_books%rowtype;
    current_payload jsonb;
    position_revision_value bigint;
    new_revision_value bigint;
    change_id_value bigint;
    existing_response jsonb;
    result jsonb;
    results jsonb := '[]'::jsonb;
begin
    if actor is null then
        raise exception 'Authentication required';
    end if;
    if jsonb_typeof(coalesce(mutations, '[]'::jsonb)) <> 'array' then
        raise exception 'mutations must be a JSON array';
    end if;

    perform pg_advisory_xact_lock(hashtextextended(actor::text, 0));

    for mutation_item in
        select value from jsonb_array_elements(coalesce(mutations, '[]'::jsonb))
    loop
        mutation_id_value := (mutation_item ->> 'mutation_id')::uuid;
        entity_type_value := mutation_item ->> 'entity_type';
        operation_value := mutation_item ->> 'operation';
        entity_id_value := mutation_item ->> 'entity_id';
        mutation_payload := mutation_item -> 'payload';
        base_revision_value := nullif(mutation_item ->> 'base_revision', '')::bigint;
        cloud_book_id_value := nullif(mutation_payload ->> 'cloud_book_id', '')::uuid;
        current_payload := null;
        position_revision_value := null;
        new_revision_value := null;
        change_id_value := null;

        select response
        into existing_response
        from public.sync_mutations as stored_mutation
        where stored_mutation.cloud_user_id = actor
          and stored_mutation.mutation_id = mutation_id_value;
        if found then
            -- Older versions cached this transient precondition as rejected.
            if existing_response->>'status' = 'rejected'
                and existing_response->>'reason' = 'book_files_must_be_removed_first'
            then
                delete from public.sync_mutations as stored_mutation
                where stored_mutation.cloud_user_id = actor
                  and stored_mutation.mutation_id = mutation_id_value;
            else
                results := results || jsonb_build_array(existing_response);
                continue;
            end if;
        end if;

        result := jsonb_build_object(
            'mutation_id', mutation_id_value,
            'status', 'rejected',
            'reason', 'unsupported_mutation'
        );

        if entity_type_value = 'library_book'
            and operation_value = 'delete'
            and cloud_book_id_value is not null
        then
            select * into book_row
            from public.cloud_books b
            where b.id = cloud_book_id_value
              and b.cloud_user_id = actor
            for update;

            if found then
                current_payload := jsonb_build_object(
                    'library_book_id',
                    book_row.content_hash_algorithm || ':' || book_row.content_hash,
                    'cloud_book_id', book_row.id,
                    'content_hash', book_row.content_hash,
                    'content_hash_algorithm', book_row.content_hash_algorithm,
                    'title', book_row.title,
                    'author', book_row.author,
                    'format', book_row.format,
                    'metadata_json', book_row.metadata::text,
                    'remote_revision', book_row.revision,
                    'deleted_at', book_row.deleted_at
                );

                if book_row.deleted_at is not null then
                    result := jsonb_build_object(
                        'mutation_id', mutation_id_value,
                        'status', 'accepted',
                        'cloud_book_id', book_row.id,
                        'revision', book_row.revision,
                        'payload', current_payload
                    );
                elsif base_revision_value is null
                    or base_revision_value <> book_row.revision
                then
                    result := jsonb_build_object(
                        'mutation_id', mutation_id_value,
                        'status', 'conflict',
                        'cloud_book_id', book_row.id,
                        'revision', book_row.revision,
                        'payload', current_payload,
                        'reason', 'stale_revision'
                    );
                elsif exists (
                    select 1 from public.cloud_book_files f
                    where f.cloud_book_id = book_row.id
                ) or exists (
                    select 1 from public.cloud_book_uploads u
                    where u.cloud_book_id = book_row.id
                      and u.status = 'reserved'
                      and u.expires_at > now()
                ) then
                    result := jsonb_build_object(
                        'mutation_id', mutation_id_value,
                        'status', 'retryable',
                        'retry_after_ms', 30000,
                        'reason', 'book_files_must_be_removed_first'
                    );
                else
                    select revision
                    into position_revision_value
                    from public.reading_positions
                    where cloud_book_id = book_row.id
                    for update;
                    position_revision_value := coalesce(position_revision_value, 0) + 1;
                    delete from public.reading_positions
                    where cloud_book_id = book_row.id;

                    update public.cloud_books
                    set deleted_at = timezone('utc', now()),
                        revision = revision + 1,
                        updated_at = timezone('utc', now())
                    where id = book_row.id
                    returning * into book_row;

                    new_revision_value := book_row.revision;
                    insert into public.sync_changes(
                        cloud_user_id,
                        entity_type,
                        entity_id,
                        operation,
                        payload,
                        revision
                    ) values (
                        actor,
                        'reading_position',
                        book_row.id::text,
                        'delete',
                        jsonb_build_object(
                            'library_book_id',
                            book_row.content_hash_algorithm || ':' || book_row.content_hash,
                            'cloud_book_id', book_row.id,
                            'deleted_at', book_row.deleted_at,
                            'remote_revision', position_revision_value,
                            'is_deleted', true
                        ),
                        position_revision_value
                    );
                    current_payload := jsonb_build_object(
                        'library_book_id',
                        book_row.content_hash_algorithm || ':' || book_row.content_hash,
                        'cloud_book_id', book_row.id,
                        'content_hash', book_row.content_hash,
                        'content_hash_algorithm', book_row.content_hash_algorithm,
                        'title', book_row.title,
                        'author', book_row.author,
                        'format', book_row.format,
                        'metadata_json', book_row.metadata::text,
                        'remote_revision', new_revision_value,
                        'deleted_at', book_row.deleted_at
                    );
                    insert into public.sync_changes(
                        cloud_user_id,
                        entity_type,
                        entity_id,
                        operation,
                        payload,
                        revision
                    ) values (
                        actor,
                        'library_book_deleted',
                        book_row.id::text,
                        'delete',
                        current_payload,
                        new_revision_value
                    ) returning change_id into change_id_value;
                    result := jsonb_build_object(
                        'mutation_id', mutation_id_value,
                        'status', 'accepted',
                        'cloud_book_id', book_row.id,
                        'revision', new_revision_value,
                        'change_id', change_id_value,
                        'payload', current_payload
                    );
                end if;
            else
                result := jsonb_build_object(
                    'mutation_id', mutation_id_value,
                    'status', 'rejected',
                    'reason', 'cloud_book_not_owned'
                );
            end if;
        end if;

        -- Let the same outbox mutation re-evaluate after its bounded retry delay.
        if result->>'status' <> 'retryable' then
            insert into public.sync_mutations(
                cloud_user_id,
                mutation_id,
                entity_type,
                entity_id,
                response
            ) values (
                actor,
                mutation_id_value,
                entity_type_value,
                entity_id_value,
                result
            );
        end if;
        results := results || jsonb_build_array(result);
    end loop;
    return results;
end;
$$;

revoke all on function public.delete_cloud_books(jsonb, bigint) from public, anon;
grant execute on function public.delete_cloud_books(jsonb, bigint) to authenticated;
