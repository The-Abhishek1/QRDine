begin;
select plan(14);

-- fixtures (as superuser)
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
insert into public.categories (id, restaurant_id, name) values
  ('c0000000-0000-0000-0000-0000000000a1', 'aaaaaaaa-0000-0000-0000-000000000001', 'Mains'),
  ('c0000000-0000-0000-0000-0000000000b1', 'bbbbbbbb-0000-0000-0000-000000000001', 'Mains');
insert into public.dishes (id, restaurant_id, category_id, name, base_price) values
  ('d0000000-0000-0000-0000-0000000000a1', 'aaaaaaaa-0000-0000-0000-000000000001', 'c0000000-0000-0000-0000-0000000000a1', 'Paneer', 20000),
  ('d0000000-0000-0000-0000-0000000000b1', 'bbbbbbbb-0000-0000-0000-000000000001', 'c0000000-0000-0000-0000-0000000000b1', 'Dosa', 9000);
insert into public.dining_tables (id, restaurant_id, label) values
  ('e0000000-0000-0000-0000-0000000000a1', 'aaaaaaaa-0000-0000-0000-000000000001', 'T1'),
  ('e0000000-0000-0000-0000-0000000000a2', 'aaaaaaaa-0000-0000-0000-000000000001', 'T2'),
  ('e0000000-0000-0000-0000-0000000000b1', 'bbbbbbbb-0000-0000-0000-000000000001', 'T1');
insert into public.sessions (id, restaurant_id, table_id) values
  ('51000000-0000-0000-0000-0000000000a1', 'aaaaaaaa-0000-0000-0000-000000000001', 'e0000000-0000-0000-0000-0000000000a1'),
  ('51000000-0000-0000-0000-0000000000a2', 'aaaaaaaa-0000-0000-0000-000000000001', 'e0000000-0000-0000-0000-0000000000a2'),
  ('51000000-0000-0000-0000-0000000000b1', 'bbbbbbbb-0000-0000-0000-000000000001', 'e0000000-0000-0000-0000-0000000000b1');
insert into public.orders (id, restaurant_id, session_id, client_key) values
  ('f0000000-0000-0000-0000-0000000000a1', 'aaaaaaaa-0000-0000-0000-000000000001', '51000000-0000-0000-0000-0000000000a1', 'client-a1'),
  ('f0000000-0000-0000-0000-0000000000a2', 'aaaaaaaa-0000-0000-0000-000000000001', '51000000-0000-0000-0000-0000000000a2', 'client-a2'),
  ('f0000000-0000-0000-0000-0000000000b1', 'bbbbbbbb-0000-0000-0000-000000000001', '51000000-0000-0000-0000-0000000000b1', 'client-b1');

-- 1. anon
set local role anon;
select throws_ok($$select * from public.dishes$$, '42501', null, 'anon cannot read the menu table');
reset role;

-- owner A
select set_config('request.jwt.claims', '{"sub":"a0000000-0000-0000-0000-0000000000a1","role":"authenticated","app_role":"owner","restaurant_id":"aaaaaaaa-0000-0000-0000-000000000001"}', true);
set local role authenticated;
select is((select count(*) from public.dishes)::int, 1, 'owner A sees only own dishes');
select is((select count(*) from public.orders)::int, 2, 'owner A sees only own orders');
select throws_ok($$insert into public.dishes (restaurant_id, category_id, name, base_price) values ('bbbbbbbb-0000-0000-0000-000000000001','c0000000-0000-0000-0000-0000000000b1','x',100)$$,
  '42501', null, 'owner A cannot write into hotel B');
select throws_ok($$update public.restaurants set sub_status = 'active' where id = 'aaaaaaaa-0000-0000-0000-000000000001'$$,
  '42501', null, 'owner cannot grant themselves a subscription');
select throws_ok($$update public.orders set status = 'accepted' where id = 'f0000000-0000-0000-0000-0000000000a1'$$,
  '42501', null, 'owner cannot update orders directly');
select lives_ok($$select public.accept_order('f0000000-0000-0000-0000-0000000000a1', 15)$$, 'owner A accepts own order');
select throws_ok($$select public.accept_order('f0000000-0000-0000-0000-0000000000a1', 15)$$,
  'P0001', 'order_not_pending', 'an order cannot be accepted twice');
reset role;

-- owner B
select set_config('request.jwt.claims', '{"sub":"b0000000-0000-0000-0000-0000000000b1","role":"authenticated","app_role":"owner","restaurant_id":"bbbbbbbb-0000-0000-0000-000000000001"}', true);
set local role authenticated;
select throws_ok($$select public.accept_order('f0000000-0000-0000-0000-0000000000a2', 10)$$,
  'P0001', 'order_not_found', 'owner B cannot touch a hotel A order');
reset role;

-- guest of session A1 (same hotel as owner A, but must not inherit owner rights)
select set_config('request.jwt.claims', '{"sub":"99999999-0000-0000-0000-000000000001","role":"authenticated","app_role":"guest","restaurant_id":"aaaaaaaa-0000-0000-0000-000000000001","session_id":"51000000-0000-0000-0000-0000000000a1"}', true);
set local role authenticated;
select is((select count(*) from public.orders)::int, 1, 'guest sees only its own session order');
select is((select count(*) from public.dishes)::int, 0, 'guest has no direct menu access');
select throws_ok($$insert into public.orders (restaurant_id, session_id, client_key) values ('aaaaaaaa-0000-0000-0000-000000000001','51000000-0000-0000-0000-0000000000a1','client-x1')$$,
  '42501', null, 'guest cannot insert orders directly');
select throws_ok($$select public.accept_order('f0000000-0000-0000-0000-0000000000a1', 5)$$,
  'P0001', 'order_not_found', 'guest cannot accept orders');
reset role;

-- anon cannot call owner functions
set local role anon;
select throws_ok($$select public.open_table('e0000000-0000-0000-0000-0000000000a1')$$, '42501', null, 'anon cannot call open_table');
reset role;

select * from finish();
rollback;
