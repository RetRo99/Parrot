-- Uploads are open to every signed-in account.
--
-- The owner's decision of 2026-10-11: anyone with a Parrot Cloud account may
-- back up. Until now 'uploads' was allow-list only, the same gate as 'recap',
-- which was right while backup was in closed testing and is no longer wanted.
--
-- Only the 'uploads' answer changes. 'recap' stays allow-list only: it costs
-- money per call and has a global daily cap, so it is still invitation-only.
--
-- Nothing else about uploading moves. The 200 MiB allowance, the per-file size
-- limits (including the 64 MiB per prepared chapter from 20261010000000), the
-- content block-list, the pending-reservation cap, the rights attestation and
-- every row level security policy are all checked elsewhere in
-- reserve_book_upload and finalize_book_upload and are untouched here. An
-- anonymous caller still cannot reach any of this: execute on both functions
-- below is revoked from anon, and the 'uploads' answer is explicitly false when
-- there is no account behind the call.
--
-- Tests: supabase/tests/open_uploads_test.sql (16 assertions), written first.
-- The four assertions in supabase/tests/security_hardening_test.sql that stated
-- the old rule are updated in the same commit.

-- cloud_feature_enabled keeps its shape, its security definer and its grants.
-- Only the decision for 'uploads' is special-cased, so every other feature
-- (today 'recap', tomorrow whatever is added) still means "is there a row".
create or replace function public.cloud_feature_enabled(feature text)
returns boolean
language sql
stable
security definer
set search_path = public
as $$
    select case
        when auth.uid() is null then false
        -- Open to every signed-in account.
        when cloud_feature_enabled.feature = 'uploads' then true
        else exists (
            select 1 from public.cloud_feature_allowlist a
            where a.cloud_user_id = auth.uid()
              and a.feature = cloud_feature_enabled.feature
        )
    end;
$$;

revoke execute on function public.cloud_feature_enabled(text)
from public, anon, service_role;
grant execute on function public.cloud_feature_enabled(text) to authenticated;

-- get_cloud_feature_access is unchanged in body and shape; it is re-created only
-- so that it is recorded against this migration and so a reader of the rollback
-- below finds both halves in one place. It reports what the function above
-- decides, which is what lets the client stop hiding backup.
create or replace function public.get_cloud_feature_access()
returns jsonb
language sql
stable
security definer
set search_path = public
as $$
    select jsonb_build_object(
        'uploads', public.cloud_feature_enabled('uploads'),
        'recap', public.cloud_feature_enabled('recap')
    );
$$;

revoke execute on function public.get_cloud_feature_access()
from public, anon, service_role;
grant execute on function public.get_cloud_feature_access() to authenticated;

-- The existing 'uploads' allow-list rows are deliberately left in place. They
-- are now redundant for the decision, but deleting them would lose the record
-- of who was in the closed test, and a rollback needs them to still be there.
comment on table public.cloud_feature_allowlist is
    'Per-account feature grants. Since 20261011000000 the ''uploads'' feature is '
    'open to every signed-in account and its rows here are historical only; '
    '''recap'' is still granted by a row.';
