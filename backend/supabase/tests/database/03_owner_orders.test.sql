begin;
select plan(4);

insert into auth.users (id, email) values
  ('a0000000-0000-0000-0000-0000000000a1', 'a@test.dev'),
  ('b0000000-0000-0000-0000-0000000000b1', 'b@test.dev');
insert into public.restaurants (id, name) values
  ('aaaaaaaa-0000-0000-0000-000000000001', 'Hotel A'),
  ('bbbbbbbb-0000-0000-0000-000000000001', 'Hotel B');
update public.restaurants set approval = 'approved', approved_at = now();
insert into public.owners (user_id, restaurant_id) values
  ('a0000000-0000-0000-0000-0000000000a1', 'aaaaaaaa-0000-0000-0000-000000000001'),
  ('b0000000-0000-0000-0000-0000000000b1', 'bbbbbbbb-0000-0000-0000-000000000001');
insert into public.dining_tables (id, restaurant_id, label) values
  ('e0000000-0000-0000-0000-0000000000a1', 'aaaaaaaa-0000-0000-0000-000000000001', 'T1');
insert into public.sessions (id, restaurant_id, table_id) values
  ('51000000-0000-0000-0000-0000000000a1', 'aaaaaaaa-0000-0000-0000-000000000001', 'e0000000-0000-0000-0000-0000000000a1');
insert into public.orders (id, restaurant_id, session_id, client_key) values
  ('f0000000-0000-0000-0000-0000000000a1', 'aaaaaaaa-0000-0000-0000-000000000001', '51000000-0000-0000-0000-0000000000a1', 'client-a1'),
  ('f0000000-0000-0000-0000-0000000000a2', 'aaaaaaaa-0000-0000-0000-000000000001', '51000000-0000-0000-0000-0000000000a1', 'client-a2');

select set_config('request.jwt.claims', '{"sub":"a0000000-0000-0000-0000-0000000000a1","role":"authenticated","app_role":"owner","restaurant_id":"aaaaaaaa-0000-0000-0000-000000000001"}', true);
set local role authenticated;
select public.accept_order('f0000000-0000-0000-0000-0000000000a1', 15);
select lives_ok($$select public.complete_order('f0000000-0000-0000-0000-0000000000a1')$$, 'accepted order can be marked served');
select throws_ok($$select public.complete_order('f0000000-0000-0000-0000-0000000000a2')$$, 'P0001', 'order_not_accepted', 'a pending order cannot be marked served');
select throws_ok($$select public.complete_order('f0000000-0000-0000-0000-0000000000a1')$$, 'P0001', 'order_not_accepted', 'served twice is rejected');
reset role;

select set_config('request.jwt.claims', '{"sub":"b0000000-0000-0000-0000-0000000000b1","role":"authenticated","app_role":"owner","restaurant_id":"bbbbbbbb-0000-0000-0000-000000000001"}', true);
set local role authenticated;
select throws_ok($$select public.complete_order('f0000000-0000-0000-0000-0000000000a1')$$, 'P0001', 'order_not_found', 'another hotel cannot touch the order');
reset role;

select * from finish();
rollback;
