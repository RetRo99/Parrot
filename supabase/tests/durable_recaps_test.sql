begin;
create extension if not exists pgtap with schema extensions;
set search_path=extensions,public;
select no_plan();
insert into auth.users(id,instance_id,aud,role,email) values
('50000000-0000-4000-8000-000000000001','00000000-0000-0000-0000-000000000000','authenticated','authenticated','recap-job-a@example.invalid'),
('50000000-0000-4000-8000-000000000002','00000000-0000-0000-0000-000000000000','authenticated','authenticated','recap-job-b@example.invalid');
insert into public.cloud_feature_allowlist(cloud_user_id,feature) values
('50000000-0000-4000-8000-000000000001','recap'),('50000000-0000-4000-8000-000000000002','recap');
insert into public.cloud_books(id,cloud_user_id,title) values
('50000000-0000-4000-8000-000000000010','50000000-0000-4000-8000-000000000001','Fixture'),
('50000000-0000-4000-8000-000000000020','50000000-0000-4000-8000-000000000002','Fixture');
select ok((select relrowsecurity from pg_class where oid='public.recap_jobs'::regclass),'jobs use RLS');
select ok(not has_table_privilege('authenticated','public.recap_jobs','select'),'clients cannot read excerpts');
select ok(not has_table_privilege('authenticated','public.recap_jobs','update'),'clients cannot mutate results');
select ok(not has_function_privilege('authenticated','public.claim_recap_job(integer)','execute'),'claim is service-only');
select ok(not has_function_privilege('authenticated','public.consume_recap_quota(integer)','execute'),'direct client quota accounting is revoked');
select ok(not has_function_privilege('authenticated','public.take_recap_upload(uuid,integer)','execute'),'clients cannot consume staging outside admission');

create function pg_temp.submit(s text,b uuid default '50000000-0000-4000-8000-000000000010',e text default repeat('Event happened. ',10)) returns jsonb language sql as $$
 select public.submit_recap_job(s,b,'en',1000,'{"href":"chapter.xhtml","totalProgression":0.5}',e,'Last.'); $$;
grant execute on function pg_temp.submit(text,uuid,text) to authenticated;
set local role authenticated;
set local request.jwt.claims='{"sub":"50000000-0000-4000-8000-000000000001","role":"authenticated","is_anonymous":false}';
select throws_ok($$select pg_temp.submit('s1')$$,'42501','Consent required','new consent is required');
select public.set_recap_consent(true);
select is(pg_temp.submit('s1')->>'state','queued','submission persists queued state');
select is(pg_temp.submit('s1')->>'state','queued','lost response / duplicate returns existing job');
select is(pg_temp.submit('s1','50000000-0000-4000-8000-000000000010',repeat('Different. ',10))->>'error','conflict','conflicting excerpt cannot overwrite');
select is(pg_temp.submit('unlinked',null)->>'state','queued','unlinked books are accepted');
select is(jsonb_array_length(public.fetch_recap_jobs('unlinked')->'items'),1,'unlinked lookup works by session');
select throws_ok($$select pg_temp.submit('bad-book','50000000-0000-4000-8000-000000000020')$$,'42501','Book unavailable','unauthorized cloud book is refused');
select throws_ok($$select public.claim_recap_job(30)$$,'42501',null,'a user cannot claim work');
set local request.jwt.claims='{"sub":"50000000-0000-4000-8000-000000000002","role":"authenticated"}';
select is(jsonb_array_length(public.fetch_recap_jobs('s1')->'items'),0,'another account cannot look up a result');
select throws_ok($$select public.fetch_recap_jobs(null,'50000000-0000-4000-8000-000000000010')$$,'42501','Book unavailable','another account cannot fetch by book');
reset role;
select is((select count(*)::integer from public.recap_jobs where session_id='s1'),1,'one database job for repeated submission');
select is((select count(*)::integer from public.recap_usage where user_id='50000000-0000-4000-8000-000000000001'),0,'submission consumes no quota');

-- Isolate this fixture's messages from other seeded jobs.
delete from pgmq.q_recap_jobs where (message->>'id')::uuid not in (select id from public.recap_jobs where user_id='50000000-0000-4000-8000-000000000001');
create temp table claimed(value jsonb);
grant all on claimed to service_role;
set local role service_role;
set local request.jwt.claims='{"role":"service_role"}';
insert into claimed select public.claim_recap_job(30);
select ok((select value->>'lease' is not null from claimed limit 1),'worker atomically claims');
reset role;
select is((select count from public.recap_usage where user_id='50000000-0000-4000-8000-000000000001'),1,'admission charges one unit');
-- A crashed worker: expire both visibility and lease, retry the same job.
update public.recap_jobs set lease_until=now()-interval '1 second' where id=(select (value->>'id')::uuid from claimed limit 1);
update pgmq.q_recap_jobs set vt=now()-interval '1 second' where msg_id=(select (value->>'messageId')::bigint from claimed limit 1);
set local role service_role;
set local request.jwt.claims='{"role":"service_role"}';
insert into claimed select public.claim_recap_job(30);
reset role;
select is((select count from public.recap_usage where user_id='50000000-0000-4000-8000-000000000001'),1,'stale retry is not charged again');
select is((select attempts from public.recap_jobs where id=(select (value->>'id')::uuid from claimed limit 1)),2,'crash recovery increments bounded provider attempts');
set local role service_role;
set local request.jwt.claims='{"role":"service_role"}';
select is(public.finish_recap_job((select (value->>'id')::uuid from claimed limit 1),(select (value->>'lease')::uuid from claimed limit 1),
 (select (value->>'messageId')::bigint from claimed limit 1),'completed','Stale.', 'hy3',false),false,'stale worker cannot complete');
select is(public.finish_recap_job((select (value->>'id')::uuid from claimed offset 1 limit 1),(select (value->>'lease')::uuid from claimed offset 1 limit 1),
 (select (value->>'messageId')::bigint from claimed offset 1 limit 1),'completed','Saved.', 'hy3',false),true,'result saved before acknowledgement');
reset role;
select ok((select excerpt is null and last_sentence is null from public.recap_jobs where id=(select (value->>'id')::uuid from claimed limit 1)),'terminal processing scrubs text');
set local role authenticated;
set local request.jwt.claims='{"sub":"50000000-0000-4000-8000-000000000001","role":"authenticated"}';
select is(public.fetch_recap_jobs('s1')->'items'->0->>'summary','Saved.','lost completion response recovers persisted result');
select is(jsonb_array_length(public.fetch_recap_jobs(null,'50000000-0000-4000-8000-000000000010',0,1)->'items'),1,'page size bounded');
select is(public.fetch_recap_jobs(null,null,0,1)->>'hasMore','true','pagination reports remaining results');
select is(jsonb_array_length(public.fetch_recap_jobs(null,null,(public.fetch_recap_jobs(null,null,0,1)->>'nextCursor')::bigint,1)->'items'),1,'next page advances');
select public.delete_recap_job('s1');
select is(public.fetch_recap_jobs('s1')->'items'->0->>'state','deleted','deletion propagates as a tombstone');
select is(pg_temp.submit('s1')->>'state','deleted','deleted session cannot be recreated');
-- Simulate a timed-out submission arriving only after deletion was acknowledged.
select public.delete_recap_job('delete-before-submit');
select is(public.fetch_recap_jobs('delete-before-submit')->'items'->0->>'state','deleted','deletion of an unknown session persists a tombstone');
select is(pg_temp.submit('delete-before-submit')->>'state','deleted','delayed submission cannot recreate a deleted session');
select public.delete_recap_job('delete-before-submit');
select is(pg_temp.submit('delete-before-submit',null,repeat('Different delayed text. ',10))->>'state','deleted','repeated deletion also fences different delayed input');
reset role;
select ok((select excerpt is null and last_sentence is null and summary is null and state='deleted' from public.recap_jobs
 where user_id='50000000-0000-4000-8000-000000000001' and session_id='delete-before-submit'),'pre-admission tombstone retains no read text');
select ok(not exists(select 1 from pgmq.q_recap_jobs q join public.recap_jobs j on j.id=(q.message->>'id')::uuid
 where j.session_id='delete-before-submit'),'a deleted session never queues provider work');
select ok(not exists(select 1 from public.sync_changes where cloud_user_id='50000000-0000-4000-8000-000000000001' and entity_type='recap' and entity_id='s1' and payload->>'summary' is not null),'deletion removes historical feed summaries');

-- Quota refusal charges nothing; the same queue message resumes tomorrow.
update public.recap_settings set value=1 where key='per_user_daily_limit';
set local role service_role;
set local request.jwt.claims='{"role":"service_role"}';
select is(public.claim_recap_job(30),null,'quota-deferred job is not claimed');
reset role;
select is((select count from public.recap_usage where user_id='50000000-0000-4000-8000-000000000001'),1,'quota deferral does not charge');
update public.recap_settings set value=30 where key='per_user_daily_limit';
update public.recap_jobs set next_at=now() where session_id='unlinked';
update pgmq.q_recap_jobs set vt=now()-interval '1 second';
delete from claimed;
set local role service_role;
set local request.jwt.claims='{"role":"service_role"}';
insert into claimed select public.claim_recap_job(30);
select ok((select value is not null from claimed),'deferred job resumes');
reset role;
select is((select count from public.recap_usage where user_id='50000000-0000-4000-8000-000000000001'),2,'resumed admission is charged once');
set local role authenticated;
set local request.jwt.claims='{"sub":"50000000-0000-4000-8000-000000000001","role":"authenticated"}';
select public.set_recap_consent(false);
select is(public.fetch_recap_jobs('unlinked')->'items'->0->>'state','deleted','withdrawal cancels in-flight work');
set local role service_role;
set local request.jwt.claims='{"role":"service_role"}';
select is(public.finish_recap_job((select (value->>'id')::uuid from claimed),(select (value->>'lease')::uuid from claimed),
 (select (value->>'messageId')::bigint from claimed),'completed','Too late.', 'hy3',false),false,'withdrawn work cannot resurrect');
reset role;
select ok(not exists(select 1 from public.recap_jobs where user_id='50000000-0000-4000-8000-000000000001' and (excerpt is not null or summary is not null)),'withdrawal removes text and results');
set local role authenticated;
set local request.jwt.claims='{"sub":"50000000-0000-4000-8000-000000000001","role":"authenticated"}';
select public.set_recap_consent(true);
select is(pg_temp.submit('expired-text',null)->>'state','queued','fresh consent permits a new session');
select is(pg_temp.submit('expired-summary',null)->>'state','queued','summary retention fixture');
select is(pg_temp.submit('book-delete')->>'state','queued','book deletion fixture');
select throws_ok($$select public.submit_recap_job('invalid-position',null,'en',1000,'{"totalProgression":1.1}',repeat('Read. ',20),null)$$,
 '22023','Invalid position','out-of-range progression is refused');
select throws_ok($$select public.submit_recap_job('invalid-position',null,'en',1000,'{"totalProgression":"0.5"}',repeat('Read. ',20),null)$$,
 '22023','Invalid position','string progression is refused');
reset role;
update public.recap_jobs set expires_at=now()-interval '1 second' where session_id='expired-text';
update public.recap_jobs set state='completed',excerpt=null,last_sentence=null,summary='Expired.',result_expires_at=now()-interval '1 second' where session_id='expired-summary';
set local role authenticated;
select is(public.fetch_recap_jobs('expired-text')->'items'->0->>'state','deleted','expired work hidden before cleanup');
select is(public.fetch_recap_jobs('expired-summary')->'items'->0->>'state','deleted','expired results hidden before cleanup');
select is(public.fetch_recap_jobs('expired-summary')->'items'->0->>'summary',null,'expired summary cannot be fetched');
reset role;
delete from public.cloud_books where id='50000000-0000-4000-8000-000000000010';
select ok(not exists(select 1 from public.cloud_books where id='50000000-0000-4000-8000-000000000010'),'trigger does not cancel physical deletion');
select ok((select state='deleted' and excerpt is null and summary is null from public.recap_jobs where session_id='book-delete'),'book deletion cancels work and scrubs content');
set local role service_role;
set local request.jwt.claims='{"role":"service_role"}';
select public.cleanup_recap_jobs();
reset role;
select ok(not exists(select 1 from public.recap_jobs where session_id in ('expired-text','expired-summary') and (excerpt is not null or last_sentence is not null or summary is not null)),'retention physically scrubs content');
select ok(not exists(select 1 from pgmq.q_recap_jobs),'cleanup removes terminal queue messages');

-- Staged text is consumed only when a job is kept; every rejection leaves it
-- in place so the invited retry can resubmit the same submission.
set local role authenticated;
set local request.jwt.claims='{"sub":"50000000-0000-4000-8000-000000000001","role":"authenticated","is_anonymous":false}';
select public.put_recap_upload_part('60000000-0000-4000-8000-000000000001',0,3,repeat('Part one. ',10));
select is((select public.submit_recap_job('staged',null,'en',1000,'{"href":"chapter.xhtml","totalProgression":0.5}',
 'tail.','Last.','60000000-0000-4000-8000-000000000001',3)->>'error'),'upload_incomplete',
 'a missing staging part is refused');
reset role;
select is((select count(*)::integer from public.recap_upload_parts
 where user_id='50000000-0000-4000-8000-000000000001'),0,
 'an incomplete upload is dropped so the retry starts over');
set local role authenticated;
select public.put_recap_upload_part('60000000-0000-4000-8000-000000000002',0,2,repeat('Part one. ',10));
select is((select public.submit_recap_job('staged-conflict',null,'en',1000,'{"href":"chapter.xhtml","totalProgression":0.5}',
 repeat('First happened. ',10),null)->>'state'),'queued','staging regression fixture');
select is((select public.submit_recap_job('staged-conflict',null,'en',1000,'{"href":"chapter.xhtml","totalProgression":0.5}',
 'tail.','Last.','60000000-0000-4000-8000-000000000002',2)->>'error'),'conflict',
 'a conflicting staged submission is refused');
reset role;
select is((select count(*)::integer from public.recap_upload_parts
 where user_id='50000000-0000-4000-8000-000000000001'),1,
 'a rejected submission keeps the staged text');
set local role authenticated;
select is((select public.submit_recap_job('staged',null,'en',1000,'{"href":"chapter.xhtml","totalProgression":0.5}',
 'tail.','Last.','60000000-0000-4000-8000-000000000002',2)->>'state'),'queued',
 'the kept staging resubmits into a durable job');
reset role;
select is((select count(*)::integer from public.recap_upload_parts
 where user_id='50000000-0000-4000-8000-000000000001'),0,
 'a persisted job consumes the staged text');
-- Deletion fences are bounded, idempotent and never queue worker work.
set local role authenticated;
select throws_ok($$select public.delete_recap_job('')$$,'22023','Invalid session','empty deletion IDs are rejected');
select throws_ok($$select public.delete_recap_job(repeat('x',129))$$,'22023','Invalid session','oversized deletion IDs are rejected');
select public.delete_recap_job('delete-before-submit');
reset role;
select is((select count(*)::integer from public.sync_changes where entity_type='recap' and entity_id='delete-before-submit'),1,
 'replayed deletion does not create more sync events');

set local request.jwt.claims='{"sub":"50000000-0000-4000-8000-000000000002","role":"authenticated"}';
set local role authenticated;
select throws_ok($$select public.delete_recap_job('unconsented-fence')$$,'42501','Recap access denied',
 'an account without recap consent cannot create unknown-session fences');
reset role;
insert into public.recap_jobs(user_id,session_id,fingerprint,state,summary) values
('50000000-0000-4000-8000-000000000002','existing-without-consent','existing','completed','Remove me.');
set local role authenticated;
select public.delete_recap_job('existing-without-consent');
reset role;
select ok((select state='deleted' and summary is null from public.recap_jobs where session_id='existing-without-consent'),
 'existing results remain deletable without consent');
set local request.jwt.claims='{"sub":"50000000-0000-4000-8000-000000000002","role":"authenticated","is_anonymous":true}';
set local role authenticated;
select throws_ok($$select public.delete_recap_job('anonymous-fence')$$,'42501','Authentication required',
 'anonymous users cannot call deletion directly');
reset role;

set local request.jwt.claims='{"sub":"50000000-0000-4000-8000-000000000001","role":"authenticated"}';
insert into public.recap_jobs(user_id,session_id,fingerprint,state)
select '50000000-0000-4000-8000-000000000001','limit-daily-'||n,'','deleted' from generate_series(1,100) n;
set local role authenticated;
select throws_ok($$select public.delete_recap_job('over-daily-budget')$$,'54000','Recap deletion limit reached',
 'daily unknown-session fencing is bounded');
select public.delete_recap_job('staged');
reset role;
select ok((select state='deleted' from public.recap_jobs where session_id='staged'),
 'deleting an existing job bypasses an exhausted fence budget');
delete from public.recap_jobs where session_id like 'limit-daily-%';

insert into public.recap_jobs(user_id,session_id,fingerprint,state,created_at)
select '50000000-0000-4000-8000-000000000001','limit-account-'||n,'','deleted',now()-interval '2 days'
from generate_series(1,1000) n;
set local role authenticated;
select throws_ok($$select public.delete_recap_job('over-account-budget')$$,'54000','Recap deletion limit reached',
 'per-account permanent fence storage is bounded');
reset role;
delete from public.recap_jobs where session_id like 'limit-account-%';

insert into public.recap_jobs(user_id,session_id,fingerprint,state,created_at)
select '50000000-0000-4000-8000-000000000002','limit-global-'||n,'','deleted',now()-interval '2 days'
from generate_series(1,10000) n;
set local role authenticated;
select throws_ok($$select public.delete_recap_job('over-global-budget')$$,'54000','Recap deletion limit reached',
 'deployment-wide permanent fence storage is bounded');
reset role;
select ok(not exists(select 1 from pgmq.q_recap_jobs q join public.recap_jobs j on j.id=(q.message->>'id')::uuid
 where j.fingerprint=''),'unknown-session fences never queue provider work');
select * from finish();
rollback;
