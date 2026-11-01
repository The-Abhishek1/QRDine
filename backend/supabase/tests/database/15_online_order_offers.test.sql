begin;
select plan(3);

insert into auth.users (id, email)
values ('a0000000-0000-0000-0000-0000000000e1', 'online-offer@test.dev');
insert into public.restaurants (id, name, approval)
values ('aaaaaaaa-0000-0000-0000-0000000000e1', 'Online offer test', 'approved');
insert into public.categories (id, restaurant_id, name)
values ('c0000000-0000-0000-0000-0000000000e1', 'aaaaaaaa-0000-0000-0000-0000000000e1', 'Offer items');
insert into public.dishes (id, restaurant_id, category_id, name, base_price)
values
  ('d0000000-0000-0000-0000-0000000000e1', 'aaaaaaaa-0000-0000-0000-0000000000e1', 'c0000000-0000-0000-0000-0000000000e1', 'Buy item', 100),
  ('d0000000-0000-0000-0000-0000000000e2', 'aaaaaaaa-0000-0000-0000-0000000000e1', 'c0000000-0000-0000-0000-0000000000e1', 'Reward item', 50);
insert into public.offers (id, restaurant_id, name, buy_qty, get_qty, buy_dish_id, reward_dish_id)
values ('f0000000-0000-0000-0000-0000000000e1', 'aaaaaaaa-0000-0000-0000-0000000000e1', 'Buy two get one', 2, 1,
  'd0000000-0000-0000-0000-0000000000e1', 'd0000000-0000-0000-0000-0000000000e2');

insert into public.online_orders (
  restaurant_id, customer_id, client_key, fulfilment, payment_method, customer_name, customer_phone,
  items_snapshot, subtotal, total
) values (
  'aaaaaaaa-0000-0000-0000-0000000000e1',
  'a0000000-0000-0000-0000-0000000000e1',
  'online-offer-test',
  'pickup',
  'cod',
  'Test customer',
  '9876543210',
  '[{"dish_id":"d0000000-0000-0000-0000-0000000000e1","name":"Buy item","qty":2,"unit_price":100},{"dish_id":"d0000000-0000-0000-0000-0000000000e2","name":"Reward item","qty":1,"unit_price":50}]'::jsonb,
  250,
  250
);

select is((select offer_discount from public.online_orders where client_key = 'online-offer-test'), 50,
  'online Buy N Get M discount is calculated server-side');
select is((select total from public.online_orders where client_key = 'online-offer-test'), 200,
  'online order total uses the server-calculated offer discount');
select is((select items_snapshot -> 1 ->> 'offer_free_qty' from public.online_orders where client_key = 'online-offer-test'), '1',
  'online order snapshot records the free reward quantity');

select * from finish();
rollback;
