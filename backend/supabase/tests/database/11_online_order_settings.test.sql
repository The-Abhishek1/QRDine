begin;
select plan(6);

insert into public.restaurants (id, name, approval)
values ('77777777-0000-0000-0000-000000000001', 'Online settings test', 'approved');

select is((select online_orders_enabled from public.restaurants where id = '77777777-0000-0000-0000-000000000001'),
  false, 'online orders default to disabled');
update public.restaurants set
  online_delivery_enabled = true,
  online_pickup_enabled = true,
  online_cod_enabled = true,
  online_cod_fee = 2500
where id = '77777777-0000-0000-0000-000000000001';
update public.restaurants set online_orders_enabled = true
where id = '77777777-0000-0000-0000-000000000001';
select is((select online_orders_enabled and online_delivery_enabled and online_pickup_enabled and online_cod_enabled
  and online_cod_fee = 2500 from public.restaurants where id = '77777777-0000-0000-0000-000000000001'),
  true, 'valid fulfilment and payment settings can enable online orders');

select throws_ok(
  $$update public.restaurants set online_delivery_enabled = false, online_pickup_enabled = false where id = '77777777-0000-0000-0000-000000000001'$$,
  '23514', 'new row for relation "restaurants" violates check constraint "online_orders_need_fulfilment"',
  'enabled online orders require a fulfilment method');
select throws_ok(
  $$update public.restaurants set online_cod_enabled = false where id = '77777777-0000-0000-0000-000000000001'$$,
  '23514', 'new row for relation "restaurants" violates check constraint "online_orders_need_payment"',
  'enabled online orders require a payment method');
select throws_ok(
  $$update public.restaurants set online_upi_enabled = true where id = '77777777-0000-0000-0000-000000000001'$$,
  '23514', 'new row for relation "restaurants" violates check constraint "online_upi_needs_vpa"',
  'UPI cannot be enabled without a restaurant UPI ID');
select throws_ok(
  $$update public.restaurants set online_delivery_fee = 100001 where id = '77777777-0000-0000-0000-000000000001'$$,
  '23514', 'new row for relation "restaurants" violates check constraint "restaurants_online_delivery_fee_check"',
  'delivery fees are bounded to ₹1,000');

select * from finish();
rollback;
