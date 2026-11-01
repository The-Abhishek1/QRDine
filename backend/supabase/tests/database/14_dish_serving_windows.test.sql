begin;
select plan(7);

select ok(public.dish_serving_window_open(null, null), 'a dish without a schedule is available all day');
select ok(public.dish_serving_window_open('09:00', '17:00', '2026-11-01 06:30:00+00'), 'a regular serving window uses India local time');
select ok(not public.dish_serving_window_open('09:00', '17:00', '2026-11-01 12:00:00+00'), 'a dish is unavailable outside its serving window');
select ok(public.dish_serving_window_open('22:00', '02:00', '2026-11-01 17:30:00+00'), 'overnight serving windows work before midnight');
select ok(public.dish_serving_window_open('22:00', '02:00', '2026-11-01 19:30:00+00'), 'overnight serving windows work after midnight');

insert into auth.users (id, email)
values ('a0000000-0000-0000-0000-0000000000d1', 'dish-hours@test.dev');
insert into public.restaurants (id, name, approval)
values ('aaaaaaaa-0000-0000-0000-0000000000d1', 'Dish-hours test', 'approved');
insert into public.categories (id, restaurant_id, name)
values ('c0000000-0000-0000-0000-0000000000d1', 'aaaaaaaa-0000-0000-0000-0000000000d1', 'Timed items');
insert into public.dishes (id, restaurant_id, category_id, name, base_price, avail_from, avail_to)
values (
  'd0000000-0000-0000-0000-0000000000d1',
  'aaaaaaaa-0000-0000-0000-0000000000d1',
  'c0000000-0000-0000-0000-0000000000d1',
  'Timed dish',
  100,
  ((now() at time zone 'Asia/Kolkata')::time + interval '2 hours')::time,
  ((now() at time zone 'Asia/Kolkata')::time + interval '3 hours')::time
);
insert into public.dining_tables (id, restaurant_id, label)
values ('e0000000-0000-0000-0000-0000000000d1', 'aaaaaaaa-0000-0000-0000-0000000000d1', 'T1');
insert into public.sessions (id, restaurant_id, table_id)
values ('51000000-0000-0000-0000-0000000000d1', 'aaaaaaaa-0000-0000-0000-0000000000d1', 'e0000000-0000-0000-0000-0000000000d1');
insert into public.orders (id, restaurant_id, session_id, client_key)
values ('52000000-0000-0000-0000-0000000000d1', 'aaaaaaaa-0000-0000-0000-0000000000d1', '51000000-0000-0000-0000-0000000000d1', 'dish-hours-test');

select throws_ok(
  $$insert into public.order_items (restaurant_id, session_id, order_id, dish_id, name_snapshot, qty, unit_price)
    values ('aaaaaaaa-0000-0000-0000-0000000000d1', '51000000-0000-0000-0000-0000000000d1',
      '52000000-0000-0000-0000-0000000000d1', 'd0000000-0000-0000-0000-0000000000d1', 'Timed dish', 1, 100)$$,
  'P0001', 'dish_unavailable:Timed dish (not served right now)', 'table orders enforce dish serving hours');
select throws_ok(
  $$insert into public.online_orders (
      restaurant_id, customer_id, client_key, fulfilment, payment_method, customer_name, customer_phone,
      items_snapshot, subtotal, total
    ) values (
      'aaaaaaaa-0000-0000-0000-0000000000d1', 'a0000000-0000-0000-0000-0000000000d1', 'dish-hours-test',
      'pickup', 'upi', 'Test', '9876543210',
      '[{"dish_id":"d0000000-0000-0000-0000-0000000000d1"}]'::jsonb, 100, 100
    )$$,
  'P0001', 'dish_unavailable:Timed dish (not served right now)', 'online orders enforce dish serving hours');

select * from finish();
rollback;
