begin;
select plan(15);

insert into auth.users (id, email) values
  ('a0000000-0000-0000-0000-0000000000f1', 'lifecycle-customer@test.dev'),
  ('a0000000-0000-0000-0000-0000000000f2', 'lifecycle-owner@test.dev');
insert into public.restaurants (id, name, approval)
values ('aaaaaaaa-0000-0000-0000-0000000000f1', 'Lifecycle test', 'approved');
insert into public.owners (user_id, restaurant_id)
values ('a0000000-0000-0000-0000-0000000000f2', 'aaaaaaaa-0000-0000-0000-0000000000f1');
insert into public.categories (id, restaurant_id, name)
values ('c0000000-0000-0000-0000-0000000000f1', 'aaaaaaaa-0000-0000-0000-0000000000f1', 'Lifecycle items');
insert into public.dishes (id, restaurant_id, category_id, name, base_price, stock_qty)
values ('d0000000-0000-0000-0000-0000000000f1', 'aaaaaaaa-0000-0000-0000-0000000000f1',
  'c0000000-0000-0000-0000-0000000000f1', 'Lifecycle item', 100, 6);
insert into public.online_orders (
  id, restaurant_id, customer_id, client_key, status, fulfilment, payment_method, payment_status, refund_status,
  customer_name, customer_phone, items_snapshot, subtotal, total
) values
  ('b0000000-0000-0000-0000-0000000000f1', 'aaaaaaaa-0000-0000-0000-0000000000f1',
   'a0000000-0000-0000-0000-0000000000f1', 'cancel-paid-test', 'pending', 'pickup', 'upi', 'paid', 'not_required',
   'Test customer', '9876543210', '[{"dish_id":"d0000000-0000-0000-0000-0000000000f1","qty":2}]'::jsonb, 200, 200),
  ('b0000000-0000-0000-0000-0000000000f2', 'aaaaaaaa-0000-0000-0000-0000000000f1',
   'a0000000-0000-0000-0000-0000000000f1', 'cancel-no-refund-test', 'cancelled', 'pickup', 'upi', 'paid', 'required',
   'Test customer', '9876543210', '[]'::jsonb, 0, 0),
  ('b0000000-0000-0000-0000-0000000000f3', 'aaaaaaaa-0000-0000-0000-0000000000f1',
   'a0000000-0000-0000-0000-0000000000f1', 'progress-test', 'accepted', 'pickup', 'cod', 'cod_due', 'not_required',
   'Test customer', '9876543210', '[]'::jsonb, 0, 0),
  ('b0000000-0000-0000-0000-0000000000f4', 'aaaaaaaa-0000-0000-0000-0000000000f1',
   'a0000000-0000-0000-0000-0000000000f1', 'cancel-unpaid-test', 'pending', 'pickup', 'upi', 'awaiting_payment', 'not_required',
   'Test customer', '9876543210', '[]'::jsonb, 0, 0);

select set_config('request.jwt.claims',
  '{"sub":"a0000000-0000-0000-0000-0000000000f1","role":"authenticated","app_role":"customer"}', true);
set local role authenticated;
select lives_ok(
  $$select public.customer_cancel_online_order('b0000000-0000-0000-0000-0000000000f1')$$,
  'customer can cancel their pending order');
select lives_ok(
  $$select public.customer_cancel_online_order('b0000000-0000-0000-0000-0000000000f4')$$,
  'customer can cancel an unpaid UPI order');
reset role;

select is((select status from public.online_orders where id = 'b0000000-0000-0000-0000-0000000000f1'),
  'cancelled', 'customer cancellation updates the order status');
select is((select refund_status from public.online_orders where id = 'b0000000-0000-0000-0000-0000000000f1'),
  'required', 'paid UPI cancellation requires refund review');
select is((select stock_qty from public.dishes where id = 'd0000000-0000-0000-0000-0000000000f1'),
  8, 'customer cancellation restores reserved dish stock');
select is((select refund_status from public.online_orders where id = 'b0000000-0000-0000-0000-0000000000f4'),
  'not_required', 'an unpaid UPI cancellation does not require a refund');

select set_config('request.jwt.claims',
  '{"sub":"a0000000-0000-0000-0000-0000000000f2","role":"authenticated","app_role":"owner","restaurant_id":"aaaaaaaa-0000-0000-0000-0000000000f1"}', true);
set local role authenticated;
select lives_ok(
  $$select public.restaurant_online_order_action('b0000000-0000-0000-0000-0000000000f1', 'mark_refunded', 'UPI-REF-1')$$,
  'restaurant can record an externally sent refund');
select lives_ok(
  $$select public.restaurant_online_order_action('b0000000-0000-0000-0000-0000000000f2', 'mark_no_refund', 'Payment was not received')$$,
  'restaurant can resolve review when no refund is due');
select lives_ok($$select public.restaurant_online_order_action('b0000000-0000-0000-0000-0000000000f3', 'preparing')$$,
  'restaurant can move an accepted order to preparation');
select lives_ok($$select public.restaurant_online_order_action('b0000000-0000-0000-0000-0000000000f3', 'ready')$$,
  'restaurant can mark an in-progress order ready');
select lives_ok($$select public.restaurant_online_order_action('b0000000-0000-0000-0000-0000000000f3', 'complete')$$,
  'restaurant can complete a ready order');
reset role;

select is((select refund_status from public.online_orders where id = 'b0000000-0000-0000-0000-0000000000f1'),
  'refunded', 'refund action records sent status');
select is((select refund_reference from public.online_orders where id = 'b0000000-0000-0000-0000-0000000000f1'),
  'UPI-REF-1', 'refund action saves transaction reference');
select is((select refund_note from public.online_orders where id = 'b0000000-0000-0000-0000-0000000000f2'),
  'Payment was not received', 'no-refund resolution retains its reason');
select is((select status from public.online_orders where id = 'b0000000-0000-0000-0000-0000000000f3'),
  'completed', 'order progresses through preparing and ready to completed');

select * from finish();
rollback;
