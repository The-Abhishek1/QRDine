begin;
select plan(15);

insert into auth.users (id, email) values
  ('99999999-0000-0000-0000-0000000000e1', 'online-platform@test.dev'),
  ('a0000000-0000-0000-0000-0000000000e2', 'online-owner@test.dev');
insert into public.platform_admins (user_id)
values ('99999999-0000-0000-0000-0000000000e1');
insert into public.restaurants (id, name, approval, phone, address, online_delivery_enabled)
values (
  'aaaaaaaa-0000-0000-0000-0000000000e2',
  'Online order test',
  'approved',
  '9876543210',
  '1 Test Street',
  true
);
insert into public.owners (user_id, restaurant_id)
values ('a0000000-0000-0000-0000-0000000000e2', 'aaaaaaaa-0000-0000-0000-0000000000e2');

select is((select online_application_status from public.restaurants
  where id = 'aaaaaaaa-0000-0000-0000-0000000000e2'),
  'not_applied', 'online ordering requires a separate application');
select is(public.online_cod_available(), false, 'COD starts disabled platform-wide');

select set_config('request.jwt.claims',
  '{"sub":"a0000000-0000-0000-0000-0000000000e2","role":"authenticated","app_role":"owner","restaurant_id":"aaaaaaaa-0000-0000-0000-0000000000e2"}',
  true);
set local role authenticated;
select lives_ok($$select public.submit_online_application('Delivery in nearby areas')$$,
  'restaurant owner can submit an online-order application');
select is((select online_application_status from public.restaurants
  where id = 'aaaaaaaa-0000-0000-0000-0000000000e2'),
  'pending', 'application is queued for platform review');
select throws_ok(
  $$select public.save_online_order_settings(true, true, false, true, false, 100, 0)$$,
  'P0001', 'online_application_required', 'restaurant cannot enable online orders before approval');
select throws_ok(
  $$select public.platform_review_online_application('aaaaaaaa-0000-0000-0000-0000000000e2', true, null)$$,
  'P0001', 'not_platform_admin', 'restaurant owner cannot approve its own application');
reset role;

select set_config('request.jwt.claims',
  '{"sub":"99999999-0000-0000-0000-0000000000e1","role":"authenticated","app_role":"platform_admin"}',
  true);
set local role authenticated;
select lives_ok(
  $$select public.platform_review_online_application('aaaaaaaa-0000-0000-0000-0000000000e2', true, null)$$,
  'platform admin can approve the online-order application');
reset role;
select is((select online_application_status from public.restaurants
  where id = 'aaaaaaaa-0000-0000-0000-0000000000e2'),
  'approved', 'approved application is persisted');

select set_config('request.jwt.claims',
  '{"sub":"a0000000-0000-0000-0000-0000000000e2","role":"authenticated","app_role":"owner","restaurant_id":"aaaaaaaa-0000-0000-0000-0000000000e2"}',
  true);
set local role authenticated;
select lives_ok(
  $$select public.save_online_order_settings(true, true, false, true, false, 100, 0)$$,
  'approved restaurant can enable UPI online orders');
select throws_ok(
  $$select public.save_online_order_settings(true, true, false, true, true, 100, 0)$$,
  'P0001', 'cod_not_available', 'restaurant cannot enable COD before platform rollout');
reset role;

select set_config('request.jwt.claims',
  '{"sub":"99999999-0000-0000-0000-0000000000e1","role":"authenticated","app_role":"platform_admin"}',
  true);
set local role authenticated;
select lives_ok($$select public.platform_set_online_cod(true)$$, 'platform admin enables COD rollout');
reset role;
select is(public.online_cod_available(), true, 'COD is globally enabled after platform rollout');

select set_config('request.jwt.claims',
  '{"sub":"a0000000-0000-0000-0000-0000000000e2","role":"authenticated","app_role":"owner","restaurant_id":"aaaaaaaa-0000-0000-0000-0000000000e2"}',
  true);
set local role authenticated;
select lives_ok(
  $$select public.save_online_order_settings(true, true, false, true, true, 100, 250)$$,
  'approved restaurant can opt into COD after platform rollout');
reset role;
select lives_ok($$select public.platform_set_online_cod(false)$$, 'platform admin can disable COD rollout');
select is((select online_orders_enabled and online_upi_enabled and not online_cod_enabled
  from public.restaurants where id = 'aaaaaaaa-0000-0000-0000-0000000000e2'),
  true, 'disabling COD preserves UPI orders and disables restaurant COD');

select * from finish();
rollback;
