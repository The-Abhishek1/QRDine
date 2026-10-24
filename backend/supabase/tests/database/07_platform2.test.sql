begin;
select plan(12);

insert into auth.users (id, email) values
  ('99999999-0000-0000-0000-0000000000f1', 'platform@test.dev'), ('a0000000-0000-0000-0000-0000000000a1', 'a@test.dev'), ('b0000000-0000-0000-0000-0000000000b1', 'b@test.dev');
insert into public.platform_admins (user_id) values ('99999999-0000-0000-0000-0000000000f1');
insert into public.restaurants (id, name, approval, approved_at) values
  ('aaaaaaaa-0000-0000-0000-000000000001', 'Hotel A', 'approved', now() - interval '10 days'),
  ('bbbbbbbb-0000-0000-0000-000000000001', 'Hotel B', 'approved', now() - interval '10 days');
insert into public.owners (user_id, restaurant_id, role) values
  ('a0000000-0000-0000-0000-0000000000a1', 'aaaaaaaa-0000-0000-0000-000000000001', 'owner'),
  ('b0000000-0000-0000-0000-0000000000b1', 'bbbbbbbb-0000-0000-0000-000000000001', 'owner');
insert into public.categories (id, restaurant_id, name) values ('c0000000-0000-0000-0000-0000000000a1', 'aaaaaaaa-0000-0000-0000-000000000001', 'Mains');
insert into public.dishes (id, restaurant_id, category_id, name, base_price) values
  ('d0000000-0000-0000-0000-0000000000a1', 'aaaaaaaa-0000-0000-0000-000000000001', 'c0000000-0000-0000-0000-0000000000a1', 'Paneer', 20000);

select set_config('request.jwt.claims', '{"sub":"99999999-0000-0000-0000-0000000000f1","role":"authenticated","app_role":"platform_admin"}', true);
set local role authenticated;
select lives_ok($$select public.platform_update_hotel('aaaaaaaa-0000-0000-0000-000000000001', 'Hotel A', '999', 'Street', null, 'slow payer')$$, 'admin saves a private note');
select is((select e ->> 'admin_note' from jsonb_array_elements(public.platform_hotels()) e where e ->> 'id' = 'aaaaaaaa-0000-0000-0000-000000000001'), 'slow payer', 'and reads it back');
select lives_ok($$select public.platform_record_payment('aaaaaaaa-0000-0000-0000-000000000001', 50000, 'upi', 'TXN1', 1, null, null)$$, 'admin records a payment');
select is((public.platform_payments() -> 0 ->> 'receipt_no') is not null and (public.platform_payments() -> 0 ->> 'valid_until') is not null, true, 'the payment has a receipt number and a period');
select is((public.platform_insights() -> 'no_menu' -> 0 ->> 'name'), 'Hotel B', 'insights lists hotels with no menu');
select is((public.platform_view_hotel('aaaaaaaa-0000-0000-0000-000000000001') -> 'hotel' ->> 'name'), 'Hotel A', 'admin can look at a hotel read-only');
select lives_ok($$select public.platform_set_broadcast('Maintenance tonight at 2 am', 3)$$, 'admin posts a broadcast');
select lives_ok($$select public.platform_set_support('999', 'help@x.dev', 'UPI x@bank', 'QR Dine', 'Bangalore', '29ABCDE1234F1Z5')$$, 'admin saves business details');
reset role;

select is((select count(*)::int from public.platform_audit where action = 'view_as'), 1, 'every look is logged');

select set_config('request.jwt.claims', '{"sub":"a0000000-0000-0000-0000-0000000000a1","role":"authenticated","app_role":"owner","restaurant_id":"aaaaaaaa-0000-0000-0000-000000000001"}', true);
set local role authenticated;
select is(public.my_hotel_status() -> 'broadcast' ->> 'message', 'Maintenance tonight at 2 am', 'hotels see the broadcast');
select throws_ok($$select * from public.hotel_notes$$, '42501', null, 'a hotel cannot read your private notes');
select throws_ok($$select public.platform_view_hotel('bbbbbbbb-0000-0000-0000-000000000001')$$, 'P0001', 'not_platform_admin', 'a hotel cannot look at another hotel');
reset role;

select * from finish();
rollback;
