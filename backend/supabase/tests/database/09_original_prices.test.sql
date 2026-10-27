begin;
select plan(6);

insert into public.restaurants (id, name, approval)
values ('99000000-0000-0000-0000-000000000001', 'Original price test', 'approved');
insert into public.categories (id, restaurant_id, name)
values ('99000000-0000-0000-0000-000000000002', '99000000-0000-0000-0000-000000000001', 'Mains');
insert into public.dishes (id, restaurant_id, category_id, name, base_price)
values ('99000000-0000-0000-0000-000000000003', '99000000-0000-0000-0000-000000000001',
        '99000000-0000-0000-0000-000000000002', 'Dosa', 10000);
insert into public.dish_variants (id, restaurant_id, dish_id, name, price)
values ('99000000-0000-0000-0000-000000000004', '99000000-0000-0000-0000-000000000001',
        '99000000-0000-0000-0000-000000000003', 'Half', 6000);

select is((select original_price from public.dishes where id = '99000000-0000-0000-0000-000000000003'),
          null::int, 'historical dish rows keep a null original price');
select is((select original_price from public.dish_variants where id = '99000000-0000-0000-0000-000000000004'),
          null::int, 'historical variant rows keep a null original price');
update public.dishes set original_price = 12000 where id = '99000000-0000-0000-0000-000000000003';
update public.dish_variants set original_price = 8000 where id = '99000000-0000-0000-0000-000000000004';
select is((select original_price from public.dishes where id = '99000000-0000-0000-0000-000000000003'),
          12000, 'dish original price can exceed its selling price');
select is((select original_price from public.dish_variants where id = '99000000-0000-0000-0000-000000000004'),
          8000, 'variant original price can exceed its selling price');
select throws_ok(
  $$update public.dishes set original_price = base_price where id = '99000000-0000-0000-0000-000000000003'$$,
  '23514', 'new row for relation "dishes" violates check constraint "dishes_original_price_gt_base"',
  'dish original price cannot equal its selling price');
select throws_ok(
  $$update public.dish_variants set original_price = price where id = '99000000-0000-0000-0000-000000000004'$$,
  '23514', 'new row for relation "dish_variants" violates check constraint "dish_variants_original_price_gt_price"',
  'variant original price cannot equal its selling price');

select * from finish();
rollback;
