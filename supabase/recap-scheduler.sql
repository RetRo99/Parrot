-- Run AFTER deploying recap-worker and configuring its secret. No secrets
-- belong in this file. Create Vault entries privately via Dashboard first:
-- recap_project_url, recap_publishable_key, recap_worker_secret.
create extension if not exists pg_cron;
create extension if not exists pg_net with schema extensions;

create or replace function public.wake_recap_worker(p_cleanup boolean default false)
returns bigint language plpgsql security definer set search_path = public as $$
declare project_url text; client_key text; worker_secret text;
begin
 select decrypted_secret into project_url from vault.decrypted_secrets where name='recap_project_url';
 select decrypted_secret into client_key from vault.decrypted_secrets where name='recap_publishable_key';
 select decrypted_secret into worker_secret from vault.decrypted_secrets where name='recap_worker_secret';
 if project_url is null or client_key is null or length(coalesce(worker_secret,''))<32 then raise exception 'Recap scheduler not configured'; end if;
 return net.http_post(url:=project_url || '/functions/v1/recap-worker',
 headers:=jsonb_build_object('Content-Type','application/json','apikey',client_key,'X-Recap-Worker',worker_secret),
 body:=jsonb_build_object('mode',case when p_cleanup then 'cleanup' else 'process' end),timeout_milliseconds:=140000);
end; $$;
revoke all on function public.wake_recap_worker(boolean) from public,anon,authenticated,service_role;

create or replace function public.recap_insert_wakeup() returns trigger
language plpgsql security definer set search_path = public as $$
begin
 -- Wakeup is a latency optimization, not delivery. A failure must not roll
 -- back job admission. Cron will retry from the durable queue.
 begin perform public.wake_recap_worker(false); exception when others then null; end;
 return new;
end; $$;
revoke all on function public.recap_insert_wakeup() from public,anon,authenticated,service_role;
drop trigger if exists recap_insert_wakeup on public.recap_jobs;
create trigger recap_insert_wakeup after insert on public.recap_jobs for each row
when (new.state='queued') execute function public.recap_insert_wakeup();

select cron.schedule('recap-recovery','* * * * *',$job$
 select public.wake_recap_worker(false)
 where exists(select 1 from pgmq.q_recap_jobs where vt<=now());
$job$);
select cron.schedule('recap-cleanup','*/15 * * * *',$job$select public.wake_recap_worker(true);$job$);
select cron.schedule('recap-cron-history','0 3 * * *',$job$
 delete from cron.job_run_details where end_time<now()-interval '7 days';
$job$);
-- pg_net stores credentials in its transient request queue. Do not expose
-- net/vault/pgmq through the Data API or grant clients access to these tables.
revoke all on all tables in schema net from anon,authenticated;
