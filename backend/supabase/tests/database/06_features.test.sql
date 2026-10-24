begin;
select plan(41);

insert into auth.users (id, email) values
  ('a0000000-0000-0000-0000-0000000000a1', 'owner@test.dev'), ('a0000000-0000-0000-0000-0000000000a2', 'waiter@test.dev'),
  ('a0000000-0000-0000-0000-0000000000a3', 'kitchen@test.dev'), ('b0000000-0000-0000-0000-0000000000b1', 'b@test.dev');
insert into public.restaurants (id, name, approval) values
  ('aaaaaaaa-0000-0000-0000-000000000001', 'Hotel A', 'approved'), ('bbbbbbbb-0000-0000-0000-000000000001', 'Hotel B', 'approved');
insert into public.owners (user_id, restaurant_id, role, display_name) values
  ('a0000000-0000-0000-0000-0000000000a1', 'aaaaaaaa-0000-0000-0000-000000000001', 'owner', null),
  ('a0000000-0000-0000-0000-0000000000a2', 'aaaaaaaa-0000-0000-0000-000000000001', 'waiter', 'Ravi'),
  ('a0000000-0000-0000-0000-0000000000a3', 'aaaaaaaa-0000-0000-0000-000000000001', 'kitchen', 'Chef'),
  ('b0000000-0000-0000-0000-0000000000b1', 'bbbbbbbb-0000-0000-0000-000000000001', 'owner', null);
insert into public.categories (id, restaurant_id, name) values
  ('c0000000-0000-0000-0000-0000000000a1', 'aaaaaaaa-0000-0000-0000-000000000001', 'Mains'),
  ('c0000000-0000-0000-0000-0000000000a2', 'aaaaaaaa-0000-0000-0000-000000000001', 'Drinks');
insert into public.dishes (id, restaurant_id, category_id, name, base_price, stock_qty) values
  ('d0000000-0000-0000-0000-0000000000a1', 'aaaaaaaa-0000-0000-0000-000000000001', 'c0000000-0000-0000-0000-0000000000a1', 'Paneer', 20000, null),
  ('d0000000-0000-0000-0000-0000000000a2', 'aaaaaaaa-0000-0000-0000-000000000001', 'c0000000-0000-0000-0000-0000000000a1', 'Dosa', 9000, 3),
  ('d0000000-0000-0000-0000-0000000000a3', 'aaaaaaaa-0000-0000-0000-000000000001', 'c0000000-0000-0000-0000-0000000000a2', 'Tea', 1000, null),
  ('d0000000-0000-0000-0000-0000000000a4', 'aaaaaaaa-0000-0000-0000-000000000001', 'c0000000-0000-0000-0000-0000000000a1', 'Rice', 5000, null);
insert into public.dish_variants (id, restaurant_id, dish_id, name, price) values
  ('f1000000-0000-0000-0000-0000000000a1', 'aaaaaaaa-0000-0000-0000-000000000001', 'd0000000-0000-0000-0000-0000000000a1', 'Half', 12000),
  ('f1000000-0000-0000-0000-0000000000a2', 'aaaaaaaa-0000-0000-0000-000000000001', 'd0000000-0000-0000-0000-0000000000a1', 'Full', 20000);
insert into public.addon_groups (id, restaurant_id, name, min_select, max_select) values
  ('a9000000-0000-0000-0000-0000000000a1', 'aaaaaaaa-0000-0000-0000-000000000001', 'Extras', 0, 1);
insert into public.addons (id, restaurant_id, group_id, name, price) values
  ('ad000000-0000-0000-0000-0000000000a1', 'aaaaaaaa-0000-0000-0000-000000000001', 'a9000000-0000-0000-0000-0000000000a1', 'Cheese', 3000),
  ('ad000000-0000-0000-0000-0000000000a2', 'aaaaaaaa-0000-0000-0000-000000000001', 'a9000000-0000-0000-0000-0000000000a1', 'Butter', 2000);
insert into public.dish_addon_groups (restaurant_id, dish_id, group_id) values
  ('aaaaaaaa-0000-0000-0000-000000000001', 'd0000000-0000-0000-0000-0000000000a1', 'a9000000-0000-0000-0000-0000000000a1');
insert into public.dining_tables (id, restaurant_id, label) values
  ('e0000000-0000-0000-0000-0000000000a1', 'aaaaaaaa-0000-0000-0000-000000000001', 'T1'),
  ('e0000000-0000-0000-0000-0000000000a2', 'aaaaaaaa-0000-0000-0000-000000000001', 'T2'),
  ('e0000000-0000-0000-0000-0000000000a3', 'aaaaaaaa-0000-0000-0000-000000000001', 'T3');
insert into public.sessions (id, restaurant_id, table_id) values
  ('51000000-0000-0000-0000-0000000000a1', 'aaaaaaaa-0000-0000-0000-000000000001', 'e0000000-0000-0000-0000-0000000000a1'),
  ('51000000-0000-0000-0000-0000000000a2', 'aaaaaaaa-0000-0000-0000-000000000001', 'e0000000-0000-0000-0000-0000000000a2');
update public.dining_tables t set status = 'active', active_session_id = s.id from public.sessions s where s.table_id = t.id;

-- 1-5 variants and add-ons
select is((public.place_order('51000000-0000-0000-0000-0000000000a1', 'ck-variant1', 'dine_in', null,
  '[{"dish_id":"d0000000-0000-0000-0000-0000000000a1","qty":1,"variant_id":"f1000000-0000-0000-0000-0000000000a1","addon_ids":["ad000000-0000-0000-0000-0000000000a1"]}]'::jsonb) ->> 'total')::int,
  15000, 'half Paneer (12000) + cheese (3000) is 15000');
select is((select public.item_label(name_snapshot, variant_snapshot, addons) from public.order_items limit 1), 'Paneer (Half) + Cheese', 'bill label shows variant and add-on');
select throws_ok($$select public.place_order('51000000-0000-0000-0000-0000000000a1','ck-variant2','dine_in',null,'[{"dish_id":"d0000000-0000-0000-0000-0000000000a1","qty":1}]'::jsonb)$$,
  'P0001', 'variant_required:Paneer', 'a dish with variants needs one chosen');
select throws_ok($$select public.place_order('51000000-0000-0000-0000-0000000000a1','ck-variant3','dine_in',null,'[{"dish_id":"d0000000-0000-0000-0000-0000000000a1","qty":1,"variant_id":"f1000000-0000-0000-0000-0000000000a1","addon_ids":["ad000000-0000-0000-0000-0000000000a1","ad000000-0000-0000-0000-0000000000a2"]}]'::jsonb)$$,
  'P0001', 'addon_choice:Extras', 'too many add-ons in a group is rejected');
select throws_ok($$select public.place_order('51000000-0000-0000-0000-0000000000a1','ck-variant4','dine_in',null,'[{"dish_id":"d0000000-0000-0000-0000-0000000000a4","qty":1,"variant_id":"f1000000-0000-0000-0000-0000000000a1"}]'::jsonb)$$,
  'P0001', 'bad_items', 'a variant of another dish is rejected');

-- 6-9 stock
select public.place_order('51000000-0000-0000-0000-0000000000a1', 'ck-dosa-two', 'dine_in', null, '[{"dish_id":"d0000000-0000-0000-0000-0000000000a2","qty":2}]'::jsonb);
select is((select stock_qty from public.dishes where id = 'd0000000-0000-0000-0000-0000000000a2'), 1, 'stock goes down with each order');
select throws_ok($$select public.place_order('51000000-0000-0000-0000-0000000000a1','ck-dosa-bad','dine_in',null,'[{"dish_id":"d0000000-0000-0000-0000-0000000000a2","qty":2}]'::jsonb)$$,
  'P0001', 'dish_unavailable:Dosa (only 1 left)', 'cannot order more than is left');
select public.place_order('51000000-0000-0000-0000-0000000000a1', 'ck-dosa-last', 'dine_in', null, '[{"dish_id":"d0000000-0000-0000-0000-0000000000a2","qty":1}]'::jsonb);
select is((select is_available from public.dishes where id = 'd0000000-0000-0000-0000-0000000000a2'), false, 'the dish sells out by itself at zero');

-- as owner: rejecting an order gives the stock back
select set_config('request.jwt.claims', '{"sub":"a0000000-0000-0000-0000-0000000000a1","role":"authenticated","app_role":"owner","restaurant_id":"aaaaaaaa-0000-0000-0000-000000000001"}', true);
set local role authenticated;
select public.reject_order((select id from public.orders where client_key = 'ck-dosa-last'), 'test');
reset role;
select is((select stock_qty from public.dishes where id = 'd0000000-0000-0000-0000-0000000000a2'), 1, 'a rejected order returns its stock');
select is((select is_available from public.dishes where id = 'd0000000-0000-0000-0000-0000000000a2'), true, 'and the dish is available again');

-- 11 timed menu: a window that is surely not now
update public.categories set avail_from = ((now() at time zone 'Asia/Kolkata')::time + interval '2 hours'),
       avail_to = ((now() at time zone 'Asia/Kolkata')::time + interval '3 hours') where id = 'c0000000-0000-0000-0000-0000000000a2';
select throws_ok($$select public.place_order('51000000-0000-0000-0000-0000000000a1','ck-tea-001','dine_in',null,'[{"dish_id":"d0000000-0000-0000-0000-0000000000a3","qty":1}]'::jsonb)$$,
  'P0001', 'dish_unavailable:Tea (not served right now)', 'a timed category refuses orders outside its hours');

-- 12-16 waiter: add an order, cancel an item
select set_config('request.jwt.claims', '{"sub":"a0000000-0000-0000-0000-0000000000a2","role":"authenticated","app_role":"waiter","restaurant_id":"aaaaaaaa-0000-0000-0000-000000000001"}', true);
set local role authenticated;
select lives_ok($$select public.owner_add_order('51000000-0000-0000-0000-0000000000a2', '[{"dish_id":"d0000000-0000-0000-0000-0000000000a4","qty":2}]'::jsonb)$$, 'a waiter can add an order for a table');
reset role;
select is((select status::text || '/' || source from public.orders where session_id = '51000000-0000-0000-0000-0000000000a2' limit 1), 'accepted/staff', 'a staff order starts cooking');
set local role authenticated;
select lives_ok($$select public.cancel_order_item((select id from public.order_items where session_id = '51000000-0000-0000-0000-0000000000a2' limit 1), 'customer changed mind')$$, 'a waiter can cancel an item with a reason');
select throws_ok($$select public.cancel_order_item((select id from public.order_items where session_id = '51000000-0000-0000-0000-0000000000a2' limit 1), 'again')$$,
  'P0001', 'item_cancelled', 'an item cannot be cancelled twice');
reset role;
select is((select status::text from public.orders where session_id = '51000000-0000-0000-0000-0000000000a2' limit 1), 'cancelled', 'cancelling every item cancels the order');

-- 17-22 roles
set local role authenticated;
select public.owner_add_order('51000000-0000-0000-0000-0000000000a2', '[{"dish_id":"d0000000-0000-0000-0000-0000000000a4","qty":1}]'::jsonb);
select throws_ok($$select public.owner_stats(now() - interval '1 day', now())$$, 'P0001', 'not_owner', 'a waiter cannot read insights');
select throws_ok($$insert into public.dishes (restaurant_id, category_id, name, base_price) values ('aaaaaaaa-0000-0000-0000-000000000001','c0000000-0000-0000-0000-0000000000a1','Hack',1)$$,
  '42501', null, 'a waiter cannot add to the menu');
select lives_ok($$select public.accept_order((select id from public.orders where client_key = 'ck-variant1'), 15)$$, 'a waiter can accept an order');
reset role;
select set_config('request.jwt.claims', '{"sub":"a0000000-0000-0000-0000-0000000000a3","role":"authenticated","app_role":"kitchen","restaurant_id":"aaaaaaaa-0000-0000-0000-000000000001"}', true);
set local role authenticated;
select is((select count(*) from public.orders)::int > 0, true, 'the kitchen can see orders');
select lives_ok($$select public.mark_order_ready((select id from public.orders where client_key = 'ck-variant1'))$$, 'the kitchen can mark an order ready');
select throws_ok($$select public.accept_order((select id from public.orders where client_key = 'ck-dosa-two'), 10)$$, 'P0001', 'order_not_found', 'the kitchen cannot accept orders');
select throws_ok($$select public.get_bill('51000000-0000-0000-0000-0000000000a1')$$, 'P0001', 'session_not_found', 'the kitchen cannot see bills');
reset role;
select is((select ready_at is not null from public.orders where client_key = 'ck-variant1'), true, 'ready time is stored');

-- 23-24 move and merge
select set_config('request.jwt.claims', '{"sub":"a0000000-0000-0000-0000-0000000000a2","role":"authenticated","app_role":"waiter","restaurant_id":"aaaaaaaa-0000-0000-0000-000000000001"}', true);
set local role authenticated;
select lives_ok($$select public.move_session('51000000-0000-0000-0000-0000000000a2', 'e0000000-0000-0000-0000-0000000000a3')$$, 'a party can move to a free table');
reset role;
select is((select status::text from public.dining_tables where id = 'e0000000-0000-0000-0000-0000000000a2'), 'closed', 'the old table is free');
set local role authenticated;
select lives_ok($$select public.merge_sessions('51000000-0000-0000-0000-0000000000a2', '51000000-0000-0000-0000-0000000000a1')$$, 'two tables can be merged into one bill');
reset role;
select is((select count(*)::int from public.orders where session_id = '51000000-0000-0000-0000-0000000000a1' and source = 'staff'), 2, 'the merged table brings its orders along');

-- 25-29 manager PIN discount
update public.orders set status = 'completed' where session_id = '51000000-0000-0000-0000-0000000000a1' and status in ('accepted', 'pending');
select set_config('request.jwt.claims', '{"sub":"a0000000-0000-0000-0000-0000000000a1","role":"authenticated","app_role":"owner","restaurant_id":"aaaaaaaa-0000-0000-0000-000000000001"}', true);
set local role authenticated;
select is((public.set_manager_pin('1234') ->> 'ok')::boolean, true, 'the owner sets a manager PIN');
select is((public.close_session('51000000-0000-0000-0000-0000000000a1', 'cash', null, null, 1000, null) ->> 'error'), 'pin_required', 'a discount without the PIN is refused');
select is((public.close_session('51000000-0000-0000-0000-0000000000a1', 'cash', null, null, 1000, '9999') ->> 'error'), 'pin_wrong', 'a wrong PIN is refused');
select is((public.close_session('51000000-0000-0000-0000-0000000000a1', 'cash', null, null, 1000, '1234') ->> 'number')::int, 1, 'the right PIN lets the discount through');
reset role;
select is((select (snapshot -> 'bill' ->> 'manual_discount')::int from public.invoices where session_id = '51000000-0000-0000-0000-0000000000a1'), 1000, 'the discount is on the bill');

-- 30-35 guest coupon, PIN lockout, cash count, insights, disabled staff
insert into public.dining_tables (id, restaurant_id, label) values ('e0000000-0000-0000-0000-0000000000a4', 'aaaaaaaa-0000-0000-0000-000000000001', 'T4');
insert into public.sessions (id, restaurant_id, table_id) values ('51000000-0000-0000-0000-0000000000a4', 'aaaaaaaa-0000-0000-0000-000000000001', 'e0000000-0000-0000-0000-0000000000a4');
update public.dining_tables set status = 'active', active_session_id = '51000000-0000-0000-0000-0000000000a4' where id = 'e0000000-0000-0000-0000-0000000000a4';
select public.place_order('51000000-0000-0000-0000-0000000000a4', 'ck-coupon-1', 'dine_in', null, '[{"dish_id":"d0000000-0000-0000-0000-0000000000a4","qty":2}]'::jsonb);
update public.orders set status = 'completed' where session_id = '51000000-0000-0000-0000-0000000000a4';
insert into public.coupons (restaurant_id, code, type, value) values ('aaaaaaaa-0000-0000-0000-000000000001', 'SAVE10', 'percent', 10);
select is((public.apply_session_coupon('51000000-0000-0000-0000-0000000000a4', 'save10') ->> 'discount')::int, 1000, 'a guest can apply a coupon to their bill');
select throws_ok($$select public.apply_session_coupon('51000000-0000-0000-0000-0000000000a4', 'NOPE')$$, 'P0001', 'coupon_invalid', 'an unknown coupon is refused');
select is(public.compute_bill('51000000-0000-0000-0000-0000000000a4') ->> 'coupon', 'SAVE10', 'the staff see the guest coupon on the bill');

select set_config('request.jwt.claims', '{"sub":"a0000000-0000-0000-0000-0000000000a1","role":"authenticated","app_role":"owner","restaurant_id":"aaaaaaaa-0000-0000-0000-000000000001"}', true);
set local role authenticated;
do $$ begin for i in 1..5 loop perform public.close_session('51000000-0000-0000-0000-0000000000a4', 'cash', null, null, 500, '0000'); end loop; end $$;
select is((public.close_session('51000000-0000-0000-0000-0000000000a4', 'cash', null, null, 500, '1234') ->> 'error'), 'pin_locked', 'five wrong PINs lock discounts, even for the right PIN');
select is((public.owner_close_day((now() at time zone 'Asia/Kolkata')::date, 5000, 5000, null) ->> 'expected_cash')::bigint,
  5000 + (select coalesce(sum(total), 0) from public.sessions where restaurant_id = 'aaaaaaaa-0000-0000-0000-000000000001' and status = 'paid'), 'cash count expects float plus cash sales');
select is(jsonb_array_length(public.owner_staff()), 2, 'the owner sees the waiter and the kitchen login');
reset role;
select is(public.menu_insights('aaaaaaaa-0000-0000-0000-000000000001') ? 'bestsellers', true, 'suggestions are computed');
update public.owners set active = false where user_id = 'a0000000-0000-0000-0000-0000000000a2';
select is(public.custom_access_token_hook('{"user_id":"a0000000-0000-0000-0000-0000000000a2","claims":{}}'::jsonb) #>> '{claims,hotel_status}', 'disabled', 'a disabled staff login is blocked');

select * from finish();
rollback;
