create or replace function public.push_sync_changes(p_mutations jsonb)
returns jsonb
language plpgsql
security definer
set search_path = public
as $$
declare
    actor uuid := auth.uid();
    mutation jsonb;
    mutation_id_value uuid;
    entity_type text;
    operation text;
    mutation_payload jsonb;
    mutation_content_hash text;
    mutation_content_hash_algorithm text;
    stable_entity_id text;
    base_revision bigint;
    current_revision bigint;
    current_payload jsonb;
    current_change_id bigint;
    new_revision bigint;
    change_id bigint;
    existing_response jsonb;
    result jsonb;
    results jsonb := '[]'::jsonb;
begin
    if actor is null then
        raise exception 'Authentication required';
    end if;

    if jsonb_typeof(coalesce(p_mutations, '[]'::jsonb)) <> 'array' then
        raise exception 'p_mutations must be a JSON array';
    end if;

    for mutation in
        select value from jsonb_array_elements(coalesce(p_mutations, '[]'::jsonb))
    loop
        mutation_id_value := (mutation ->> 'mutation_id')::uuid;
        entity_type := mutation ->> 'entity_type';
        operation := mutation ->> 'operation';
        mutation_payload := mutation -> 'payload';
        base_revision := nullif(mutation ->> 'base_revision', '')::bigint;

        perform pg_advisory_xact_lock(
            hashtextextended(actor::text || ':mutation:' || mutation_id_value::text, 0)
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

        if entity_type <> 'reading_position' then
            result := jsonb_build_object(
                'mutation_id', mutation_id_value,
                'status', 'rejected',
                'reason', 'unsupported_entity_type'
            );
        elsif operation <> 'upsert' then
            result := jsonb_build_object(
                'mutation_id', mutation_id_value,
                'status', 'rejected',
                'reason', 'unsupported_operation'
            );
        else
            mutation_content_hash := coalesce(
                mutation_payload ->> 'contentHash',
                mutation_payload ->> 'content_hash'
            );
            mutation_content_hash_algorithm := coalesce(
                mutation_payload ->> 'contentHashAlgorithm',
                mutation_payload ->> 'content_hash_algorithm',
                'sha256'
            );

            if mutation_content_hash is null or mutation_content_hash = '' then
                result := jsonb_build_object(
                    'mutation_id', mutation_id_value,
                    'status', 'rejected',
                    'reason', 'content_hash_required'
                );
            else
                stable_entity_id := mutation_content_hash_algorithm || ':' || mutation_content_hash;

                perform pg_advisory_xact_lock(
                    hashtextextended(actor::text || ':' || stable_entity_id, 0)
                );

                select revision, payload
                into current_revision, current_payload
                from public.reading_positions
                where cloud_user_id = actor
                  and entity_id = stable_entity_id
                for update;

                if base_revision is not null
                    and current_revision is not null
                    and base_revision <> current_revision
                then
                    select max(change_id)
                    into current_change_id
                    from public.sync_changes
                    where cloud_user_id = actor
                      and entity_id = stable_entity_id
                      and revision = current_revision;

                    result := jsonb_build_object(
                        'mutation_id', mutation_id_value,
                        'status', 'conflict',
                        'entity_type', entity_type,
                        'entity_id', stable_entity_id,
                        'change_id', current_change_id,
                        'revision', current_revision,
                        'payload', current_payload
                    );
                else
                    if current_revision is null then
                        new_revision := 1;
                        insert into public.reading_positions(
                            cloud_user_id,
                            entity_id,
                            content_hash,
                            content_hash_algorithm,
                            payload,
                            revision
                        )
                        values (
                            actor,
                            stable_entity_id,
                            mutation_content_hash,
                            mutation_content_hash_algorithm,
                            mutation_payload,
                            new_revision
                        );
                    else
                        new_revision := current_revision + 1;
                        update public.reading_positions
                        set content_hash = mutation_content_hash,
                            content_hash_algorithm = mutation_content_hash_algorithm,
                            payload = mutation_payload,
                            revision = new_revision,
                            updated_at = timezone('utc', now())
                        where cloud_user_id = actor
                          and entity_id = stable_entity_id;
                    end if;

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
                        entity_type,
                        stable_entity_id,
                        operation,
                        mutation_payload,
                        new_revision
                    )
                    returning sync_changes.change_id into change_id;

                    result := jsonb_build_object(
                        'mutation_id', mutation_id_value,
                        'status', 'accepted',
                        'entity_type', entity_type,
                        'entity_id', stable_entity_id,
                        'revision', new_revision,
                        'change_id', change_id
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
            coalesce(result ->> 'entity_type', entity_type),
            coalesce(result ->> 'entity_id', mutation ->> 'entity_id'),
            result
        );

        results := results || jsonb_build_array(result);
    end loop;

    return results;
end;
$$;
