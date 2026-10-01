-- Client-chosen book ids. The book id is a random UUID chosen by the client and
-- stored as cloud_books.id; the content hash of the source file is only a
-- duplicate hint (source_content_hash) and never identifies a book.
--
-- Parrot Cloud was never released. This migration assumes a clean slate: it
-- deletes every user's Parrot data. Do not apply it to a database with real
-- users. Storage objects in the book-files bucket cannot be removed from SQL;
-- empty the bucket through the Storage API when applying this remotely.

-- 1. Clean slate. cloud_books cascades to files, uploads, GC claims and
--    positions. Admin configuration (the content-hash blocklist) is kept.
truncate table
    public.cloud_books,
    public.cloud_book_files,
    public.cloud_book_uploads,
    public.cloud_book_file_gc_claims,
    public.reading_positions,
    public.cloud_reading_sessions,
    public.cloud_user_storage,
    public.cloud_file_audit_events,
    public.cloud_account_deletion_requests,
    public.sync_changes,
    public.sync_mutations
restart identity cascade;

-- 2. The book id comes from the client; the hash is only a duplicate hint.
alter table public.cloud_books
    drop constraint cloud_books_cloud_user_id_content_hash_algorithm_content_ha_key;
alter table public.cloud_books rename column content_hash to source_content_hash;
alter table public.cloud_books
    rename column content_hash_algorithm to source_content_hash_algorithm;
alter table public.cloud_books
    alter column id drop default,
    alter column source_content_hash drop not null,
    alter column source_content_hash_algorithm drop not null,
    alter column format drop not null;
create index cloud_books_source_hash_idx
    on public.cloud_books (cloud_user_id, source_content_hash_algorithm, source_content_hash)
    where deleted_at is null;
create index cloud_book_files_user_hash_idx
    on public.cloud_book_files (cloud_user_id, content_hash_algorithm, content_hash);

-- 3. One payload shape for every library_book change, conflict and duplicate.
create or replace function public.cloud_book_sync_payload(book_id uuid)
returns jsonb
language sql
stable
set search_path = public
as $$
    select jsonb_build_object(
        'library_book_id', book.id::text,
        'source_content_hash', book.source_content_hash,
        'source_content_hash_algorithm', book.source_content_hash_algorithm,
        'title', book.title,
        'author', book.author,
        'format', book.format,
        'remote_revision', book.revision
    )
    from public.cloud_books as book
    where book.id = book_id;
$$;

revoke execute on function public.cloud_book_sync_payload(uuid)
from public, anon, authenticated;

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
    duplicate_book_id_value uuid;
    reading_payload jsonb;
    session_id_value text;
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
        session_id_value := null;
        new_revision_value := null;
        change_id_value := null;
        duplicate_book_id_value := null;
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
            -- The client chooses the book id. The source hash is only a
            -- duplicate hint and never identifies a book.
            begin
                cloud_book_id_value := (mutation_payload ->> 'library_book_id')::uuid;
            exception when invalid_text_representation then
                cloud_book_id_value := null;
            end;
            content_hash_value := nullif(mutation_payload ->> 'source_content_hash', '');
            content_hash_algorithm_value := nullif(
                mutation_payload ->> 'source_content_hash_algorithm',
                ''
            );
            if content_hash_value is null then
                content_hash_algorithm_value := null;
            end if;
            title_value := mutation_payload ->> 'title';
            author_value := mutation_payload ->> 'author';
            format_value := mutation_payload ->> 'format';
            metadata_value := coalesce(mutation_payload -> 'metadata', '{}'::jsonb);
            if mutation_payload ? 'metadata_json'
                and nullif(mutation_payload ->> 'metadata_json', '') is not null
            then
                metadata_value := (mutation_payload ->> 'metadata_json')::jsonb;
            end if;

            if cloud_book_id_value is null then
                result := jsonb_build_object(
                    'mutation_id', mutation_id_value,
                    'status', 'rejected',
                    'reason', 'invalid_library_book_id'
                );
            elsif title_value is null then
                result := jsonb_build_object(
                    'mutation_id', mutation_id_value,
                    'status', 'rejected',
                    'reason', 'book_identity_required'
                );
            else
                select book.revision, book.cloud_user_id
                into current_revision_value, cloud_user_id_value
                from public.cloud_books as book
                where book.id = cloud_book_id_value
                for update;

                if cloud_user_id_value is not null and cloud_user_id_value <> actor then
                    result := jsonb_build_object(
                        'mutation_id', mutation_id_value,
                        'status', 'rejected',
                        'reason', 'library_book_id_conflict'
                    );
                elsif cloud_user_id_value is null then
                    duplicate_book_id_value := null;
                    if content_hash_value is not null then
                        select book.id
                        into duplicate_book_id_value
                        from public.cloud_books as book
                        where book.cloud_user_id = actor
                          and book.deleted_at is null
                          and book.source_content_hash_algorithm = content_hash_algorithm_value
                          and book.source_content_hash = content_hash_value
                        order by book.created_at
                        limit 1;

                        if duplicate_book_id_value is null then
                            select file.cloud_book_id
                            into duplicate_book_id_value
                            from public.cloud_book_files as file
                            join public.cloud_books as book
                              on book.id = file.cloud_book_id
                            where file.cloud_user_id = actor
                              and book.deleted_at is null
                              and file.status in ('available', 'upload_pending', 'uploading')
                              and file.content_hash_algorithm = content_hash_algorithm_value
                              and file.content_hash = content_hash_value
                            order by file.created_at
                            limit 1;
                        end if;
                    end if;

                    if duplicate_book_id_value is not null then
                        result := jsonb_build_object(
                            'mutation_id', mutation_id_value,
                            'status', 'duplicate',
                            'library_book_id', cloud_book_id_value,
                            'existing_book_id', duplicate_book_id_value,
                            'payload', public.cloud_book_sync_payload(duplicate_book_id_value)
                        );
                    else
                        insert into public.cloud_books(
                            id,
                            cloud_user_id,
                            source_content_hash,
                            source_content_hash_algorithm,
                            title,
                            author,
                            format,
                            metadata
                        )
                        values (
                            cloud_book_id_value,
                            actor,
                            content_hash_value,
                            content_hash_algorithm_value,
                            title_value,
                            author_value,
                            format_value,
                            metadata_value
                        )
                        returning revision into new_revision_value;
                    end if;
                elsif base_revision_value is null
                    or base_revision_value <> current_revision_value
                then
                    result := jsonb_build_object(
                        'mutation_id', mutation_id_value,
                        'status', 'conflict',
                        'library_book_id', cloud_book_id_value,
                        'cloud_book_id', cloud_book_id_value,
                        'revision', current_revision_value,
                        'payload', public.cloud_book_sync_payload(cloud_book_id_value)
                    );
                else
                    new_revision_value := current_revision_value + 1;
                    update public.cloud_books as book
                    set title = title_value,
                        author = author_value,
                        format = format_value,
                        metadata = metadata_value,
                        source_content_hash = coalesce(
                            content_hash_value,
                            book.source_content_hash
                        ),
                        source_content_hash_algorithm = coalesce(
                            content_hash_algorithm_value,
                            book.source_content_hash_algorithm
                        ),
                        revision = new_revision_value,
                        updated_at = timezone('utc', now()),
                        deleted_at = null
                    where book.id = cloud_book_id_value;
                end if;

                if new_revision_value is not null then
                    current_payload := public.cloud_book_sync_payload(cloud_book_id_value);
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
                        'library_book_id', cloud_book_id_value,
                        'cloud_book_id', cloud_book_id_value,
                        'revision', new_revision_value,
                        'change_id', change_id_value
                    );
                end if;
            end if;
        elsif operation_value = 'upsert'
            and entity_type_value = 'reading_position'
        then
            -- Positions name their book by the client book id, which is
            -- cloud_books.id.
            begin
                cloud_book_id_value := (mutation_payload ->> 'library_book_id')::uuid;
            exception when invalid_text_representation then
                cloud_book_id_value := null;
            end;
            library_book_id_value := cloud_book_id_value::text;
            select book.cloud_user_id
            into cloud_user_id_value
            from public.cloud_books as book
            where book.id = cloud_book_id_value;
            if cloud_book_id_value is null then
                result := jsonb_build_object(
                    'mutation_id', mutation_id_value,
                    'status', 'rejected',
                    'reason', 'invalid_library_book_id'
                );
            elsif cloud_user_id_value is distinct from actor then
                result := jsonb_build_object(
                    'mutation_id', mutation_id_value,
                    'status', 'rejected',
                    'reason', 'library_book_not_found'
                );
            elsif not mutation_payload ? 'position' then
                result := jsonb_build_object(
                    'mutation_id', mutation_id_value,
                    'status', 'rejected',
                    'reason', 'position_required'
                );
            else
                reading_payload := jsonb_build_object(
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
                        'library_book_id', cloud_book_id_value,
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
                        'library_book_id', cloud_book_id_value,
                        'cloud_book_id', cloud_book_id_value,
                        'revision', new_revision_value,
                        'change_id', change_id_value
                    );
                end if;
            end if;
        elsif operation_value = 'upsert'
            and entity_type_value = 'reading_session'
        then
            -- Reading sessions are an immutable statistics ledger. Identity is
            -- derived client-side from the session's fields, so an insert is
            -- idempotent: a replayed session id is accepted without writing a
            -- duplicate row or change event.
            session_id_value := nullif(mutation_payload ->> 'session_id', '');
            if session_id_value is null
                or nullif(mutation_payload ->> 'book_uuid', '') is null
                or nullif(mutation_payload ->> 'book_title', '') is null
                or nullif(mutation_payload ->> 'book_type', '') is null
                or nullif(mutation_payload ->> 'start_time', '') is null
                or nullif(mutation_payload ->> 'end_time', '') is null
                or nullif(mutation_payload ->> 'duration_ms', '') is null
            then
                result := jsonb_build_object(
                    'mutation_id', mutation_id_value,
                    'status', 'rejected',
                    'reason', 'reading_session_fields_required'
                );
            else
                select revision
                into current_revision_value
                from public.cloud_reading_sessions
                where cloud_user_id = actor
                  and session_id = session_id_value
                for update;

                if current_revision_value is null then
                    new_revision_value := 1;
                    insert into public.cloud_reading_sessions(
                        cloud_user_id,
                        session_id,
                        payload,
                        revision
                    )
                    values (
                        actor,
                        session_id_value,
                        mutation_payload,
                        new_revision_value
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
                        session_id_value,
                        operation_value,
                        mutation_payload,
                        new_revision_value
                    )
                    returning change_id into change_id_value;
                    result := jsonb_build_object(
                        'mutation_id', mutation_id_value,
                        'status', 'accepted',
                        'session_id', session_id_value,
                        'revision', new_revision_value,
                        'change_id', change_id_value
                    );
                else
                    result := jsonb_build_object(
                        'mutation_id', mutation_id_value,
                        'status', 'accepted',
                        'session_id', session_id_value,
                        'revision', current_revision_value
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
