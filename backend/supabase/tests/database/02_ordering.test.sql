begin;
select plan(19);

insert into auth.users (id, email) values ('a0000000-0000-0000-0000-0000000000a1', 'a@test.dev');
insert into public.restaurants (id, name) values
  ('aaaaaaaa-0000-0000-0000-000000000001', 'Hotel A'),
  ('bbbbbbbb-0000-0000-0000-000000000001', 'Hotel B');
update public.restaurants set approval = 'approved', approved_at = now();
insert into public.owners (user_id, restaurant_id) values ('a0000000-0000-0000-0000-0000000000a1', 'aaaaaaaa-0000-0000-0000-000000000001');
insert into public.categories (id, restaurant_id, name) values
  ('c0000000-0000-0000-0000-0000000000a1', 'aaaaaaaa-0000-0000-0000-000000000001', 'Mains'),
  ('c0000000-0000-0000-0000-0000000000b1', 'bbbbbbbb-0000-0000-0000-000000000001', 'Mains');
insert into public.dishes (id, restaurant_id, category_id, name, base_price, is_available) values
  ('d0000000-0000-0000-0000-0000000000a1', 'aaaaaaaa-0000-0000-0000-000000000001', 'c0000000-0000-0000-0000-0000000000a1', 'Paneer', 20000, true),
  ('d0000000-0000-0000-0000-0000000000a2', 'aaaaaaaa-0000-0000-0000-000000000001', 'c0000000-0000-0000-0000-0000000000a1', 'Dosa', 9000, false),
  ('d0000000-0000-0000-0000-0000000000b1', 'bbbbbbbb-0000-0000-0000-000000000001', 'c0000000-0000-0000-0000-0000000000b1', 'Idli', 5000, true);
insert into public.dining_tables (id, restaurant_id, label) values
  ('e0000000-0000-0000-0000-0000000000a1', 'aaaaaaaa-0000-0000-0000-000000000001', 'T1'),
  ('e0000000-0000-0000-0000-0000000000a2', 'aaaaaaaa-0000-0000-0000-000000000001', 'T2'),
  ('e0000000-0000-0000-0000-0000000000a3', 'aaaaaaaa-0000-0000-0000-000000000001', 'T3');
-- T1: active session; T2: active session with a PIN; T3: closed
insert into public.sessions (id, restaurant_id, table_id) values
  ('51000000-0000-0000-0000-0000000000a1', 'aaaaaaaa-0000-0000-0000-000000000001', 'e0000000-0000-0000-0000-0000000000a1'),
  ('51000000-0000-0000-0000-0000000000a2', 'aaaaaaaa-0000-0000-0000-000000000001', 'e0000000-0000-0000-0000-0000000000a2');
update public.dining_tables set status = 'active', active_session_id = '51000000-0000-0000-0000-0000000000a1' where id = 'e0000000-0000-0000-0000-0000000000a1';
update public.dining_tables set status = 'active', active_session_id = '51000000-0000-0000-0000-0000000000a2' where id = 'e0000000-0000-0000-0000-0000000000a2';
insert into public.session_secrets (session_id, restaurant_id, pin_hash)
  values ('51000000-0000-0000-0000-0000000000a2', 'aaaaaaaa-0000-0000-0000-000000000001', extensions.crypt('1234', extensions.gen_salt('bf')));

-- 1-3: pricing from the database, snapshot, idempotency
select is((public.place_order('51000000-0000-0000-0000-0000000000a1', 'ck-000001', 'dine_in', 'no onion',
  '[{"dish_id":"d0000000-0000-0000-0000-0000000000a1","qty":2,"unit_price":1}]'::jsonb) ->> 'total')::int, 40000, 'total is computed from DB prices (client price ignored)');
select is((select unit_price from public.order_items limit 1), 20000, 'unit price snapshot stored');
select is((public.place_order('51000000-0000-0000-0000-0000000000a1', 'ck-000001', 'dine_in', null,
  '[{"dish_id":"d0000000-0000-0000-0000-0000000000a1","qty":2}]'::jsonb) ->> 'duplicate')::boolean, true, 'same client_key is idempotent');

-- 4-6: rejections
select throws_ok($$select public.place_order('51000000-0000-0000-0000-0000000000a1','ck-000002','dine_in',null,'[{"dish_id":"d0000000-0000-0000-0000-0000000000a2","qty":1}]'::jsonb)$$,
  'P0001', 'dish_unavailable:Dosa', 'out-of-stock dish is rejected');
select is((select count(*) from public.orders)::int, 1, 'failed order leaves nothing behind');
select throws_ok($$select public.place_order('51000000-0000-0000-0000-0000000000a1','ck-000003','dine_in',null,'[{"dish_id":"d0000000-0000-0000-0000-0000000000b1","qty":1}]'::jsonb)$$,
  'P0001', 'dish_not_found', 'another hotel''s dish is rejected');
select throws_ok($$select public.place_order('51000000-0000-0000-0000-0000000000a1','ck-000004','dine_in',null,'[{"dish_id":"d0000000-0000-0000-0000-0000000000a1","qty":5}]'::jsonb, 100)$$,
  'P0001', 'order_too_large', 'over the order cap is rejected');

-- 8-10: bill request and reorder (a bill can only be requested once orders are served)
update public.orders set status = 'completed' where session_id = '51000000-0000-0000-0000-0000000000a1';
select is((public.request_service('51000000-0000-0000-0000-0000000000a1', 'bill') ->> 'duplicate')::boolean, false, 'bill request created');
select is((select status::text from public.dining_tables where id = 'e0000000-0000-0000-0000-0000000000a1'), 'bill_requested', 'table shows bill requested');
select is((public.request_service('51000000-0000-0000-0000-0000000000a1', 'bill') ->> 'duplicate')::boolean, true, 'second bill request is a duplicate');
select public.place_order('51000000-0000-0000-0000-0000000000a1', 'ck-reorder', 'parcel', null, '[{"dish_id":"d0000000-0000-0000-0000-0000000000a1","qty":1}]'::jsonb);
select is((select status::text from public.dining_tables where id = 'e0000000-0000-0000-0000-0000000000a1'), 'active', 'reorder after bill request reopens the table');

-- 11-12: rate limit, closed session
do $$ begin
  for i in 1..3 loop
    perform public.place_order('51000000-0000-0000-0000-0000000000a1', 'ck-loop-' || i, 'dine_in', null, '[{"dish_id":"d0000000-0000-0000-0000-0000000000a1","qty":1}]'::jsonb);
  end loop;
end $$;
select throws_ok($$select public.place_order('51000000-0000-0000-0000-0000000000a1','ck-loop-9','dine_in',null,'[{"dish_id":"d0000000-0000-0000-0000-0000000000a1","qty":1}]'::jsonb)$$,
  'P0001', 'rate_limited', 'sixth order in 10 minutes is rate limited');
update public.sessions set status = 'paid' where id = '51000000-0000-0000-0000-0000000000a1';
select throws_ok($$select public.place_order('51000000-0000-0000-0000-0000000000a1','ck-late','dine_in',null,'[{"dish_id":"d0000000-0000-0000-0000-0000000000a1","qty":1}]'::jsonb)$$,
  'P0001', 'session_closed', 'paid session takes no more orders');

-- 13-16: PIN
select is(public.verify_session_pin('51000000-0000-0000-0000-0000000000a2', null), 'pin_required', 'missing PIN is not counted as an attempt');
select is(public.verify_session_pin('51000000-0000-0000-0000-0000000000a2', '1234'), 'ok', 'correct PIN accepted');
do $$ begin for i in 1..5 loop perform public.verify_session_pin('51000000-0000-0000-0000-0000000000a2', '0000'); end loop; end $$;
select is(public.verify_session_pin('51000000-0000-0000-0000-0000000000a2', '1234'), 'locked', 'five wrong PINs lock the session even for the right PIN');

-- 17: clients cannot call ordering functions
set local role authenticated;
select throws_ok($$select public.place_order('51000000-0000-0000-0000-0000000000a2','ck-x1234','dine_in',null,'[]'::jsonb)$$, '42501', null, 'authenticated cannot call place_order');
reset role;

-- 18-19: void_session (owner)
select set_config('request.jwt.claims', '{"sub":"a0000000-0000-0000-0000-0000000000a1","role":"authenticated","app_role":"owner","restaurant_id":"aaaaaaaa-0000-0000-0000-000000000001"}', true);
set local role authenticated;
select throws_ok($$select public.void_session('e0000000-0000-0000-0000-0000000000a3')$$, 'P0001', 'table_not_active', 'closed table cannot be voided');
reset role;
select public.open_session_if_needed('e0000000-0000-0000-0000-0000000000a3');
set local role authenticated;
select lives_ok($$select public.void_session('e0000000-0000-0000-0000-0000000000a3')$$, 'empty session can be voided');
reset role;

select * from finish();
rollback;
