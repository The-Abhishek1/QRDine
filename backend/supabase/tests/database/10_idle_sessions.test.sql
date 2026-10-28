begin;
select plan(9);

insert into public.restaurants (id, name, approval)
values ('88888888-0000-0000-0000-000000000001', 'Idle Session Test', 'approved');
insert into public.dining_tables (id, restaurant_id, label) values
  ('88888888-0000-0000-0000-000000000011', '88888888-0000-0000-0000-000000000001', 'T1'),
  ('88888888-0000-0000-0000-000000000012', '88888888-0000-0000-0000-000000000001', 'T2'),
  ('88888888-0000-0000-0000-000000000013', '88888888-0000-0000-0000-000000000001', 'T3'),
  ('88888888-0000-0000-0000-000000000014', '88888888-0000-0000-0000-000000000001', 'T4');
insert into public.sessions (id, restaurant_id, table_id, opened_at) values
  ('88888888-0000-0000-0000-000000000021', '88888888-0000-0000-0000-000000000001', '88888888-0000-0000-0000-000000000011', now() - interval '6 minutes'),
  ('88888888-0000-0000-0000-000000000022', '88888888-0000-0000-0000-000000000001', '88888888-0000-0000-0000-000000000012', now() - interval '6 minutes'),
  ('88888888-0000-0000-0000-000000000023', '88888888-0000-0000-0000-000000000001', '88888888-0000-0000-0000-000000000013', now() - interval '2 minutes'),
  ('88888888-0000-0000-0000-000000000024', '88888888-0000-0000-0000-000000000001', '88888888-0000-0000-0000-000000000014', now() - interval '6 minutes');
update public.dining_tables t set status = 'active', active_session_id = s.id
from public.sessions s where s.table_id = t.id;
insert into public.session_secrets (session_id, restaurant_id, pin_hash)
values ('88888888-0000-0000-0000-000000000021', '88888888-0000-0000-0000-000000000001', extensions.crypt('2468', extensions.gen_salt('bf')));
insert into public.orders (restaurant_id, session_id, client_key)
values ('88888888-0000-0000-0000-000000000001', '88888888-0000-0000-0000-000000000022', 'idle-session-order');
insert into public.service_requests (restaurant_id, session_id, type)
values ('88888888-0000-0000-0000-000000000001', '88888888-0000-0000-0000-000000000024', 'waiter');

select isnt(public.open_session_if_needed('88888888-0000-0000-0000-000000000011'),
  '88888888-0000-0000-0000-000000000021'::uuid, 'an empty session older than five minutes gets replaced');
select is((select status::text from public.sessions where id = '88888888-0000-0000-0000-000000000021'),
  'void', 'the abandoned session is closed as void');
select is(public.verify_session_pin((select active_session_id from public.dining_tables where id = '88888888-0000-0000-0000-000000000011'), '2468'),
  'ok', 'the replacement session retains the table PIN');
select is((select active_session_id from public.dining_tables where id = '88888888-0000-0000-0000-000000000011'),
  public.open_session_if_needed('88888888-0000-0000-0000-000000000011'), 'the replacement is reused on the next scan');
select is(public.open_session_if_needed('88888888-0000-0000-0000-000000000012'),
  '88888888-0000-0000-0000-000000000022'::uuid, 'a session with an order is never replaced');
select is(public.open_session_if_needed('88888888-0000-0000-0000-000000000013'),
  '88888888-0000-0000-0000-000000000023'::uuid, 'a recent empty session is reused');
select is(public.open_session_if_needed('88888888-0000-0000-0000-000000000014'),
  '88888888-0000-0000-0000-000000000024'::uuid, 'an open service request prevents replacement');
select is((select status::text from public.sessions where id = '88888888-0000-0000-0000-000000000024'),
  'active', 'a session with an open request remains active');
select is((select count(*)::int from public.sessions where table_id = '88888888-0000-0000-0000-000000000011' and status = 'active'),
  1, 'a table has only one active session after replacement');

select * from finish();
rollback;
