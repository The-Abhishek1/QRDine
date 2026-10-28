begin;
select plan(1);

set local role authenticated;
select set_config(
  'request.jwt.claims',
  '{"sub":"11111111-0000-0000-0000-000000000001","role":"authenticated","app_role":"customer"}',
  true
);
select throws_ok(
  $$select public.create_restaurant('Customer account must not create hotels')$$,
  'P0001',
  'not_authenticated',
  'customer accounts cannot create restaurant owner accounts'
);

select * from finish();
rollback;
