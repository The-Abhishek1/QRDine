begin;
select plan(25);

insert into auth.users (id, email) values
  ('a0000000-0000-0000-0000-0000000000a1', 'a@test.dev'),
  ('b0000000-0000-0000-0000-0000000000b1', 'b@test.dev');
insert into public.restaurants (id, name, gst_mode, gst_rate) values
  ('aaaaaaaa-0000-0000-0000-000000000001', 'Hotel A', 'regular', 5),
  ('bbbbbbbb-0000-0000-0000-000000000001', 'Hotel B', 'none', 0);
update public.restaurants set approval = 'approved', approved_at = now();
insert into public.owners (user_id, restaurant_id) values
  ('a0000000-0000-0000-0000-0000000000a1', 'aaaaaaaa-0000-0000-0000-000000000001'),
  ('b0000000-0000-0000-0000-0000000000b1', 'bbbbbbbb-0000-0000-0000-000000000001');
insert into public.categories (id, restaurant_id, name) values ('c0000000-0000-0000-0000-0000000000a1', 'aaaaaaaa-0000-0000-0000-000000000001', 'Mains');
insert into public.dishes (id, restaurant_id, category_id, name, base_price) values
  ('d0000000-0000-0000-0000-0000000000a1', 'aaaaaaaa-0000-0000-0000-000000000001', 'c0000000-0000-0000-0000-0000000000a1', 'Paneer', 20000);
insert into public.dining_tables (id, restaurant_id, label) values
  ('e0000000-0000-0000-0000-0000000000a1', 'aaaaaaaa-0000-0000-0000-000000000001', 'T1'),
  ('e0000000-0000-0000-0000-0000000000a2', 'aaaaaaaa-0000-0000-0000-000000000001', 'T2'),
  ('e0000000-0000-0000-0000-0000000000a3', 'aaaaaaaa-0000-0000-0000-000000000001', 'T3');
insert into public.sessions (id, restaurant_id, table_id) values
  ('51000000-0000-0000-0000-0000000000a1', 'aaaaaaaa-0000-0000-0000-000000000001', 'e0000000-0000-0000-0000-0000000000a1'),
  ('51000000-0000-0000-0000-0000000000a2', 'aaaaaaaa-0000-0000-0000-000000000001', 'e0000000-0000-0000-0000-0000000000a2'),
  ('51000000-0000-0000-0000-0000000000a3', 'aaaaaaaa-0000-0000-0000-000000000001', 'e0000000-0000-0000-0000-0000000000a3');
update public.dining_tables t set status = 'active', active_session_id = s.id from public.sessions s where s.table_id = t.id;

select public.place_order('51000000-0000-0000-0000-0000000000a1', 'ck-000001', 'dine_in', null, '[{"dish_id":"d0000000-0000-0000-0000-0000000000a1","qty":2}]'::jsonb);

-- bill request rules
select throws_ok($$select public.request_service('51000000-0000-0000-0000-0000000000a1','bill')$$, 'P0001', 'orders_not_served', 'bill blocked while an order is still pending');
select throws_ok($$select public.request_service('51000000-0000-0000-0000-0000000000a2','bill')$$, 'P0001', 'nothing_to_bill', 'bill blocked when nothing was ordered');
update public.orders set status = 'completed' where session_id = '51000000-0000-0000-0000-0000000000a1';

-- pricing, GST, coupon
select is((public.compute_bill('51000000-0000-0000-0000-0000000000a1') ->> 'total')::int, 42000, 'GST 5% added: 40000 + 2000');
select is((public.compute_bill('51000000-0000-0000-0000-0000000000a1') ->> 'cgst')::int, 1000, 'CGST is half the tax');
insert into public.coupons (restaurant_id, code, type, value) values ('aaaaaaaa-0000-0000-0000-000000000001', 'SAVE10', 'percent', 10);
select is((public.compute_bill('51000000-0000-0000-0000-0000000000a1', 'save10') ->> 'total')::int, 37800, '10% coupon applied before GST');
select throws_ok($$select public.compute_bill('51000000-0000-0000-0000-0000000000a1','NOPE')$$, 'P0001', 'coupon_invalid', 'unknown coupon rejected');

-- bill request then reorder cancels it
select is((public.request_service('51000000-0000-0000-0000-0000000000a1', 'bill') ->> 'duplicate')::boolean, false, 'bill can be requested once everything is served');
select public.place_order('51000000-0000-0000-0000-0000000000a1', 'ck-000002', 'dine_in', null, '[{"dish_id":"d0000000-0000-0000-0000-0000000000a1","qty":1}]'::jsonb);
select is((select status::text from public.service_requests where session_id = '51000000-0000-0000-0000-0000000000a1' and type = 'bill'), 'resolved', 'reordering cancels the open bill request');
update public.orders set status = 'completed' where session_id = '51000000-0000-0000-0000-0000000000a1';

-- another hotel cannot close it
select set_config('request.jwt.claims', '{"sub":"b0000000-0000-0000-0000-0000000000b1","role":"authenticated","app_role":"owner","restaurant_id":"bbbbbbbb-0000-0000-0000-000000000001"}', true);
set local role authenticated;
select throws_ok($$select public.close_session('51000000-0000-0000-0000-0000000000a1','cash')$$, 'P0001', 'session_not_found', 'another hotel cannot close the session');
select throws_ok($$select public.get_bill('51000000-0000-0000-0000-0000000000a3')$$, 'P0001', 'session_not_found', 'another hotel cannot read the bill');
reset role;

-- owner A pays
select set_config('request.jwt.claims', '{"sub":"a0000000-0000-0000-0000-0000000000a1","role":"authenticated","app_role":"owner","restaurant_id":"aaaaaaaa-0000-0000-0000-000000000001"}', true);
set local role authenticated;
select is((public.close_session('51000000-0000-0000-0000-0000000000a1', 'cash', null, 'SAVE10') ->> 'number')::int, 1, 'first invoice is number 1');
select is((select status::text from public.sessions where id = '51000000-0000-0000-0000-0000000000a1'), 'paid', 'session is paid');
select is((select status::text from public.dining_tables where id = 'e0000000-0000-0000-0000-0000000000a1'), 'closed', 'table returns to closed');
select is((select total from public.sessions where id = '51000000-0000-0000-0000-0000000000a1'), 56700, '3 dishes, 10% off, 5% GST = 56700');
reset role;

-- second invoice gets the next number
select public.place_order('51000000-0000-0000-0000-0000000000a2', 'ck-000003', 'dine_in', null, '[{"dish_id":"d0000000-0000-0000-0000-0000000000a1","qty":1}]'::jsonb);
update public.orders set status = 'completed' where session_id = '51000000-0000-0000-0000-0000000000a2';
select set_config('request.jwt.claims', '{"sub":"a0000000-0000-0000-0000-0000000000a1","role":"authenticated","app_role":"owner","restaurant_id":"aaaaaaaa-0000-0000-0000-000000000001"}', true);
set local role authenticated;
select is((public.close_session('51000000-0000-0000-0000-0000000000a2', 'upi') ->> 'number')::int, 2, 'invoice numbers are consecutive');
reset role;

-- pending orders block closing; split must add up
select public.place_order('51000000-0000-0000-0000-0000000000a3', 'ck-000004', 'dine_in', null, '[{"dish_id":"d0000000-0000-0000-0000-0000000000a1","qty":1}]'::jsonb);
select set_config('request.jwt.claims', '{"sub":"a0000000-0000-0000-0000-0000000000a1","role":"authenticated","app_role":"owner","restaurant_id":"aaaaaaaa-0000-0000-0000-000000000001"}', true);
set local role authenticated;
select throws_ok($$select public.close_session('51000000-0000-0000-0000-0000000000a3','cash')$$, 'P0001', 'orders_pending', 'a waiting order must be accepted or rejected first');
select lives_ok($$select public.get_bill('51000000-0000-0000-0000-0000000000a3')$$, 'owner can read the running bill');
reset role;
update public.orders set status = 'completed' where session_id = '51000000-0000-0000-0000-0000000000a3';
set local role authenticated;
select throws_ok($$select public.close_session('51000000-0000-0000-0000-0000000000a3','split','{"cash":100,"upi":100}'::jsonb)$$, 'P0001', 'split_mismatch', 'split must equal the total');
select is(jsonb_array_length(public.owner_serving()), 1, 'only the table still in service is listed');
select is((public.owner_stats(now() - interval '1 day', now() + interval '1 day') ->> 'revenue')::int, 77700, 'revenue = 56700 + 21000');
reset role;

-- reviews (service role) and category delete (owner)
select lives_ok($$select public.submit_review('51000000-0000-0000-0000-0000000000a1', 5, 4, 'great food')$$, 'a paid session can be reviewed');
select throws_ok($$select public.submit_review('51000000-0000-0000-0000-0000000000a1', 5, 4, 'again')$$, 'P0001', 'already_reviewed', 'only one review per session');
insert into public.categories (id, restaurant_id, name) values ('c0000000-0000-0000-0000-0000000000a2', 'aaaaaaaa-0000-0000-0000-000000000001', 'Empty');
set local role authenticated;
select throws_ok($$select public.delete_category('c0000000-0000-0000-0000-0000000000a1')$$, 'P0001', 'category_has_dishes', 'category with dishes cannot be deleted');
select lives_ok($$select public.delete_category('c0000000-0000-0000-0000-0000000000a2')$$, 'empty category can be deleted');
select throws_ok($$select public.compute_bill('51000000-0000-0000-0000-0000000000a3')$$, '42501', null, 'clients cannot call compute_bill directly');
reset role;

select * from finish();
rollback;
