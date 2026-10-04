-- Saved items: bookmarks, highlights and notes in Parrot Cloud.
--
-- One row per item, keyed by a client-generated UUID, so the same item edited
-- on two devices can never become two rows. Items sync through the existing
-- push/pull protocol as entity type saved_item. Conflicts are last write wins
-- on the client's updated_at; equal times fall to the larger mutation id.
-- Deletes are tombstones that win or lose by the same rule, and are purged
-- after 180 days by purge_saved_item_tombstones (scheduled externally, like
-- purge_cloud_retention). pull_sync_changes is unchanged: it returns every
-- sync_changes row whatever its entity type. Account deletion purges rows
-- through the auth.users on-delete cascade.

create table public.cloud_saved_items (
    cloud_user_id uuid not null references auth.users(id) on delete cascade,
    item_id uuid not null,
    book_key text not null,
    payload jsonb not null,
    client_updated_at timestamptz not null,
    last_mutation_id uuid not null,
    deleted_at timestamptz,
    revision bigint not null default 1,
    created_at timestamptz not null default timezone('utc', now()),
    updated_at timestamptz not null default timezone('utc', now()),
    primary key (cloud_user_id, item_id)
);

create index cloud_saved_items_user_book
on public.cloud_saved_items(cloud_user_id, book_key);

create index cloud_saved_items_tombstones
on public.cloud_saved_items(deleted_at)
where deleted_at is not null;

alter table public.cloud_saved_items enable row level security;

create policy cloud_saved_items_owner_select
on public.cloud_saved_items for select to authenticated
using (cloud_user_id = auth.uid());

revoke all on public.cloud_saved_items from anon, authenticated;
grant select on public.cloud_saved_items to authenticated;

create trigger guard_cloud_account_deletion_write
before insert or update on public.cloud_saved_items
for each row execute function public.guard_cloud_account_deletion_write();

-- Applies one saved_item upsert or delete for actor. Called only from
-- push_sync_changes, which holds the per-account lock and records the
-- response for idempotency.
create or replace function public.apply_saved_item_mutation(
    actor uuid,
    mutation_id_value uuid,
    operation_value text,
    entity_id_value text,
    mutation_payload jsonb
)
returns jsonb
language plpgsql
security definer
set search_path = public
as $$
declare
    max_items constant integer := 50000;
    item_id_value uuid;
    book_key_value text;
    type_value text;
    href_value text;
    color_value text;
    note_value text;
    quote_value text;
    incoming_updated_at timestamptz;
    incoming_created_at timestamptz;
    incoming_deleted_at timestamptz;
    stored record;
    new_revision_value bigint;
    change_id_value bigint;
    stored_payload jsonb;
    live_count integer;
begin
    if jsonb_typeof(mutation_payload) is distinct from 'object' then
        return jsonb_build_object(
            'mutation_id', mutation_id_value, 'status', 'rejected', 'reason', 'invalid_saved_item'
        );
    end if;

    begin
        item_id_value := (mutation_payload ->> 'item_id')::uuid;
        incoming_updated_at := (mutation_payload ->> 'updated_at')::timestamptz;
        incoming_created_at := coalesce(
            (mutation_payload ->> 'created_at')::timestamptz,
            incoming_updated_at
        );
        incoming_deleted_at := nullif(mutation_payload ->> 'deleted_at', '')::timestamptz;
    exception when others then
        item_id_value := null;
    end;

    book_key_value := nullif(mutation_payload ->> 'book_key', '');
    type_value := mutation_payload ->> 'type';
    href_value := nullif(mutation_payload ->> 'href', '');
    color_value := nullif(mutation_payload ->> 'color', '');
    note_value := nullif(mutation_payload ->> 'note', '');
    quote_value := nullif(mutation_payload ->> 'text_quote', '');

    if item_id_value is null
        or entity_id_value is distinct from item_id_value::text
        or incoming_updated_at is null
        or book_key_value is null
        or char_length(book_key_value) > 512
        or type_value is null
        or type_value not in ('bookmark', 'highlight')
        or href_value is null
        or char_length(href_value) > 1024
        or (color_value is not null and color_value not in ('amber', 'rose', 'sage', 'sky'))
        or char_length(coalesce(mutation_payload ->> 'text_before', '')) > 256
        or char_length(coalesce(mutation_payload ->> 'text_after', '')) > 256
        or char_length(coalesce(mutation_payload ->> 'chapter_title', '')) > 512
        or char_length(coalesce(mutation_payload ->> 'book_title', '')) > 512
        or char_length(coalesce(mutation_payload ->> 'book_author', '')) > 512
        or char_length(coalesce(mutation_payload ->> 'audio_href', '')) > 1024
    then
        return jsonb_build_object(
            'mutation_id', mutation_id_value, 'status', 'rejected', 'reason', 'invalid_saved_item'
        );
    end if;

    if char_length(coalesce(quote_value, '')) > 1500 or char_length(coalesce(note_value, '')) > 2000 then
        return jsonb_build_object(
            'mutation_id', mutation_id_value, 'status', 'rejected', 'reason', 'saved_item_too_long'
        );
    end if;

    -- A device with a clock far ahead must not win every future edit.
    incoming_updated_at := least(incoming_updated_at, now() + interval '5 minutes');
    if operation_value = 'delete' then
        incoming_deleted_at := coalesce(incoming_deleted_at, incoming_updated_at);
    elsif incoming_deleted_at is not null then
        incoming_deleted_at := least(incoming_deleted_at, now() + interval '5 minutes');
    end if;

    select item.client_updated_at, item.last_mutation_id, item.revision, item.payload
    into stored
    from public.cloud_saved_items as item
    where item.cloud_user_id = actor
      and item.item_id = item_id_value
    for update;

    if found and (
        stored.client_updated_at > incoming_updated_at
        or (stored.client_updated_at = incoming_updated_at
            and stored.last_mutation_id >= mutation_id_value)
    ) then
        -- The stored version is newer: the client takes it.
        return jsonb_build_object(
            'mutation_id', mutation_id_value,
            'status', 'conflict',
            'item_id', item_id_value,
            'revision', stored.revision,
            'payload', stored.payload
        );
    end if;

    if not found and incoming_deleted_at is null then
        select count(*)::integer
        into live_count
        from public.cloud_saved_items as item
        where item.cloud_user_id = actor
          and item.deleted_at is null;
        if live_count >= max_items then
            return jsonb_build_object(
                'mutation_id', mutation_id_value, 'status', 'rejected', 'reason', 'saved_item_limit'
            );
        end if;
    end if;

    new_revision_value := coalesce(stored.revision, 0) + 1;
    stored_payload := jsonb_build_object(
        'item_id', item_id_value::text,
        'book_key', book_key_value,
        'book_title', mutation_payload -> 'book_title',
        'book_author', mutation_payload -> 'book_author',
        'type', type_value,
        'href', href_value,
        'media_type', mutation_payload -> 'media_type',
        'progression', mutation_payload -> 'progression',
        'total_progression', mutation_payload -> 'total_progression',
        'position', mutation_payload -> 'position',
        'chapter_title', mutation_payload -> 'chapter_title',
        'text_before', mutation_payload -> 'text_before',
        'text_quote', to_jsonb(quote_value),
        'text_after', mutation_payload -> 'text_after',
        'color', to_jsonb(color_value),
        'note', to_jsonb(note_value),
        'audio_href', mutation_payload -> 'audio_href',
        'audio_ms', mutation_payload -> 'audio_ms',
        'created_at', incoming_created_at,
        'updated_at', incoming_updated_at,
        'deleted_at', incoming_deleted_at,
        'remote_revision', new_revision_value
    );

    insert into public.cloud_saved_items(
        cloud_user_id,
        item_id,
        book_key,
        payload,
        client_updated_at,
        last_mutation_id,
        deleted_at,
        revision
    )
    values (
        actor,
        item_id_value,
        book_key_value,
        stored_payload,
        incoming_updated_at,
        mutation_id_value,
        incoming_deleted_at,
        new_revision_value
    )
    on conflict (cloud_user_id, item_id) do update
    set book_key = excluded.book_key,
        payload = excluded.payload,
        client_updated_at = excluded.client_updated_at,
        last_mutation_id = excluded.last_mutation_id,
        deleted_at = excluded.deleted_at,
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
        'saved_item',
        item_id_value::text,
        case when incoming_deleted_at is null then 'upsert' else 'delete' end,
        stored_payload,
        new_revision_value
    )
    returning change_id into change_id_value;

    return jsonb_build_object(
        'mutation_id', mutation_id_value,
        'status', 'accepted',
        'item_id', item_id_value,
        'revision', new_revision_value,
        'change_id', change_id_value,
        'payload', stored_payload
    );
end;
$$;

revoke execute on function public.apply_saved_item_mutation(uuid, uuid, text, text, jsonb)
from public, anon, authenticated;

-- Tombstones are kept long enough for offline devices to learn about the
-- delete, then removed with their change rows. Service role only.
create or replace function public.purge_saved_item_tombstones(retain_days integer default 180)
returns jsonb
language plpgsql
security definer
set search_path = public
as $$
declare
    cutoff timestamptz;
    items_deleted integer;
    changes_deleted integer;
begin
    if auth.role() is distinct from 'service_role' then
        raise exception 'Service role required';
    end if;
    if retain_days is null or retain_days < 90 then
        raise exception 'retain_days must be at least 90';
    end if;
    cutoff := now() - make_interval(days => retain_days);

    with purged as (
        delete from public.cloud_saved_items as item
        where item.deleted_at is not null
          and item.deleted_at < cutoff
          and item.updated_at < cutoff
        returning item.cloud_user_id, item.item_id
    ), purged_changes as (
        delete from public.sync_changes as change
        using purged
        where change.cloud_user_id = purged.cloud_user_id
          and change.entity_type = 'saved_item'
          and change.entity_id = purged.item_id::text
        returning change.change_id
    )
    select (select count(*) from purged), (select count(*) from purged_changes)
    into items_deleted, changes_deleted;

    return jsonb_build_object(
        'status', 'purged',
        'saved_items_deleted', items_deleted,
        'sync_changes_deleted', changes_deleted
    );
end;
$$;

revoke execute on function public.purge_saved_item_tombstones(integer)
from public, anon, authenticated;
grant execute on function public.purge_saved_item_tombstones(integer) to service_role;

-- push_sync_changes: the definition from 20261003000000 plus the saved_item
-- branch, which delegates to apply_saved_item_mutation.
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
    item_rejection text;
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

    -- Clients push at most 50 mutations; 50 x 8 KiB fits under 512 KiB.
    if jsonb_array_length(coalesce(mutations, '[]'::jsonb)) > 200
        or octet_length(coalesce(mutations, '[]'::jsonb)::text) > 524288
    then
        raise exception using errcode = 'P0001', message = 'sync_batch_too_large';
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
        base_revision_value := null;
        item_rejection := null;
        -- The id is the idempotency key, so a malformed one still raises.
        mutation_id_value := (mutation_item ->> 'mutation_id')::uuid;
        entity_type_value := mutation_item ->> 'entity_type';
        operation_value := mutation_item ->> 'operation';
        entity_id_value := mutation_item ->> 'entity_id';
        mutation_payload := mutation_item -> 'payload';
        -- Size first, then the remaining casts, so neither a bloated nor a
        -- malformed entry can abort the whole batch.
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
            entity_type_value := left(entity_type_value, 64);
            entity_id_value := left(entity_id_value, 256);
        elsif operation_value = 'upsert'
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
                    -- Same answer as a malformed id: don't reveal that
                    -- another account owns this book id.
                    result := jsonb_build_object(
                        'mutation_id', mutation_id_value,
                        'status', 'rejected',
                        'reason', 'invalid_library_book_id'
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
                or char_length(session_id_value) > 1024
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
                or cardinality(link_members_value) > 64
                or exists (
                    select 1
                    from unnest(link_members_value) as member(value)
                    where char_length(member.value) > 512
                )
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
                or char_length(pair_key_value) > 1024
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
        elsif entity_type_value = 'saved_item'
            and operation_value in ('upsert', 'delete')
        then
            result := public.apply_saved_item_mutation(
                actor,
                mutation_id_value,
                operation_value,
                entity_id_value,
                mutation_payload
            );
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
