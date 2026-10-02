# Security hardening rollout (20261003000000)

`migrations/20261003000000_parrot_cloud_security_hardening.sql` makes uploads
and recaps allowlist-only, lowers the default quota to 200 MiB, adds bucket,
reservation, hash and sync payload limits, a global recap cap, and a
retention purge. Run every step yourself; nothing here is automated.

## 1. Before applying

Run in the SQL editor. Replace `<your-account-email>` with each operator's
email (one row per account).

```sql
begin;

-- Same DDL as the migration; the migration skips it when it already exists.
create table if not exists public.cloud_feature_allowlist (
    cloud_user_id uuid not null references auth.users(id) on delete cascade,
    feature text not null check (feature in ('uploads', 'recap')),
    added_at timestamptz not null default timezone('utc', now()),
    note text,
    primary key (cloud_user_id, feature)
);
alter table public.cloud_feature_allowlist enable row level security;
revoke all on public.cloud_feature_allowlist from anon, authenticated;

insert into public.cloud_feature_allowlist (cloud_user_id, feature, note)
select u.id, f.feature, 'operator'
from auth.users u
cross join (values ('uploads'), ('recap')) as f(feature)
where u.email = '<your-account-email>'
on conflict do nothing;

-- Keep 5 GiB for operators even if their storage row doesn't exist yet.
insert into public.cloud_user_storage (cloud_user_id, quota_bytes)
select u.id, 5368709120 from auth.users u
where u.email = '<your-account-email>'
on conflict (cloud_user_id) do update
set quota_bytes = greatest(public.cloud_user_storage.quota_bytes, excluded.quota_bytes);

commit;
```

Check for rows that break the new constraints. Each query should return 0.
If not, those constraints stay NOT VALID (the migration logs a warning) and
any later update of such a row fails, so fix or delete the rows first.

```sql
select count(*) from public.cloud_book_files
where content_hash_algorithm <> 'sha-256-v1' or content_hash !~ '^[0-9a-f]{64}$'
   or size_bytes < 1 or char_length(file_name) > 1024
   or char_length(relative_path) > 4096 or char_length(media_type) > 64;
select count(*) from public.cloud_book_uploads
where content_hash_algorithm <> 'sha-256-v1' or content_hash !~ '^[0-9a-f]{64}$'
   or size_bytes < 1;
select count(*) from public.cloud_books where octet_length(metadata::text) > 65536;
select count(*) from public.cloud_reading_sessions
where octet_length(payload::text) > 32768 or char_length(session_id) > 1024;
select count(*) from public.cloud_book_link_decisions where char_length(pair_key) > 1024;
select count(*) from public.cloud_book_links where cardinality(members) > 64;
```

## 2. Apply

`supabase db push`. The generate-recap Edge Function needs no redeploy: the
`consume_recap_quota` signature and return value are unchanged.

## 3. After applying

- Verify: `select public.get_cloud_feature_access();` as your user returns
  `{"uploads": true, "recap": true}`.
- Schedule `select public.purge_cloud_retention(90);` as the service role
  (e.g. daily). It is not scheduled by the migration.
- Tune the global recap cap without a deploy:
  `update public.recap_settings set value = <n> where key = 'global_daily_limit';`
  (`0` turns recaps off for everyone).
- Allow another account:
  `insert into public.cloud_feature_allowlist (cloud_user_id, feature) values ('<uuid>', 'uploads');`

## Rollback

Never edit the applied migration; roll forward with a new one.

- Re-open uploads/recaps for everyone (fastest):
  `insert into public.cloud_feature_allowlist (cloud_user_id, feature) select id, f from auth.users, unnest(array['uploads','recap']) f on conflict do nothing;`
- Lift the global recap cap: set `global_daily_limit` to a large value.
- Full revert, in a new migration: restore `reserve_book_upload` from
  `20260924000007`, `consume_recap_quota` from `20261002000000` and
  `push_sync_changes` from `20261001000001`; re-patch
  `reserve_book_upload_before_orphan_gc` from `interval '2 hours'` back to
  `interval '24 hours'`; set the `book-files` bucket limits back to null;
  set the `quota_bytes` default back to `5368709120`; drop the new
  `*_check` constraints. The new tables can stay.
- Quotas of existing rows were never changed, so nothing to restore there.
