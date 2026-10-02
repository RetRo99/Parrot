-- Security hardening for Parrot Cloud uploads, recaps and sync.
--
-- Rollout: see supabase/SECURITY_ROLLOUT.md. Uploads and recaps become
-- allowlist-only, so allowlist the operators BEFORE applying this.
--
-- purge_cloud_retention() is not scheduled here (no pg_cron). It must be run
-- by a service-role job, e.g. daily.

-- 1. Feature allowlist shared by uploads and recaps. Managed with the service
-- role or the SQL editor; clients have no direct access. "if not exists" so
-- operators can be allowlisted before this migration runs (see rollout doc).
create table if not exists public.cloud_feature_allowlist (
    cloud_user_id uuid not null references auth.users(id) on delete cascade,
    feature text not null check (feature in ('uploads', 'recap')),
    added_at timestamptz not null default timezone('utc', now()),
    note text,
    primary key (cloud_user_id, feature)
);

alter table public.cloud_feature_allowlist enable row level security;
revoke all on public.cloud_feature_allowlist from anon, authenticated;

-- Lets the client hide features the account can't use.
create or replace function public.get_cloud_feature_access()
returns jsonb
language sql
stable
security definer
set search_path = public
as $$
    select jsonb_build_object(
        'uploads', exists (
            select 1 from public.cloud_feature_allowlist a
            where a.cloud_user_id = auth.uid() and a.feature = 'uploads'
        ),
        'recap', exists (
            select 1 from public.cloud_feature_allowlist a
            where a.cloud_user_id = auth.uid() and a.feature = 'recap'
        )
    );
$$;

revoke execute on function public.get_cloud_feature_access()
from public, anon, service_role;
grant execute on function public.get_cloud_feature_access() to authenticated;

-- 2. New accounts start with 200 MiB. Existing rows keep their quota.
alter table public.cloud_user_storage
    alter column quota_bytes set default 209715200;

-- 3. Bucket limits. The client uploads one file per media type: EPUBs and
-- single-file audiobooks (m4b/mp3), which can exceed 1 GiB. 2 GiB keeps real
-- audiobooks working; the account quota stays the real limiter. MIME types
-- mirror TusUploadMetadata.contentType(). The project-wide Storage upload
-- limit still applies on top of this.
update storage.buckets
set file_size_limit = 2147483648,
    allowed_mime_types = array[
        'application/epub+zip',
        'application/pdf',
        'audio/mpeg',
        'audio/mp4',
        'audio/flac',
        'audio/ogg',
        'audio/opus',
        'audio/wav',
        'application/octet-stream'
    ]
where id = 'book-files';

-- 4. Table constraints, added NOT VALID and validated only when existing rows
-- comply, so applying never fails on old data. NOT VALID still checks every
-- new or updated row. Content hashes are client-supplied (the client streams
-- SHA-256 while uploading); server-side hashing is a follow-up.
do $$
declare
    c record;
    violated boolean;
begin
    for c in
        select *
        from (values
            ('cloud_book_files', 'cloud_book_files_hash_algorithm_check',
             $c$content_hash_algorithm = 'sha-256-v1'$c$),
            ('cloud_book_files', 'cloud_book_files_hash_format_check',
             $c$content_hash ~ '^[0-9a-f]{64}$'$c$),
            ('cloud_book_files', 'cloud_book_files_size_positive_check',
             $c$size_bytes >= 1$c$),
            ('cloud_book_files', 'cloud_book_files_text_length_check',
             $c$char_length(file_name) <= 1024
                and char_length(relative_path) <= 4096
                and char_length(media_type) <= 64$c$),
            ('cloud_book_uploads', 'cloud_book_uploads_hash_algorithm_check',
             $c$content_hash_algorithm = 'sha-256-v1'$c$),
            ('cloud_book_uploads', 'cloud_book_uploads_hash_format_check',
             $c$content_hash ~ '^[0-9a-f]{64}$'$c$),
            ('cloud_book_uploads', 'cloud_book_uploads_size_positive_check',
             $c$size_bytes >= 1$c$),
            ('cloud_book_uploads', 'cloud_book_uploads_text_length_check',
             $c$char_length(file_name) <= 1024
                and char_length(relative_path) <= 4096
                and char_length(media_type) <= 64$c$),
            ('cloud_books', 'cloud_books_metadata_size_check',
             $c$octet_length(metadata::text) <= 65536$c$),
            ('cloud_reading_sessions', 'cloud_reading_sessions_size_check',
             $c$octet_length(payload::text) <= 32768
                and char_length(session_id) <= 1024$c$),
            ('cloud_book_link_decisions', 'cloud_book_link_decisions_pair_key_check',
             $c$char_length(pair_key) <= 1024$c$),
            ('cloud_book_links', 'cloud_book_links_members_size_check',
             $c$cardinality(members) <= 64
                and octet_length(array_to_string(members, ',')) <= 65536$c$)
        ) as t(table_name, constraint_name, expression)
    loop
        execute format(
            'alter table public.%I add constraint %I check (%s) not valid',
            c.table_name, c.constraint_name, c.expression
        );
        execute format(
            'select exists (select 1 from public.%I where not (%s))',
            c.table_name, c.expression
        ) into violated;
        if violated then
            raise warning '% left NOT VALID: existing rows violate it',
                c.constraint_name;
        else
            execute format(
                'alter table public.%I validate constraint %I',
                c.table_name, c.constraint_name
            );
        end if;
    end loop;
end;
$$;

-- 5. Reservations expire after 2 hours instead of 24. The client re-reserves
-- on every attempt and keeps its TUS session, so an expired reservation only
-- costs a fresh reserve call. Patch the literal in the core RPC in place.
do $$
declare
    definition text;
begin
    definition := pg_get_functiondef(
        'public.reserve_book_upload_before_orphan_gc(uuid,text,text,text,bigint,text,text,jsonb)'::regprocedure
    );
    if position($l$interval '24 hours'$l$ in definition) = 0 then
        raise exception 'reservation expiry literal not found';
    end if;
    execute replace(
        definition,
        $l$interval '24 hours'$l$,
        $l$interval '2 hours'$l$
    );
end;
$$;

-- 6. reserve_book_upload: the definition from 20260924000007 plus the upload
-- allowlist, hash/size/length validation and a pending-reservation cap.
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
    hash_value text := lower(btrim(content_hash));
    hash_algorithm_value text := lower(btrim(content_hash_algorithm));
    pending_count integer;
    response jsonb;
    existing_record jsonb;
begin
    if actor is null then
        raise exception 'Authentication required';
    end if;
    -- Same per-account lock the inner RPCs take (re-entrant), so the
    -- pending count below can't race a concurrent reservation.
    perform pg_advisory_xact_lock(hashtextextended(actor::text, 0));

    if not exists (
        select 1 from public.cloud_feature_allowlist a
        where a.cloud_user_id = actor and a.feature = 'uploads'
    ) then
        return jsonb_build_object('status', 'rejected', 'reason', 'uploads_not_enabled');
    end if;

    if hash_algorithm_value is distinct from 'sha-256-v1'
        or not coalesce(hash_value ~ '^[0-9a-f]{64}$', false)
        or coalesce(size_bytes, 0) < 1
        or char_length(file_name) > 1024
        or char_length(relative_path) > 4096
        or char_length(media_type) > 64
    then
        return jsonb_build_object('status', 'rejected', 'reason', 'invalid_upload_metadata');
    end if;

    -- Re-reserving a slot that already has a live reservation is not new.
    select count(*) into pending_count
    from public.cloud_book_uploads u
    where u.cloud_user_id = actor
      and u.status = 'reserved'
      and u.expires_at > now()
      and not (
          u.cloud_book_id = reserve_book_upload.cloud_book_id
          and u.media_type = reserve_book_upload.media_type
          and u.relative_path = coalesce(reserve_book_upload.relative_path, '')
      );
    if pending_count >= 20 then
        return jsonb_build_object(
            'status', 'rejected',
            'reason', 'too_many_pending_uploads',
            'retry_after_ms', 60000
        );
    end if;

    response := public.reserve_book_upload_before_retry_after(
        cloud_book_id,
        media_type,
        relative_path,
        file_name,
        size_bytes,
        hash_algorithm_value,
        hash_value,
        rights_attestation
    );

    if response ->> 'status' = 'rejected'
        and response ->> 'reason' = 'quota_exceeded'
    then
        return response || jsonb_build_object('retry_after_ms', 60000);
    end if;

    if response ->> 'status' = 'rejected'
        and response ->> 'reason' = 'file_exists'
    then
        -- Slot lookup by book + media + path (ownership re-verified through the
        -- book row; the inner RPC already proved the slot is occupied).
        select jsonb_build_object(
            'cloud_book_file_id', f.id,
            'status', f.status,
            'media_type', f.media_type,
            'relative_path', f.relative_path,
            'file_name', f.file_name,
            'size_bytes', f.size_bytes,
            'content_hash', f.content_hash,
            'content_hash_algorithm', f.content_hash_algorithm,
            'revision', f.revision
        )
        into existing_record
        from public.cloud_book_files f
        where f.cloud_book_id = reserve_book_upload.cloud_book_id
          and f.media_type = reserve_book_upload.media_type
          and f.relative_path = coalesce(reserve_book_upload.relative_path, '');
        if existing_record is not null then
            response := response || jsonb_build_object('existing', existing_record);
        end if;
    end if;

    return response;
end;
$$;

revoke execute on function public.reserve_book_upload(
    uuid, text, text, text, bigint, text, text, jsonb
) from public, anon, service_role;
grant execute on function public.reserve_book_upload(
    uuid, text, text, text, bigint, text, text, jsonb
) to authenticated;

-- 7. Recaps: allowlist gate plus a global daily cap on provider spend. The
-- cap lives in recap_settings so it can change without a deploy.
create table public.recap_settings (
    key text primary key,
    value integer not null check (value >= 0)
);

insert into public.recap_settings (key, value)
values ('global_daily_limit', 500)
on conflict (key) do nothing;

create table public.recap_global_usage (
    day date primary key,
    count integer not null default 0 check (count >= 0)
);

alter table public.recap_settings enable row level security;
alter table public.recap_global_usage enable row level security;
revoke all on public.recap_settings from anon, authenticated;
revoke all on public.recap_global_usage from anon, authenticated;

-- Same signature and contract as before: true takes one unit, false means
-- "no recap now" (the Edge Function answers 429 either way).
create or replace function public.consume_recap_quota(p_limit integer)
returns boolean
language plpgsql
security definer
set search_path = public
as $$
declare
    actor uuid := auth.uid();
    today date := timezone('utc', now())::date;
    global_limit integer;
    new_count integer;
begin
    if actor is null then
        raise exception 'Authentication required';
    end if;

    -- The Edge Function rejects these too; this covers direct RPC calls.
    if coalesce((auth.jwt()->>'is_anonymous')::boolean, false) then
        raise exception 'Anonymous users cannot request recaps';
    end if;

    if p_limit is null or p_limit < 1 then
        return false;
    end if;

    if not exists (
        select 1 from public.cloud_feature_allowlist a
        where a.cloud_user_id = actor and a.feature = 'recap'
    ) then
        return false;
    end if;

    -- One statement, so concurrent calls can't both slip past the limit.
    insert into public.recap_usage as ru (user_id, day, count)
    values (actor, today, 1)
    on conflict (user_id, day) do update
        set count = ru.count + 1
        where ru.count < p_limit
    returning ru.count into new_count;

    if new_count is null then
        return false;
    end if;

    -- A missing setting falls back to the seeded default.
    select s.value into global_limit
    from public.recap_settings s
    where s.key = 'global_daily_limit';
    global_limit := coalesce(global_limit, 500);

    new_count := null;
    -- The first insert of the day skips the WHERE, hence the limit >= 1 test.
    if global_limit >= 1 then
        insert into public.recap_global_usage as g (day, count)
        values (today, 1)
        on conflict (day) do update
            set count = g.count + 1
            where g.count < global_limit
        returning g.count into new_count;
    end if;

    if new_count is null then
        -- Refund the user's unit; this row is still locked by us.
        update public.recap_usage
        set count = count - 1
        where user_id = actor and day = today;
        return false;
    end if;

    return true;
end;
$$;

revoke execute on function public.consume_recap_quota(integer)
from public, anon, service_role;
grant execute on function public.consume_recap_quota(integer) to authenticated;

-- 8. push_sync_changes: the definition from 20261001000001 plus batch and
-- per-mutation size limits, field length limits, and a foreign book id that
-- reads like an invalid one (no cross-account existence probe).
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

        if octet_length(mutation_item::text) > 8192 then
            -- Rejected, not raised, so one bloated entry can't wedge the batch.
            result := jsonb_build_object(
                'mutation_id', mutation_id_value,
                'status', 'rejected',
                'reason', 'payload_too_large'
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

-- 9. Privilege hygiene. The guard is a trigger function: triggers fire without
-- an EXECUTE check, so clients never need it. cloud_account_deletion_in_progress()
-- stays granted because storage.objects RLS policies call it as the client.
revoke execute on function public.guard_cloud_account_deletion_write()
from public, anon, authenticated;

-- Clients never need TRUNCATE/REFERENCES/TRIGGER (or PG17 MAINTAIN) on
-- public tables, existing or future.
revoke truncate, references, trigger on all tables in schema public
from anon, authenticated;
alter default privileges for role postgres in schema public
    revoke truncate, references, trigger on tables from anon, authenticated;

do $$
begin
    if current_setting('server_version_num')::integer >= 170000 then
        execute 'revoke maintain on all tables in schema public from anon, authenticated';
        execute 'alter default privileges for role postgres in schema public '
            'revoke maintain on tables from anon, authenticated';
    end if;
end;
$$;

-- 10. Retention. Service role only; must be scheduled externally (no
-- pg_cron here). Only superseded sync_changes are purged: the newest change
-- per entity stays, so a fresh device pulling from cursor 0 still sees it.
create or replace function public.purge_cloud_retention(retain_days integer default 90)
returns jsonb
language plpgsql
security definer
set search_path = public
as $$
declare
    cutoff timestamptz;
    changes_deleted integer;
    mutations_deleted integer;
    recap_deleted integer;
    recap_global_deleted integer;
begin
    if auth.role() is distinct from 'service_role' then
        raise exception 'Service role required';
    end if;
    -- Offline clients replay mutations; keep idempotency records a while.
    if retain_days is null or retain_days < 30 then
        raise exception 'retain_days must be at least 30';
    end if;
    cutoff := timezone('utc', now()) - make_interval(days => retain_days);

    delete from public.sync_changes as sc
    using (
        select ranked.change_id
        from (
            select c.change_id,
                   c.created_at,
                   row_number() over (
                       partition by c.cloud_user_id, c.entity_type, c.entity_id
                       order by c.change_id desc
                   ) as newest_first
            from public.sync_changes as c
        ) as ranked
        where ranked.newest_first > 1
          and ranked.created_at < cutoff
    ) as stale
    where sc.change_id = stale.change_id;
    get diagnostics changes_deleted = row_count;

    delete from public.sync_mutations as sm
    where sm.created_at < cutoff;
    get diagnostics mutations_deleted = row_count;

    delete from public.recap_usage as ru
    where ru.day < (timezone('utc', now()) - interval '90 days')::date;
    get diagnostics recap_deleted = row_count;

    delete from public.recap_global_usage as g
    where g.day < (timezone('utc', now()) - interval '90 days')::date;
    get diagnostics recap_global_deleted = row_count;

    return jsonb_build_object(
        'status', 'purged',
        'sync_changes_deleted', changes_deleted,
        'sync_mutations_deleted', mutations_deleted,
        'recap_usage_deleted', recap_deleted,
        'recap_global_usage_deleted', recap_global_deleted
    );
end;
$$;

revoke execute on function public.purge_cloud_retention(integer)
from public, anon, authenticated;
grant execute on function public.purge_cloud_retention(integer) to service_role;
