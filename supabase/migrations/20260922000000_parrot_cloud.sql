-- Canonical pre-launch schema. Apply through a local/development DB reset;
-- this file intentionally does not migrate the superseded development schema.
create extension if not exists pgcrypto;

drop table if exists public.reading_positions cascade;
drop table if exists public.sync_changes cascade;
drop table if exists public.sync_mutations cascade;

create table public.cloud_books (
    id uuid primary key default gen_random_uuid(),
    cloud_user_id uuid not null references auth.users(id) on delete cascade,
    content_hash text not null,
    content_hash_algorithm text not null,
    title text not null,
    author text,
    format text not null,
    metadata jsonb not null default '{}'::jsonb,
    revision bigint not null default 1,
    created_at timestamptz not null default timezone('utc', now()),
    updated_at timestamptz not null default timezone('utc', now()),
    deleted_at timestamptz,
    unique (cloud_user_id, content_hash_algorithm, content_hash),
    unique (id, cloud_user_id)
);

create table public.cloud_book_files (
    id uuid primary key default gen_random_uuid(),
    cloud_book_id uuid not null,
    cloud_user_id uuid not null references auth.users(id) on delete cascade,
    storage_path text not null unique,
    relative_path text not null default '',
    file_name text not null,
    size_bytes bigint not null check (size_bytes >= 0),
    content_hash text not null,
    content_hash_algorithm text not null,
    media_type text not null,
    status text not null check (
        status in ('upload_pending', 'uploading', 'upload_failed', 'available', 'deleting')
    ),
    revision bigint not null default 1,
    created_at timestamptz not null default timezone('utc', now()),
    updated_at timestamptz not null default timezone('utc', now()),
    foreign key (cloud_book_id, cloud_user_id)
        references public.cloud_books(id, cloud_user_id) on delete cascade,
    unique (cloud_book_id, media_type, relative_path)
);

create table public.cloud_book_uploads (
    upload_id uuid primary key default gen_random_uuid(),
    cloud_book_id uuid not null,
    cloud_user_id uuid not null references auth.users(id) on delete cascade,
    cloud_book_file_id uuid not null,
    media_type text not null,
    relative_path text not null default '',
    file_name text not null,
    size_bytes bigint not null check (size_bytes >= 0),
    content_hash text not null,
    content_hash_algorithm text not null,
    rights_attestation jsonb not null,
    storage_path text not null,
    tus_upload_id text,
    status text not null check (
        status in ('reserved', 'finalized', 'cancelled', 'expired', 'failed')
    ),
    expires_at timestamptz not null,
    created_at timestamptz not null default timezone('utc', now()),
    updated_at timestamptz not null default timezone('utc', now()),
    foreign key (cloud_book_id, cloud_user_id)
        references public.cloud_books(id, cloud_user_id) on delete cascade
);

create unique index cloud_book_uploads_one_active_slot
on public.cloud_book_uploads(cloud_book_id, media_type, relative_path)
where status = 'reserved';

create index cloud_book_uploads_expiration
on public.cloud_book_uploads(expires_at)
where status = 'reserved';

create index cloud_book_uploads_storage_policy
on public.cloud_book_uploads(storage_path, cloud_user_id, expires_at)
where status = 'reserved';

create table public.cloud_user_storage (
    cloud_user_id uuid primary key references auth.users(id) on delete cascade,
    quota_bytes bigint not null default 5368709120 check (quota_bytes >= 0),
    reserved_bytes bigint not null default 0 check (reserved_bytes >= 0),
    used_bytes bigint not null default 0 check (used_bytes >= 0),
    updated_at timestamptz not null default timezone('utc', now())
);

create table public.cloud_content_blocklist (
    content_hash_algorithm text not null,
    content_hash text not null,
    reason text not null,
    created_by text not null,
    created_at timestamptz not null default timezone('utc', now()),
    primary key (content_hash_algorithm, content_hash)
);

create table public.cloud_file_audit_events (
    id bigint generated always as identity primary key,
    cloud_user_id uuid references auth.users(id) on delete set null,
    cloud_book_id uuid,
    cloud_book_file_id uuid,
    upload_id uuid,
    content_hash_algorithm text,
    content_hash text,
    action text not null,
    reason text,
    actor text not null,
    created_at timestamptz not null default timezone('utc', now())
);

create index cloud_file_audit_events_hash
on public.cloud_file_audit_events(content_hash_algorithm, content_hash, created_at desc);

create index cloud_file_audit_events_user
on public.cloud_file_audit_events(cloud_user_id, created_at desc);

create table public.reading_positions (
    cloud_book_id uuid primary key references public.cloud_books(id) on delete cascade,
    cloud_user_id uuid not null references auth.users(id) on delete cascade,
    payload jsonb not null,
    revision bigint not null default 1,
    updated_at timestamptz not null default timezone('utc', now())
);

create table public.sync_changes (
    change_id bigint generated always as identity primary key,
    cloud_user_id uuid not null references auth.users(id) on delete cascade,
    entity_type text not null,
    entity_id text not null,
    operation text not null,
    payload jsonb not null,
    revision bigint not null,
    created_at timestamptz not null default timezone('utc', now())
);

create table public.sync_mutations (
    cloud_user_id uuid not null references auth.users(id) on delete cascade,
    mutation_id uuid not null,
    entity_type text not null,
    entity_id text not null,
    response jsonb not null,
    created_at timestamptz not null default timezone('utc', now()),
    primary key (cloud_user_id, mutation_id)
);

create index cloud_books_user_revision
on public.cloud_books(cloud_user_id, revision);

create index sync_changes_user_cursor
on public.sync_changes(cloud_user_id, change_id);

create index cloud_book_files_user
on public.cloud_book_files(cloud_user_id, cloud_book_id);

alter table public.cloud_books enable row level security;
alter table public.cloud_book_files enable row level security;
alter table public.cloud_book_uploads enable row level security;
alter table public.cloud_user_storage enable row level security;
alter table public.cloud_content_blocklist enable row level security;
alter table public.cloud_file_audit_events enable row level security;
alter table public.reading_positions enable row level security;
alter table public.sync_changes enable row level security;
alter table public.sync_mutations enable row level security;

create policy cloud_books_owner_select
on public.cloud_books for select to authenticated
using (cloud_user_id = auth.uid());

create policy cloud_book_files_owner_select
on public.cloud_book_files for select to authenticated
using (cloud_user_id = auth.uid());

create policy cloud_book_uploads_owner_select
on public.cloud_book_uploads for select to authenticated
using (cloud_user_id = auth.uid());

create policy cloud_content_blocklist_owner_hash_select
on public.cloud_content_blocklist for select to authenticated
using (
    exists (
        select 1 from public.cloud_book_files f
        where f.cloud_user_id = auth.uid()
          and f.content_hash_algorithm = cloud_content_blocklist.content_hash_algorithm
          and f.content_hash = cloud_content_blocklist.content_hash
    )
);

create policy reading_positions_owner_select
on public.reading_positions for select to authenticated
using (cloud_user_id = auth.uid());

create policy sync_changes_owner_select
on public.sync_changes for select to authenticated
using (cloud_user_id = auth.uid());

revoke all on public.cloud_books from anon, authenticated;
revoke all on public.cloud_book_files from anon, authenticated;
revoke all on public.cloud_book_uploads from anon, authenticated;
revoke all on public.cloud_user_storage from anon, authenticated;
revoke all on public.cloud_content_blocklist from anon, authenticated;
revoke all on public.cloud_file_audit_events from anon, authenticated;
revoke all on public.reading_positions from anon, authenticated;
revoke all on public.sync_changes from anon, authenticated;
revoke all on public.sync_mutations from anon, authenticated;

grant select on public.cloud_books to authenticated;
grant select on public.cloud_book_files to authenticated;
grant select on public.cloud_book_uploads to authenticated;
grant select (content_hash_algorithm, content_hash)
on public.cloud_content_blocklist to authenticated;
grant select on public.reading_positions to authenticated;
grant select on public.sync_changes to authenticated;
