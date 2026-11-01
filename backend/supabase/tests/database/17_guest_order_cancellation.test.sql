begin;
select plan(5);

insert into public.restaurants (id, name, approval)
values ('aaaaaaaa-0000-0000-0000-0000000000f2', 'Guest cancellation test', 'approved');
insert into public.dining_tables (id, restaurant_id, label)
values ('e0000000-0000-0000-0000-0000000000f2', 'aaaaaaaa-0000-0000-0000-0000000000f2', 'Cancel');
insert into public.sessions (id, restaurant_id, table_id)
values ('51000000-0000-0000-0000-0000000000f2', 'aaaaaaaa-0000-0000-0000-0000000000f2', 'e0000000-0000-0000-0000-0000000000f2');
insert into public.categories (id, restaurant_id, name)
values ('c0000000-0000-0000-0000-0000000000f2', 'aaaaaaaa-0000-0000-0000-0000000000f2', 'Cancellation items');
insert into public.dishes (id, restaurant_id, category_id, name, base_price, stock_qty)
values ('d0000000-0000-0000-0000-0000000000f2', 'aaaaaaaa-0000-0000-0000-0000000000f2',
  'c0000000-0000-0000-0000-0000000000f2', 'Cancellation item', 100, 3);
insert into public.orders (id, restaurant_id, session_id, status, client_key, source)
values
  ('52000000-0000-0000-0000-0000000000f2', 'aaaaaaaa-0000-0000-0000-0000000000f2', '51000000-0000-0000-0000-0000000000f2', 'pending', 'guest-cancel-test', 'guest'),
  ('52000000-0000-0000-0000-0000000000f3', 'aaaaaaaa-0000-0000-0000-0000000000f2', '51000000-0000-0000-0000-0000000000f2', 'accepted', 'accepted-cancel-test', 'guest');
insert into public.order_items (restaurant_id, session_id, order_id, dish_id, name_snapshot, qty, unit_price)
values ('aaaaaaaa-0000-0000-0000-0000000000f2', '51000000-0000-0000-0000-0000000000f2',
  '52000000-0000-0000-0000-0000000000f2', 'd0000000-0000-0000-0000-0000000000f2', 'Cancellation item', 2, 100);

select lives_ok(
  $$select public.cancel_guest_order('51000000-0000-0000-0000-0000000000f2', '52000000-0000-0000-0000-0000000000f2')$$,
  'pending guest order can be cancelled');
select is((select status::text from public.orders where id = '52000000-0000-0000-0000-0000000000f2'),
  'cancelled', 'cancellation marks the order cancelled');
select is((select bool_and(cancelled) from public.order_items where order_id = '52000000-0000-0000-0000-0000000000f2'),
  true, 'cancellation marks its remaining items cancelled');
select is((select stock_qty from public.dishes where id = 'd0000000-0000-0000-0000-0000000000f2'),
  5, 'cancellation restores reserved stock');
select throws_ok(
  $$select public.cancel_guest_order('51000000-0000-0000-0000-0000000000f2', '52000000-0000-0000-0000-0000000000f3')$$,
  'P0001', 'order_not_pending', 'accepted orders cannot be cancelled by guests');

select * from finish();
rollback;
