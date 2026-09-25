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
                    update public.cloud_books
                    set deleted_at = timezone('utc', now()),
                        revision = revision + 1,
                        updated_at = timezone('utc', now())
                    where id = book_row.id
                    returning * into book_row;

                    new_revision_value := book_row.revision;
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
