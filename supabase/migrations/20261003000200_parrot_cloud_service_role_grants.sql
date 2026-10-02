-- delete-cloud-account reads deletion requests with the secret key, and
-- service_role had no table grant (local tests ran it as a superuser).
grant select on table public.cloud_account_deletion_requests to service_role;

-- Let ops scripts manage the feature allowlist with the secret key.
grant select, insert, delete on table public.cloud_feature_allowlist to service_role;
