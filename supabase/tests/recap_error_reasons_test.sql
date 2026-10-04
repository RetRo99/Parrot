begin;
create extension if not exists pgtap with schema extensions;
set search_path=extensions,public;
select no_plan();
insert into auth.users(id,instance_id,aud,role,email) values
('51000000-0000-4000-8000-000000000001','00000000-0000-0000-0000-000000000000','authenticated','authenticated','recap-reasons@example.invalid');
insert into public.cloud_feature_allowlist(cloud_user_id,feature) values
('51000000-0000-4000-8000-000000000001','recap');
set local role authenticated;
set local request.jwt.claims='{"sub":"51000000-0000-4000-8000-000000000001","role":"authenticated","is_anonymous":false}';
select public.set_recap_consent(true);
select public.submit_recap_job('expired',null,'en',1000,'{}',repeat('An event happened. ',10),'Last.');
select public.submit_recap_job('removed',null,'en',1000,'{}',repeat('An event happened. ',10),'Last.');
select public.submit_recap_job('withdrawn',null,'en',1000,'{}',repeat('An event happened. ',10),'Last.');
reset role;
update public.recap_jobs set expires_at=now()-interval '1 second' where session_id='expired' and user_id='51000000-0000-4000-8000-000000000001';
select is((select public.recap_projection(j)->>'errorCode' from public.recap_jobs j where session_id='expired' and user_id='51000000-0000-4000-8000-000000000001'),
 'EXCERPT_EXPIRED','expiry is not projected as consent withdrawal');
delete from public.cloud_feature_allowlist where cloud_user_id='51000000-0000-4000-8000-000000000001' and feature='recap';
update public.recap_jobs set state='failed',error_code='EXCERPT_EXPIRED',excerpt=null,last_sentence=null
 where session_id='removed' and user_id='51000000-0000-4000-8000-000000000001';
select is((select error_code from public.recap_jobs where session_id='removed' and user_id='51000000-0000-4000-8000-000000000001'),
 'ACCESS_REMOVED','allowlist removal is not mislabeled as expiry');
set local role authenticated;
set local request.jwt.claims='{"sub":"51000000-0000-4000-8000-000000000001","role":"authenticated"}';
select public.set_recap_consent(false);
reset role;
select is((select error_code from public.recap_jobs where session_id='withdrawn' and user_id='51000000-0000-4000-8000-000000000001'),
 'CONSENT_WITHDRAWN','withdrawal retains its actual reason');
select ok((select bool_and(excerpt is null and summary is null) from public.recap_jobs where user_id='51000000-0000-4000-8000-000000000001'),
 'withdrawal still scrubs all content');
select * from finish();
rollback;
