-- QR Dine M0 / 001: schema. Money = integer paise. Every tenant table carries restaurant_id.
-- Child rows use composite FKs (id, restaurant_id) so a row can never point at another hotel's parent.
create extension if not exists pgcrypto with schema extensions;

create type public.gst_mode       as enum ('regular','composition','none');
create type public.ordering_mode  as enum ('staff_activated','open');
create type public.sub_status     as enum ('trial','active','grace','on_hold','expired');
create type public.table_status   as enum ('closed','active','bill_requested');
create type public.session_status as enum ('active','bill_requested','paid','void');
create type public.order_type     as enum ('dine_in','parcel');
create type public.order_status   as enum ('pending','accepted','rejected','cancelled','completed');
create type public.request_type   as enum ('waiter','bill');
create type public.request_status as enum ('open','resolved');
create type public.category_kind  as enum ('food','beverage');
create type public.coupon_type    as enum ('percent','flat');
create type public.pay_method     as enum ('cash','upi','split');

create table public.restaurants (
  id uuid primary key default gen_random_uuid(),
  name text not null check (char_length(name) between 1 and 120),
  address text,
  gstin text check (gstin is null or char_length(gstin) = 15),
  gst_mode public.gst_mode not null default 'none',
  gst_rate numeric(5,2) not null default 5 check (gst_rate between 0 and 28),
  service_charge_pct numeric(5,2) not null default 0 check (service_charge_pct between 0 and 20),
  upi_id text,
  logo_url text,
  mode public.ordering_mode not null default 'staff_activated',
  is_open boolean not null default false,
  sub_status public.sub_status not null default 'trial',
  trial_ends_at timestamptz not null default now() + interval '30 days',
  sub_expires_at timestamptz,
  play_purchase_token text,
  qr_secret_version int not null default 1,
  created_at timestamptz not null default now()
);

create table public.owners (
  user_id uuid primary key references auth.users(id) on delete cascade,
  restaurant_id uuid not null references public.restaurants(id) on delete cascade,
  created_at timestamptz not null default now()
);
create index on public.owners (restaurant_id);

create table public.devices (
  id uuid primary key default gen_random_uuid(),
  restaurant_id uuid not null references public.restaurants(id) on delete cascade,
  user_id uuid not null default auth.uid() references auth.users(id) on delete cascade,
  fcm_token text not null,
  last_seen timestamptz not null default now(),
  unique (restaurant_id, fcm_token)
);

create table public.dining_tables (
  id uuid primary key default gen_random_uuid(),
  restaurant_id uuid not null references public.restaurants(id) on delete cascade,
  label text not null check (char_length(label) between 1 and 20),
  qr_key_version int not null default 1,
  status public.table_status not null default 'closed',
  active_session_id uuid,
  created_at timestamptz not null default now(),
  unique (restaurant_id, label),
  unique (id, restaurant_id)
);

create table public.coupons (
  id uuid primary key default gen_random_uuid(),
  restaurant_id uuid not null references public.restaurants(id) on delete cascade,
  code text not null check (char_length(code) between 3 and 20),
  type public.coupon_type not null,
  value int not null check (value > 0),
  min_bill int not null default 0,
  valid_from timestamptz,
  valid_to timestamptz,
  max_uses int,
  used_count int not null default 0,
  active boolean not null default true,
  unique (restaurant_id, code),
  unique (id, restaurant_id)
);

create table public.sessions (
  id uuid primary key default gen_random_uuid(),
  restaurant_id uuid not null references public.restaurants(id) on delete cascade,
  table_id uuid not null,
  status public.session_status not null default 'active',
  opened_at timestamptz not null default now(),
  opened_by uuid,
  closed_at timestamptz,
  subtotal int not null default 0,
  discount int not null default 0,
  tax int not null default 0,
  total int not null default 0,
  coupon_id uuid,
  payment_method public.pay_method,
  payment_split jsonb,
  invoice_id uuid,
  unique (id, restaurant_id),
  foreign key (table_id, restaurant_id) references public.dining_tables (id, restaurant_id),
  foreign key (coupon_id, restaurant_id) references public.coupons (id, restaurant_id)
);
-- at most one unpaid session per table
create unique index sessions_one_open_per_table on public.sessions (table_id) where status in ('active','bill_requested');
create index on public.sessions (restaurant_id, opened_at);
alter table public.dining_tables
  add constraint dining_tables_active_session_fk foreign key (active_session_id) references public.sessions (id) on delete set null;

-- PIN data is never granted to any client role (service role / SECURITY DEFINER functions only)
create table public.session_secrets (
  session_id uuid primary key references public.sessions(id) on delete cascade,
  restaurant_id uuid not null references public.restaurants(id) on delete cascade,
  pin_hash text not null,
  attempts int not null default 0,
  locked_until timestamptz
);

create table public.categories (
  id uuid primary key default gen_random_uuid(),
  restaurant_id uuid not null references public.restaurants(id) on delete cascade,
  name text not null check (char_length(name) between 1 and 60),
  kind public.category_kind not null default 'food',
  sort int not null default 0,
  unique (id, restaurant_id)
);

create table public.dishes (
  id uuid primary key default gen_random_uuid(),
  restaurant_id uuid not null references public.restaurants(id) on delete cascade,
  category_id uuid not null,
  name text not null check (char_length(name) between 1 and 120),
  description text check (char_length(description) <= 500),
  image_url text,
  base_price int not null check (base_price >= 0),
  is_veg boolean not null default true,
  is_available boolean not null default true,
  sort int not null default 0,
  deleted_at timestamptz,
  unique (id, restaurant_id),
  foreign key (category_id, restaurant_id) references public.categories (id, restaurant_id)
);
create index on public.dishes (restaurant_id, category_id) where deleted_at is null;

create table public.dish_variants (
  id uuid primary key default gen_random_uuid(),
  restaurant_id uuid not null references public.restaurants(id) on delete cascade,
  dish_id uuid not null,
  name text not null check (char_length(name) between 1 and 40),
  price int not null check (price >= 0),
  foreign key (dish_id, restaurant_id) references public.dishes (id, restaurant_id) on delete cascade
);

create table public.addon_groups (
  id uuid primary key default gen_random_uuid(),
  restaurant_id uuid not null references public.restaurants(id) on delete cascade,
  name text not null check (char_length(name) between 1 and 60),
  min_select int not null default 0,
  max_select int not null default 1 check (max_select >= min_select),
  unique (id, restaurant_id)
);

create table public.addons (
  id uuid primary key default gen_random_uuid(),
  restaurant_id uuid not null references public.restaurants(id) on delete cascade,
  group_id uuid not null,
  name text not null check (char_length(name) between 1 and 60),
  price int not null default 0 check (price >= 0),
  is_available boolean not null default true,
  foreign key (group_id, restaurant_id) references public.addon_groups (id, restaurant_id) on delete cascade
);

create table public.dish_addon_groups (
  restaurant_id uuid not null references public.restaurants(id) on delete cascade,
  dish_id uuid not null,
  group_id uuid not null,
  primary key (dish_id, group_id),
  foreign key (dish_id, restaurant_id) references public.dishes (id, restaurant_id) on delete cascade,
  foreign key (group_id, restaurant_id) references public.addon_groups (id, restaurant_id) on delete cascade
);

create table public.orders (
  id uuid primary key default gen_random_uuid(),
  restaurant_id uuid not null references public.restaurants(id) on delete cascade,
  session_id uuid not null,
  seq bigint generated always as identity,
  order_type public.order_type not null default 'dine_in',
  status public.order_status not null default 'pending',
  eta_minutes int check (eta_minutes between 1 and 240),
  accepted_at timestamptz,
  reject_reason text,
  note text check (char_length(note) <= 300),
  client_key text not null check (char_length(client_key) between 8 and 64),
  created_at timestamptz not null default now(),
  unique (session_id, client_key),
  unique (id, restaurant_id),
  foreign key (session_id, restaurant_id) references public.sessions (id, restaurant_id)
);
create index on public.orders (restaurant_id, created_at);
create index on public.orders (restaurant_id, status);
create index on public.orders (session_id);

create table public.order_items (
  id uuid primary key default gen_random_uuid(),
  restaurant_id uuid not null references public.restaurants(id) on delete cascade,
  session_id uuid not null,           -- denormalised for simple RLS + Realtime filters
  order_id uuid not null,
  dish_id uuid references public.dishes(id),
  name_snapshot text not null,
  variant_snapshot text,
  qty int not null check (qty between 1 and 50),
  unit_price int not null check (unit_price >= 0),
  addons jsonb not null default '[]',
  instructions text check (char_length(instructions) <= 200),
  cancelled boolean not null default false,
  foreign key (order_id, restaurant_id) references public.orders (id, restaurant_id) on delete cascade
);
create index on public.order_items (order_id);
create index on public.order_items (session_id);

create table public.service_requests (
  id uuid primary key default gen_random_uuid(),
  restaurant_id uuid not null references public.restaurants(id) on delete cascade,
  session_id uuid not null,
  type public.request_type not null,
  status public.request_status not null default 'open',
  created_at timestamptz not null default now(),
  resolved_at timestamptz,
  foreign key (session_id, restaurant_id) references public.sessions (id, restaurant_id)
);
create index on public.service_requests (restaurant_id, status);

create table public.announcements (
  id uuid primary key default gen_random_uuid(),
  restaurant_id uuid not null references public.restaurants(id) on delete cascade,
  text text not null check (char_length(text) <= 300),
  show_date date not null default current_date
);

create table public.invoices (
  id uuid primary key default gen_random_uuid(),
  restaurant_id uuid not null references public.restaurants(id) on delete cascade,
  session_id uuid not null unique,
  fy text not null,
  number int not null,
  snapshot jsonb not null,
  pdf_url text,
  issued_at timestamptz not null default now(),
  unique (restaurant_id, fy, number),
  foreign key (session_id, restaurant_id) references public.sessions (id, restaurant_id)
);

create table public.reviews (
  id uuid primary key default gen_random_uuid(),
  restaurant_id uuid not null references public.restaurants(id) on delete cascade,
  session_id uuid not null unique,
  rating_food int check (rating_food between 1 and 5),
  rating_service int check (rating_service between 1 and 5),
  comment text check (char_length(comment) <= 500),
  reply text check (char_length(reply) <= 500),
  created_at timestamptz not null default now(),
  foreign key (session_id, restaurant_id) references public.sessions (id, restaurant_id)
);
create index on public.reviews (restaurant_id, created_at);

create table public.audit_logs (
  id bigint generated always as identity primary key,
  restaurant_id uuid not null references public.restaurants(id) on delete cascade,
  actor uuid,
  action text not null,
  entity text not null,
  entity_id uuid,
  data jsonb,
  created_at timestamptz not null default now()
);
create index on public.audit_logs (restaurant_id, created_at);
