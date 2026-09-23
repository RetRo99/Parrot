-- Canonical RPC definitions for the Parrot Cloud development schema.

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
                select id, revision
                into cloud_book_id_value, current_revision_value
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
                        'remote_revision', revision
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
                        'remote_revision', new_revision_value
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
            select cloud_user_id
            into cloud_user_id_value
            from public.cloud_books
            where id = cloud_book_id_value;
            if cloud_user_id_value is distinct from actor then
                result := jsonb_build_object(
                    'mutation_id', mutation_id_value,
                    'status', 'rejected',
                    'reason', 'cloud_book_not_owned'
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

create or replace function public.pull_sync_changes(
    cursor bigint default 0,
    "limit" integer default 100
)
returns jsonb
language plpgsql
security definer
set search_path = public
as $$
declare
    actor uuid := auth.uid();
    effective_cursor bigint := greatest(coalesce(cursor, 0), 0);
    effective_limit integer := least(greatest(coalesce("limit", 100), 1), 100);
    changes jsonb;
    next_cursor bigint;
    has_more boolean;
begin
    if actor is null then
        raise exception 'Authentication required';
    end if;

    -- Match the push lock so a cursor cannot advance past an uncommitted
    -- lower change_id allocated by another mutation transaction.
    perform pg_advisory_xact_lock(hashtextextended(actor::text, 0));

    select coalesce(
        jsonb_agg(to_jsonb(change_row) order by change_id),
        '[]'::jsonb
    )
    into changes
    from (
        select
            change_id,
            entity_type,
            entity_id,
            operation,
            payload,
            revision,
            created_at
        from public.sync_changes
        where cloud_user_id = actor
          and change_id > effective_cursor
        order by change_id
        limit effective_limit
    ) as change_row;

    select coalesce(
        max((change_value.value ->> 'change_id')::bigint),
        effective_cursor
    )
    into next_cursor
    from jsonb_array_elements(changes) as change_value(value);

    select exists(
        select 1
        from public.sync_changes
        where cloud_user_id = actor
          and change_id > next_cursor
    )
    into has_more;

    return jsonb_build_object(
        'changes', changes,
        'next_cursor', next_cursor,
        'has_more', has_more
    );
end;
$$;

revoke execute on function public.push_sync_changes(jsonb, bigint) from public, anon;
revoke execute on function public.pull_sync_changes(bigint, integer) from public, anon;
grant execute on function public.push_sync_changes(jsonb, bigint) to authenticated;
grant execute on function public.pull_sync_changes(bigint, integer) to authenticated;

create or replace function public.reserve_book_upload(
    cloud_book_id uuid,
    media_type text,
    relative_path text,
    file_name text,
    size_bytes bigint,
    content_hash_algorithm text,
    content_hash text,
    rights_attestation jsonb
)
returns jsonb
language plpgsql
security definer
set search_path = public
as $$
declare
    actor uuid := auth.uid();
    book_owner uuid;
    file_id uuid;
    file_path text;
    file_status text;
    file_revision bigint;
    upload_row public.cloud_book_uploads%rowtype;
    quota_row public.cloud_user_storage%rowtype;
    event_id bigint;
    expires_at_value timestamptz := timezone('utc', now()) + interval '24 hours';
begin
    if actor is null then
        raise exception 'Authentication required';
    end if;
    perform pg_advisory_xact_lock(hashtextextended(actor::text, 0));

    if media_type is null or btrim(media_type) = ''
        or file_name is null or btrim(file_name) = ''
        or file_name ~ '[/\\]'
        or size_bytes is null or size_bytes < 0
        or content_hash_algorithm is null or btrim(content_hash_algorithm) = ''
        or content_hash is null or btrim(content_hash) = ''
        or relative_path is null
        or relative_path like '/%'
        or relative_path ~ '(^|/)\.\.(/|$)'
        or relative_path ~ E'\\\\'
    then
        return jsonb_build_object('status', 'rejected', 'reason', 'invalid_upload_metadata');
    end if;

    if rights_attestation is null
        or jsonb_typeof(rights_attestation) is distinct from 'object'
        or not (rights_attestation ?& array['attested_at', 'tos_version', 'attestation_version'])
        or nullif(rights_attestation ->> 'attested_at', '') is null
        or nullif(rights_attestation ->> 'tos_version', '') is null
        or nullif(rights_attestation ->> 'attestation_version', '') is null
    then
        return jsonb_build_object('status', 'rejected', 'reason', 'attestation_required');
    end if;

    select cloud_user_id into book_owner
    from public.cloud_books
    where id = cloud_book_id and deleted_at is null
    for update;
    if book_owner is distinct from actor then
        return jsonb_build_object('status', 'rejected', 'reason', 'cloud_book_not_owned');
    end if;

    if exists (
        select 1 from public.cloud_content_blocklist b
        where b.content_hash_algorithm = reserve_book_upload.content_hash_algorithm
          and b.content_hash = reserve_book_upload.content_hash
    ) then
        return jsonb_build_object('status', 'rejected', 'reason', 'content_blocked');
    end if;

    insert into public.cloud_user_storage(cloud_user_id)
    values (actor)
    on conflict (cloud_user_id) do nothing;
    select * into quota_row from public.cloud_user_storage
    where cloud_user_id = actor for update;

    -- Expired reservations are released lazily when this account next uploads.
    for upload_row in
        select * from public.cloud_book_uploads
        where cloud_user_id = actor and status = 'reserved' and expires_at <= now()
        for update
    loop
        update public.cloud_user_storage
        set reserved_bytes = greatest(0, reserved_bytes - upload_row.size_bytes),
            updated_at = timezone('utc', now())
        where cloud_user_id = actor;
        update public.cloud_book_uploads
        set status = 'expired', updated_at = timezone('utc', now())
        where public.cloud_book_uploads.upload_id = upload_row.upload_id;
        update public.cloud_book_files
        set status = 'upload_failed', revision = revision + 1,
            updated_at = timezone('utc', now())
        where id = upload_row.cloud_book_file_id
          and status in ('upload_pending', 'uploading');
        if found then
            insert into public.sync_changes(
                cloud_user_id, entity_type, entity_id, operation, payload, revision
            )
            select actor, 'book_file', f.id::text, 'upsert',
                jsonb_build_object(
                    'cloud_book_id', f.cloud_book_id,
                    'cloud_book_file_id', f.id,
                    'media_type', f.media_type,
                    'relative_path', f.relative_path,
                    'file_name', f.file_name,
                    'status', f.status,
                    'size_bytes', f.size_bytes,
                    'content_hash', f.content_hash,
                    'content_hash_algorithm', f.content_hash_algorithm,
                    'remote_revision', f.revision
                ), f.revision
            from public.cloud_book_files f where f.id = upload_row.cloud_book_file_id;
        end if;
        insert into public.cloud_file_audit_events(
            cloud_user_id, cloud_book_id, cloud_book_file_id, upload_id,
            content_hash_algorithm, content_hash, action, reason, actor
        ) values (
            actor, upload_row.cloud_book_id, upload_row.cloud_book_file_id,
            upload_row.upload_id, upload_row.content_hash_algorithm,
            upload_row.content_hash, 'expire', 'reservation_expired', actor::text
        );
    end loop;

    select id, storage_path, status, revision
    into file_id, file_path, file_status, file_revision
    from public.cloud_book_files
    where public.cloud_book_files.cloud_book_id = reserve_book_upload.cloud_book_id
      and cloud_book_files.media_type = reserve_book_upload.media_type
      and cloud_book_files.relative_path = coalesce(reserve_book_upload.relative_path, '')
    for update;

    if file_id is not null then
        if file_status = 'available' then
            if exists (
                select 1 from public.cloud_book_files f
                where f.id = file_id
                  and f.content_hash_algorithm = reserve_book_upload.content_hash_algorithm
                  and f.content_hash = reserve_book_upload.content_hash
            ) then
                return jsonb_build_object(
                    'status', 'already_available',
                    'cloud_book_file_id', file_id,
                    'storage_path', file_path,
                    'size_bytes', (select f.size_bytes from public.cloud_book_files f where f.id = file_id),
                    'content_hash', (select f.content_hash from public.cloud_book_files f where f.id = file_id),
                    'content_hash_algorithm', (select f.content_hash_algorithm from public.cloud_book_files f where f.id = file_id)
                );
            end if;
            return jsonb_build_object('status', 'rejected', 'reason', 'file_exists');
        end if;

        if exists (
            select 1 from public.cloud_book_files f
            where f.id = file_id
              and (f.content_hash_algorithm <> reserve_book_upload.content_hash_algorithm
                   or f.content_hash <> reserve_book_upload.content_hash)
        ) then
            return jsonb_build_object('status', 'rejected', 'reason', 'file_exists');
        end if;

        select * into upload_row
        from public.cloud_book_uploads u
        where u.cloud_book_file_id = file_id and u.status = 'reserved'
          and u.expires_at > now()
        order by u.created_at desc limit 1
        for update;
        if found then
            return jsonb_build_object(
                'status', 'reserved',
                'upload_id', upload_row.upload_id,
                'cloud_book_file_id', file_id,
                'storage_path', file_path,
                'upload_url', coalesce(
                    current_setting('app.settings.storage_tus_endpoint', true),
                    '/storage/v1/upload/resumable'
                ),
                'expires_at', upload_row.expires_at
            );
        end if;

        if file_status = 'deleting' then
            return jsonb_build_object('status', 'rejected', 'reason', 'file_exists');
        end if;
    else
        file_id := gen_random_uuid();
        file_path := 'users/' || actor::text || '/books/' || cloud_book_id::text || '/' || file_id::text;
        file_revision := 0;
    end if;

    if quota_row.used_bytes + quota_row.reserved_bytes + size_bytes > quota_row.quota_bytes then
        return jsonb_build_object(
            'status', 'rejected',
            'reason', 'quota_exceeded',
            'used_bytes', quota_row.used_bytes,
            'reserved_bytes', quota_row.reserved_bytes,
            'quota_bytes', quota_row.quota_bytes
        );
    end if;

    if file_revision = 0 then
        insert into public.cloud_book_files(
            id, cloud_book_id, cloud_user_id, storage_path, relative_path, file_name,
            size_bytes, content_hash, content_hash_algorithm, media_type, status
        ) values (
            file_id, cloud_book_id, actor, file_path, coalesce(relative_path, ''), file_name,
            size_bytes, content_hash, content_hash_algorithm, media_type, 'upload_pending'
        );
        file_revision := 1;
    else
        update public.cloud_book_files
        set file_name = reserve_book_upload.file_name,
            size_bytes = reserve_book_upload.size_bytes,
            status = 'upload_pending',
            revision = revision + 1,
            updated_at = timezone('utc', now())
        where id = file_id
        returning revision into file_revision;
    end if;

    insert into public.cloud_book_uploads(
        cloud_book_id, cloud_user_id, cloud_book_file_id, media_type, relative_path,
        file_name, size_bytes, content_hash, content_hash_algorithm,
        rights_attestation, storage_path, status, expires_at
    ) values (
        cloud_book_id, actor, file_id, media_type, coalesce(relative_path, ''),
        file_name, size_bytes, content_hash, content_hash_algorithm,
        rights_attestation, file_path, 'reserved', expires_at_value
    ) returning * into upload_row;

    update public.cloud_user_storage
    set reserved_bytes = reserved_bytes + size_bytes,
        updated_at = timezone('utc', now())
    where cloud_user_id = actor;

    insert into public.sync_changes(
        cloud_user_id, entity_type, entity_id, operation, payload, revision
    ) values (
        actor, 'book_file', file_id::text, 'upsert',
        jsonb_build_object(
            'cloud_book_id', cloud_book_id,
            'cloud_book_file_id', file_id,
            'media_type', media_type,
            'relative_path', coalesce(relative_path, ''),
            'file_name', file_name,
            'status', 'upload_pending',
            'size_bytes', size_bytes,
            'content_hash', content_hash,
            'content_hash_algorithm', content_hash_algorithm,
            'remote_revision', file_revision
        ), file_revision
    ) returning change_id into event_id;

    insert into public.cloud_file_audit_events(
        cloud_user_id, cloud_book_id, cloud_book_file_id, upload_id,
        content_hash_algorithm, content_hash, action, actor
    ) values (
        actor, cloud_book_id, file_id, upload_row.upload_id,
        content_hash_algorithm, content_hash, 'reserve', actor::text
    );

    return jsonb_build_object(
        'status', 'reserved',
        'upload_id', upload_row.upload_id,
        'cloud_book_file_id', file_id,
        'storage_path', file_path,
        'upload_url', coalesce(
            current_setting('app.settings.storage_tus_endpoint', true),
            '/storage/v1/upload/resumable'
        ),
        'expires_at', expires_at_value
    );
end;
$$;

create or replace function public.finalize_book_upload(
    upload_id uuid,
    size_bytes bigint,
    content_hash text
)
returns jsonb
language plpgsql
security definer
set search_path = public
as $$
declare
    actor uuid := auth.uid();
    upload_row public.cloud_book_uploads%rowtype;
    file_row public.cloud_book_files%rowtype;
    object_size bigint;
begin
    if actor is null then
        raise exception 'Authentication required';
    end if;
    perform pg_advisory_xact_lock(hashtextextended(actor::text, 0));

    select * into upload_row
    from public.cloud_book_uploads u
    where u.upload_id = finalize_book_upload.upload_id
      and u.cloud_user_id = actor
    for update;
    if not found then
        return jsonb_build_object('status', 'rejected', 'reason', 'upload_not_found');
    end if;

    if upload_row.status = 'finalized' then
        select * into file_row from public.cloud_book_files f
        where f.id = upload_row.cloud_book_file_id;
        return jsonb_build_object(
            'status', 'available',
            'cloud_book_file_id', file_row.id,
            'storage_path', file_row.storage_path,
            'size_bytes', file_row.size_bytes,
            'content_hash', file_row.content_hash,
            'content_hash_algorithm', file_row.content_hash_algorithm,
            'revision', file_row.revision
        );
    end if;
    if upload_row.status <> 'reserved' then
        return jsonb_build_object('status', 'rejected', 'reason', 'upload_not_reserved');
    end if;

    if upload_row.expires_at <= now() then
        update public.cloud_user_storage
        set reserved_bytes = greatest(0, reserved_bytes - upload_row.size_bytes),
            updated_at = timezone('utc', now())
        where cloud_user_id = actor;
        update public.cloud_book_uploads
        set status = 'expired', updated_at = timezone('utc', now())
        where upload_id = upload_row.upload_id;
        update public.cloud_book_files
        set status = 'upload_failed', revision = revision + 1,
            updated_at = timezone('utc', now())
        where id = upload_row.cloud_book_file_id
        returning * into file_row;
        insert into public.sync_changes(
            cloud_user_id, entity_type, entity_id, operation, payload, revision
        ) values (
            actor, 'book_file', file_row.id::text, 'upsert',
            jsonb_build_object(
                'cloud_book_id', file_row.cloud_book_id,
                'cloud_book_file_id', file_row.id,
                'media_type', file_row.media_type,
                'relative_path', file_row.relative_path,
                'file_name', file_row.file_name,
                'status', 'upload_failed',
                'size_bytes', file_row.size_bytes,
                'content_hash', file_row.content_hash,
                'content_hash_algorithm', file_row.content_hash_algorithm,
                'remote_revision', file_row.revision
            ), file_row.revision
        );
        insert into public.cloud_file_audit_events(
            cloud_user_id, cloud_book_id, cloud_book_file_id, upload_id,
            content_hash_algorithm, content_hash, action, reason, actor
        ) values (
            actor, upload_row.cloud_book_id, upload_row.cloud_book_file_id,
            upload_row.upload_id, upload_row.content_hash_algorithm,
            upload_row.content_hash, 'expire', 'reservation_expired', actor::text
        );
        return jsonb_build_object('status', 'rejected', 'reason', 'upload_expired');
    end if;

    if exists (
        select 1 from public.cloud_content_blocklist b
        where b.content_hash_algorithm = upload_row.content_hash_algorithm
          and b.content_hash = upload_row.content_hash
    ) then
        update public.cloud_user_storage
        set reserved_bytes = greatest(0, reserved_bytes - upload_row.size_bytes),
            updated_at = timezone('utc', now())
        where cloud_user_id = actor;
        update public.cloud_book_uploads set status = 'failed', updated_at = timezone('utc', now())
        where public.cloud_book_uploads.upload_id = upload_row.upload_id;
        update public.cloud_book_files set status = 'upload_failed', revision = revision + 1,
            updated_at = timezone('utc', now())
        where id = upload_row.cloud_book_file_id returning * into file_row;
        insert into public.sync_changes(
            cloud_user_id, entity_type, entity_id, operation, payload, revision
        ) values (
            actor, 'book_file', file_row.id::text, 'upsert',
            jsonb_build_object(
                'cloud_book_id', file_row.cloud_book_id,
                'cloud_book_file_id', file_row.id,
                'media_type', file_row.media_type,
                'relative_path', file_row.relative_path,
                'file_name', file_row.file_name,
                'status', 'upload_failed',
                'size_bytes', file_row.size_bytes,
                'content_hash', file_row.content_hash,
                'content_hash_algorithm', file_row.content_hash_algorithm,
                'remote_revision', file_row.revision
            ), file_row.revision
        );
        insert into public.cloud_file_audit_events(
            cloud_user_id, cloud_book_id, cloud_book_file_id, upload_id,
            content_hash_algorithm, content_hash, action, reason, actor
        ) values (
            actor, upload_row.cloud_book_id, upload_row.cloud_book_file_id,
            upload_row.upload_id, upload_row.content_hash_algorithm,
            upload_row.content_hash, 'finalize', 'content_blocked', actor::text
        );
        return jsonb_build_object('status', 'rejected', 'reason', 'content_blocked');
    end if;

    if content_hash is distinct from upload_row.content_hash then
        update public.cloud_user_storage
        set reserved_bytes = greatest(0, reserved_bytes - upload_row.size_bytes),
            updated_at = timezone('utc', now())
        where cloud_user_id = actor;
        update public.cloud_book_uploads set status = 'failed', updated_at = timezone('utc', now())
        where public.cloud_book_uploads.upload_id = upload_row.upload_id;
        update public.cloud_book_files set status = 'upload_failed', revision = revision + 1,
            updated_at = timezone('utc', now())
        where id = upload_row.cloud_book_file_id returning * into file_row;
        insert into public.sync_changes(
            cloud_user_id, entity_type, entity_id, operation, payload, revision
        ) values (
            actor, 'book_file', file_row.id::text, 'upsert',
            jsonb_build_object(
                'cloud_book_id', file_row.cloud_book_id,
                'cloud_book_file_id', file_row.id,
                'media_type', file_row.media_type,
                'relative_path', file_row.relative_path,
                'file_name', file_row.file_name,
                'status', 'upload_failed',
                'size_bytes', file_row.size_bytes,
                'content_hash', file_row.content_hash,
                'content_hash_algorithm', file_row.content_hash_algorithm,
                'remote_revision', file_row.revision
            ), file_row.revision
        );
        insert into public.cloud_file_audit_events(
            cloud_user_id, cloud_book_id, cloud_book_file_id, upload_id,
            content_hash_algorithm, content_hash, action, reason, actor
        ) values (
            actor, upload_row.cloud_book_id, upload_row.cloud_book_file_id,
            upload_row.upload_id, upload_row.content_hash_algorithm,
            upload_row.content_hash, 'finalize', 'content_hash_mismatch', actor::text
        );
        return jsonb_build_object('status', 'rejected', 'reason', 'content_hash_mismatch');
    end if;

    select (o.metadata->>'size')::bigint into object_size
    from storage.objects o
    where o.bucket_id = 'book-files' and o.name = upload_row.storage_path;
    if not found then
        return jsonb_build_object('status', 'rejected', 'reason', 'upload_incomplete');
    end if;

    if size_bytes is distinct from upload_row.size_bytes
        or object_size is distinct from upload_row.size_bytes
    then
        update public.cloud_user_storage
        set reserved_bytes = greatest(0, reserved_bytes - upload_row.size_bytes),
            updated_at = timezone('utc', now())
        where cloud_user_id = actor;
        update public.cloud_book_uploads set status = 'failed', updated_at = timezone('utc', now())
        where public.cloud_book_uploads.upload_id = upload_row.upload_id;
        update public.cloud_book_files set status = 'upload_failed', revision = revision + 1,
            updated_at = timezone('utc', now())
        where id = upload_row.cloud_book_file_id returning * into file_row;
        insert into public.sync_changes(
            cloud_user_id, entity_type, entity_id, operation, payload, revision
        ) values (
            actor, 'book_file', file_row.id::text, 'upsert',
            jsonb_build_object(
                'cloud_book_id', file_row.cloud_book_id,
                'cloud_book_file_id', file_row.id,
                'media_type', file_row.media_type,
                'relative_path', file_row.relative_path,
                'file_name', file_row.file_name,
                'status', 'upload_failed',
                'size_bytes', file_row.size_bytes,
                'content_hash', file_row.content_hash,
                'content_hash_algorithm', file_row.content_hash_algorithm,
                'remote_revision', file_row.revision
            ), file_row.revision
        );
        insert into public.cloud_file_audit_events(
            cloud_user_id, cloud_book_id, cloud_book_file_id, upload_id,
            content_hash_algorithm, content_hash, action, reason, actor
        ) values (
            actor, upload_row.cloud_book_id, upload_row.cloud_book_file_id,
            upload_row.upload_id, upload_row.content_hash_algorithm,
            upload_row.content_hash, 'finalize', 'size_mismatch', actor::text
        );
        return jsonb_build_object('status', 'rejected', 'reason', 'size_mismatch');
    end if;
    update public.cloud_book_files
    set status = 'available', revision = revision + 1, updated_at = timezone('utc', now())
    where id = upload_row.cloud_book_file_id
    returning * into file_row;
    update public.cloud_book_uploads
    set status = 'finalized', updated_at = timezone('utc', now())
    where public.cloud_book_uploads.upload_id = upload_row.upload_id;
    update public.cloud_user_storage
    set reserved_bytes = greatest(0, reserved_bytes - upload_row.size_bytes),
        used_bytes = used_bytes + upload_row.size_bytes,
        updated_at = timezone('utc', now())
    where cloud_user_id = actor;

    insert into public.sync_changes(
        cloud_user_id, entity_type, entity_id, operation, payload, revision
    ) values (
        actor, 'book_file', file_row.id::text, 'upsert',
        jsonb_build_object(
            'cloud_book_id', file_row.cloud_book_id,
            'cloud_book_file_id', file_row.id,
            'media_type', file_row.media_type,
            'relative_path', file_row.relative_path,
            'file_name', file_row.file_name,
            'status', 'available',
            'size_bytes', file_row.size_bytes,
            'content_hash', file_row.content_hash,
            'content_hash_algorithm', file_row.content_hash_algorithm,
            'remote_revision', file_row.revision
        ), file_row.revision
    );
    insert into public.cloud_file_audit_events(
        cloud_user_id, cloud_book_id, cloud_book_file_id, upload_id,
        content_hash_algorithm, content_hash, action, actor
    ) values (
        actor, file_row.cloud_book_id, file_row.id, upload_row.upload_id,
        file_row.content_hash_algorithm, file_row.content_hash, 'finalize', actor::text
    );

    return jsonb_build_object(
        'status', 'available',
        'cloud_book_file_id', file_row.id,
        'storage_path', file_row.storage_path,
        'size_bytes', file_row.size_bytes,
        'content_hash', file_row.content_hash,
        'content_hash_algorithm', file_row.content_hash_algorithm,
        'revision', file_row.revision
    );
end;
$$;

create or replace function public.cancel_book_upload(upload_id uuid)
returns jsonb
language plpgsql
security definer
set search_path = public
as $$
declare
    actor uuid := auth.uid();
    upload_row public.cloud_book_uploads%rowtype;
begin
    if actor is null then
        raise exception 'Authentication required';
    end if;
    perform pg_advisory_xact_lock(hashtextextended(actor::text, 0));
    select * into upload_row from public.cloud_book_uploads u
    where u.upload_id = cancel_book_upload.upload_id and u.cloud_user_id = actor
    for update;
    if not found then
        return jsonb_build_object('status', 'rejected', 'reason', 'upload_not_found');
    end if;
    if upload_row.status = 'cancelled' then
        return jsonb_build_object('status', 'cancelled', 'upload_id', upload_row.upload_id);
    end if;
    if upload_row.status = 'finalized' then
        return jsonb_build_object('status', 'rejected', 'reason', 'upload_already_finalized');
    end if;

    if upload_row.status = 'reserved' then
        update public.cloud_user_storage
        set reserved_bytes = greatest(0, reserved_bytes - upload_row.size_bytes),
            updated_at = timezone('utc', now())
        where cloud_user_id = actor;
    end if;
    update public.cloud_book_uploads
    set status = 'cancelled', updated_at = timezone('utc', now())
    where public.cloud_book_uploads.upload_id = upload_row.upload_id;
    delete from public.cloud_book_files f
    where f.id = upload_row.cloud_book_file_id and f.status <> 'available';

    insert into public.sync_changes(
        cloud_user_id, entity_type, entity_id, operation, payload, revision
    ) values (
        actor, 'book_file', upload_row.cloud_book_file_id::text, 'delete',
        jsonb_build_object(
            'cloud_book_id', upload_row.cloud_book_id,
            'cloud_book_file_id', upload_row.cloud_book_file_id,
            'media_type', upload_row.media_type,
            'relative_path', upload_row.relative_path,
            'status', 'none'
        ), 0
    );
    insert into public.cloud_file_audit_events(
        cloud_user_id, cloud_book_id, cloud_book_file_id, upload_id,
        content_hash_algorithm, content_hash, action, actor
    ) values (
        actor, upload_row.cloud_book_id, upload_row.cloud_book_file_id,
        upload_row.upload_id, upload_row.content_hash_algorithm,
        upload_row.content_hash, 'cancel', actor::text
    );
    return jsonb_build_object('status', 'cancelled', 'upload_id', upload_row.upload_id);
end;
$$;

create or replace function public.create_book_download(cloud_book_file_id uuid)
returns jsonb
language plpgsql
security definer
set search_path = public
as $$
declare
    actor uuid := auth.uid();
    file_row public.cloud_book_files%rowtype;
begin
    if actor is null then
        raise exception 'Authentication required';
    end if;
    perform pg_advisory_xact_lock(hashtextextended(actor::text, 0));
    select * into file_row from public.cloud_book_files f
    where f.id = create_book_download.cloud_book_file_id
      and f.cloud_user_id = actor
    for update;
    if not found then
        return jsonb_build_object('status', 'rejected', 'reason', 'cloud_book_file_not_owned');
    end if;
    if file_row.status <> 'available' then
        return jsonb_build_object('status', 'rejected', 'reason', 'file_not_available');
    end if;
    if exists (
        select 1 from public.cloud_content_blocklist b
        where b.content_hash_algorithm = file_row.content_hash_algorithm
          and b.content_hash = file_row.content_hash
    ) then
        return jsonb_build_object('status', 'rejected', 'reason', 'content_blocked');
    end if;

    insert into public.cloud_file_audit_events(
        cloud_user_id, cloud_book_id, cloud_book_file_id,
        content_hash_algorithm, content_hash, action, actor
    ) values (
        actor, file_row.cloud_book_id, file_row.id,
        file_row.content_hash_algorithm, file_row.content_hash,
        'download_grant', actor::text
    );
    return jsonb_build_object(
        'status', 'available',
        'cloud_book_file_id', file_row.id,
        'storage_path', file_row.storage_path,
        'size_bytes', file_row.size_bytes,
        'content_hash', file_row.content_hash,
        'content_hash_algorithm', file_row.content_hash_algorithm,
        'media_type', file_row.media_type,
        'relative_path', file_row.relative_path,
        'file_name', file_row.file_name
    );
end;
$$;

revoke execute on function public.reserve_book_upload(uuid, text, text, text, bigint, text, text, jsonb) from public, anon;
revoke execute on function public.finalize_book_upload(uuid, bigint, text) from public, anon;
revoke execute on function public.cancel_book_upload(uuid) from public, anon;
revoke execute on function public.create_book_download(uuid) from public, anon;
grant execute on function public.reserve_book_upload(uuid, text, text, text, bigint, text, text, jsonb) to authenticated;
grant execute on function public.finalize_book_upload(uuid, bigint, text) to authenticated;
grant execute on function public.cancel_book_upload(uuid) to authenticated;
grant execute on function public.create_book_download(uuid) to authenticated;
