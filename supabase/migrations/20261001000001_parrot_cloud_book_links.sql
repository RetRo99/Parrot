-- Linked books across servers. A link groups copies of one book that live on
-- different sources. A copy is named by a portable key: library:<bookId>,
-- storyteller:<uuid> or audiobookshelf:<itemId>. Links and "not the same book"
-- decisions sync through the existing push/pull protocol as the entity types
-- book_link and book_link_decision. pull_sync_changes is unchanged: it returns
-- every sync_changes row whatever its entity type.

create table public.cloud_book_links (
    cloud_user_id uuid not null references auth.users(id) on delete cascade,
    id uuid not null,
    members text[] not null,
    revision bigint not null default 1,
    created_at timestamptz not null default timezone('utc', now()),
    updated_at timestamptz not null default timezone('utc', now()),
    deleted_at timestamptz,
    primary key (cloud_user_id, id),
    check (cardinality(members) >= 2)
);

create table public.cloud_book_link_decisions (
    cloud_user_id uuid not null references auth.users(id) on delete cascade,
    pair_key text not null,
    decision text not null check (decision in ('never', 'skip')),
    decided_at timestamptz not null,
    revision bigint not null default 1,
    primary key (cloud_user_id, pair_key)
);

alter table public.cloud_book_links enable row level security;
alter table public.cloud_book_link_decisions enable row level security;

create policy cloud_book_links_owner_select
on public.cloud_book_links for select to authenticated
using (cloud_user_id = auth.uid());

create policy cloud_book_link_decisions_owner_select
on public.cloud_book_link_decisions for select to authenticated
using (cloud_user_id = auth.uid());

revoke all on public.cloud_book_links from anon, authenticated;
revoke all on public.cloud_book_link_decisions from anon, authenticated;
grant select on public.cloud_book_links to authenticated;
grant select on public.cloud_book_link_decisions to authenticated;

create trigger guard_cloud_account_deletion_write
before insert or update on public.cloud_book_links
for each row execute function public.guard_cloud_account_deletion_write();

create trigger guard_cloud_account_deletion_write
before insert or update on public.cloud_book_link_decisions
for each row execute function public.guard_cloud_account_deletion_write();

-- One payload shape for every book_link change and conflict.
create or replace function public.cloud_book_link_sync_payload(owner_id uuid, link_id uuid)
returns jsonb
language sql
stable
set search_path = public
as $$
    select jsonb_build_object(
        'link_id', link.id::text,
        'members', to_jsonb(link.members),
        'deleted', link.deleted_at is not null,
        'remote_revision', link.revision,
        'created_at', link.created_at
    )
    from public.cloud_book_links as link
    where link.cloud_user_id = owner_id
      and link.id = link_id;
$$;

revoke execute on function public.cloud_book_link_sync_payload(uuid, uuid)
from public, anon, authenticated;

-- push_sync_changes: the definition from 20261001000000 plus the book_link and
-- book_link_decision entity types.
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
    link_id_value uuid;
    link_members_value text[];
    link_deleted_value boolean;
    link_deleted_at_value timestamptz;
    conflicting_link_id_value uuid;
    pair_key_value text;
    decision_value text;
    decided_at_value timestamptz;
    current_decided_at_value timestamptz;
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
        link_id_value := null;
        link_members_value := null;
        link_deleted_at_value := null;
        conflicting_link_id_value := null;
        decided_at_value := null;
        current_decided_at_value := null;
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
        elsif entity_type_value = 'book_link'
            and operation_value in ('upsert', 'delete')
        then
            -- A link groups copies of one book from different sources. A copy
            -- belongs to at most one live link per account.
            begin
                link_id_value := (mutation_payload ->> 'link_id')::uuid;
            exception when invalid_text_representation then
                link_id_value := null;
            end;
            if jsonb_typeof(mutation_payload -> 'members') = 'array' then
                select coalesce(
                    array_agg(distinct member.value order by member.value),
                    '{}'::text[]
                )
                into link_members_value
                from jsonb_array_elements_text(mutation_payload -> 'members') as member(value);
            end if;
            link_deleted_value := operation_value = 'delete'
                or mutation_payload -> 'deleted' = 'true'::jsonb;

            select link.revision, link.deleted_at
            into current_revision_value, link_deleted_at_value
            from public.cloud_book_links as link
            where link.cloud_user_id = actor
              and link.id = link_id_value
            for update;

            if link_id_value is null then
                result := jsonb_build_object(
                    'mutation_id', mutation_id_value,
                    'status', 'rejected',
                    'reason', 'invalid_link'
                );
            elsif link_deleted_value then
                if current_revision_value is not null and link_deleted_at_value is null then
                    new_revision_value := current_revision_value + 1;
                    update public.cloud_book_links as link
                    set revision = new_revision_value,
                        updated_at = timezone('utc', now()),
                        deleted_at = timezone('utc', now())
                    where link.cloud_user_id = actor
                      and link.id = link_id_value;
                else
                    -- Deleting a link that is unknown or already deleted is a no-op.
                    result := jsonb_build_object(
                        'mutation_id', mutation_id_value,
                        'status', 'accepted',
                        'link_id', link_id_value,
                        'revision', current_revision_value
                    );
                end if;
            elsif link_members_value is null
                or cardinality(link_members_value) < 2
                or exists (
                    select 1
                    from unnest(link_members_value) as member(value)
                    where split_part(member.value, ':', 1) = ''
                       or position(':' in member.value) = length(member.value)
                       or position(':' in member.value) = 0
                )
                or (
                    select count(distinct split_part(member.value, ':', 1))
                    from unnest(link_members_value) as member(value)
                ) <> cardinality(link_members_value)
            then
                result := jsonb_build_object(
                    'mutation_id', mutation_id_value,
                    'status', 'rejected',
                    'reason', 'invalid_link'
                );
            else
                select other_link.id
                into conflicting_link_id_value
                from public.cloud_book_links as other_link
                where other_link.cloud_user_id = actor
                  and other_link.id <> link_id_value
                  and other_link.deleted_at is null
                  and other_link.members && link_members_value
                order by other_link.created_at, other_link.id
                limit 1;

                if conflicting_link_id_value is not null then
                    -- The client merges the two links and pushes again.
                    current_payload := public.cloud_book_link_sync_payload(
                        actor,
                        conflicting_link_id_value
                    );
                    result := jsonb_build_object(
                        'mutation_id', mutation_id_value,
                        'status', 'conflict',
                        'link_id', conflicting_link_id_value,
                        'revision', current_payload -> 'remote_revision',
                        'payload', current_payload
                    );
                elsif current_revision_value is null then
                    insert into public.cloud_book_links(cloud_user_id, id, members)
                    values (actor, link_id_value, link_members_value)
                    returning revision into new_revision_value;
                elsif base_revision_value is null
                    or base_revision_value <> current_revision_value
                then
                    result := jsonb_build_object(
                        'mutation_id', mutation_id_value,
                        'status', 'conflict',
                        'link_id', link_id_value,
                        'revision', current_revision_value,
                        'payload', public.cloud_book_link_sync_payload(actor, link_id_value)
                    );
                else
                    new_revision_value := current_revision_value + 1;
                    update public.cloud_book_links as link
                    set members = link_members_value,
                        revision = new_revision_value,
                        updated_at = timezone('utc', now()),
                        deleted_at = null
                    where link.cloud_user_id = actor
                      and link.id = link_id_value;
                end if;
            end if;

            if new_revision_value is not null then
                current_payload := public.cloud_book_link_sync_payload(actor, link_id_value);
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
                    link_id_value::text,
                    case when link_deleted_value then 'delete' else 'upsert' end,
                    current_payload,
                    new_revision_value
                )
                returning change_id into change_id_value;
                result := jsonb_build_object(
                    'mutation_id', mutation_id_value,
                    'status', 'accepted',
                    'link_id', link_id_value,
                    'revision', new_revision_value,
                    'change_id', change_id_value
                );
            end if;
        elsif operation_value = 'upsert'
            and entity_type_value = 'book_link_decision'
        then
            -- "Not the same book" and "Skip" decisions: last write wins on
            -- decided_at.
            pair_key_value := nullif(mutation_payload ->> 'pair_key', '');
            decision_value := mutation_payload ->> 'decision';
            begin
                decided_at_value := (mutation_payload ->> 'decided_at')::timestamptz;
            exception when others then
                decided_at_value := null;
            end;

            if pair_key_value is null
                or decision_value is null
                or decision_value not in ('never', 'skip')
                or decided_at_value is null
            then
                result := jsonb_build_object(
                    'mutation_id', mutation_id_value,
                    'status', 'rejected',
                    'reason', 'invalid_link_decision'
                );
            else
                select stored.revision,
                       stored.decided_at,
                       jsonb_build_object(
                           'pair_key', stored.pair_key,
                           'decision', stored.decision,
                           'decided_at', stored.decided_at,
                           'remote_revision', stored.revision
                       )
                into current_revision_value, current_decided_at_value, current_payload
                from public.cloud_book_link_decisions as stored
                where stored.cloud_user_id = actor
                  and stored.pair_key = pair_key_value
                for update;

                if current_revision_value is not null
                    and current_decided_at_value >= decided_at_value
                then
                    result := jsonb_build_object(
                        'mutation_id', mutation_id_value,
                        'status', 'accepted',
                        'pair_key', pair_key_value,
                        'revision', current_revision_value,
                        'payload', current_payload
                    );
                else
                    new_revision_value := coalesce(current_revision_value, 0) + 1;
                    insert into public.cloud_book_link_decisions(
                        cloud_user_id,
                        pair_key,
                        decision,
                        decided_at,
                        revision
                    )
                    values (
                        actor,
                        pair_key_value,
                        decision_value,
                        decided_at_value,
                        new_revision_value
                    )
                    on conflict (cloud_user_id, pair_key) do update
                    set decision = excluded.decision,
                        decided_at = excluded.decided_at,
                        revision = excluded.revision;
                    current_payload := jsonb_build_object(
                        'pair_key', pair_key_value,
                        'decision', decision_value,
                        'decided_at', decided_at_value,
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
                        pair_key_value,
                        operation_value,
                        current_payload,
                        new_revision_value
                    )
                    returning change_id into change_id_value;
                    result := jsonb_build_object(
                        'mutation_id', mutation_id_value,
                        'status', 'accepted',
                        'pair_key', pair_key_value,
                        'revision', new_revision_value,
                        'change_id', change_id_value,
                        'payload', current_payload
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
