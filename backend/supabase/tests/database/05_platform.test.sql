begin;
select plan(21);

insert into auth.users (id, email) values
  ('99999999-0000-0000-0000-0000000000f1', 'platform@test.dev'),
  ('a0000000-0000-0000-0000-0000000000a1', 'a@test.dev'),
  ('b0000000-0000-0000-0000-0000000000b1', 'b@test.dev'),
  ('c0000000-0000-0000-0000-0000000000c1', 'c@test.dev');
insert into public.platform_admins (user_id) values ('99999999-0000-0000-0000-0000000000f1');
insert into public.restaurants (id, name, approval) values
  ('aaaaaaaa-0000-0000-0000-000000000001', 'Hotel A', 'approved'),
  ('bbbbbbbb-0000-0000-0000-000000000001', 'Hotel B', 'pending');
update public.restaurants set approved_at = now() where id = 'aaaaaaaa-0000-0000-0000-000000000001';
insert into public.owners (user_id, restaurant_id) values
  ('a0000000-0000-0000-0000-0000000000a1', 'aaaaaaaa-0000-0000-0000-000000000001'),
  ('b0000000-0000-0000-0000-0000000000b1', 'bbbbbbbb-0000-0000-0000-000000000001');
insert into public.categories (id, restaurant_id, name) values
  ('c0000000-0000-0000-0000-0000000000a1', 'aaaaaaaa-0000-0000-0000-000000000001', 'Mains'),
  ('c0000000-0000-0000-0000-0000000000b1', 'bbbbbbbb-0000-0000-0000-000000000001', 'Mains');

-- 1-3 the token hook
select is(public.custom_access_token_hook('{"user_id":"99999999-0000-0000-0000-0000000000f1","claims":{}}'::jsonb) #>> '{claims,app_role}', 'platform_admin', 'platform admin gets its own role');
select is(public.custom_access_token_hook('{"user_id":"a0000000-0000-0000-0000-0000000000a1","claims":{}}'::jsonb) #>> '{claims,app_role}', 'owner', 'approved hotel owner gets the owner role');
select is(public.custom_access_token_hook('{"user_id":"b0000000-0000-0000-0000-0000000000b1","claims":{}}'::jsonb) #>> '{claims,hotel_status}', 'pending', 'pending hotel owner is marked pending');

-- 4-5 a pending hotel can neither take orders nor use the owner policies, even with a stale owner token
select is(public.restaurant_can_take_orders('bbbbbbbb-0000-0000-0000-000000000001'), false, 'pending hotel cannot take orders');
select set_config('request.jwt.claims', '{"sub":"b0000000-0000-0000-0000-0000000000b1","role":"authenticated","app_role":"owner","restaurant_id":"bbbbbbbb-0000-0000-0000-000000000001"}', true);
set local role authenticated;
select is((select count(*) from public.categories)::int, 0, 'a pending hotel sees nothing, even with an owner-looking token');
select throws_ok($$update public.restaurants set approval = 'approved' where id = 'bbbbbbbb-0000-0000-0000-000000000001'$$, '42501', null, 'a hotel cannot approve itself');
select is(public.my_hotel_status() ->> 'state', 'pending', 'a blocked owner can still read its status');
reset role;

-- 8-9 only the platform admin may call platform functions
select set_config('request.jwt.claims', '{"sub":"a0000000-0000-0000-0000-0000000000a1","role":"authenticated","app_role":"owner","restaurant_id":"aaaaaaaa-0000-0000-0000-000000000001"}', true);
set local role authenticated;
select throws_ok($$select public.platform_hotels()$$, 'P0001', 'not_platform_admin', 'an owner cannot list hotels');
select throws_ok($$select * from public.platform_admins$$, '42501', null, 'clients cannot read the admin list');
reset role;

-- platform admin actions
select set_config('request.jwt.claims', '{"sub":"99999999-0000-0000-0000-0000000000f1","role":"authenticated","app_role":"platform_admin"}', true);
set local role authenticated;
select is(jsonb_array_length(public.platform_hotels()), 2, 'platform admin lists both hotels');
select is((public.platform_hotels() -> 0 ->> 'name'), 'Hotel B', 'pending hotels are listed first');
select lives_ok($$select public.platform_review('bbbbbbbb-0000-0000-0000-000000000001', true, null, 30)$$, 'platform admin approves a hotel');
select is((select e ->> 'state' from jsonb_array_elements(public.platform_hotels()) e where e ->> 'id' = 'bbbbbbbb-0000-0000-0000-000000000001'), 'trial', 'an approved hotel starts its trial');
select throws_ok($$select public.platform_review('aaaaaaaa-0000-0000-0000-000000000001', false, '  ')$$, 'P0001', 'reason_required', 'rejecting needs a reason');
select lives_ok($$select public.platform_set_hold('aaaaaaaa-0000-0000-0000-000000000001', true, 'unpaid')$$, 'platform admin puts a hotel on hold');
reset role;
select is(public.restaurant_can_take_orders('aaaaaaaa-0000-0000-0000-000000000001'), false, 'a held hotel cannot take orders');
set local role authenticated;
select lives_ok($$select public.platform_set_hold('aaaaaaaa-0000-0000-0000-000000000001', false)$$, 'and releases it');

-- payments
select throws_ok($$select public.platform_record_payment('aaaaaaaa-0000-0000-0000-000000000001', 100000, 'cheque', null, 1, null, null)$$, 'P0001', 'bad_method', 'only cash, upi or bank');
select lives_ok($$select public.platform_record_payment('aaaaaaaa-0000-0000-0000-000000000001', 100000, 'bank', 'UTR123', 3, null, null)$$, 'a bank payment is recorded');
select is((public.platform_summary() ->> 'collected_total')::int, 100000, 'the summary adds up the ledger');
reset role;

-- the ledger outlives the hotel
delete from public.restaurants where id = 'aaaaaaaa-0000-0000-0000-000000000001';
select is((select count(*)::int from public.subscription_payments where restaurant_id is null and hotel_name = 'Hotel A'), 1, 'payment records survive deleting a hotel');

select * from finish();
rollback;
