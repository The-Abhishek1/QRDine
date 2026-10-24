-- QR Dine M0 / 002: claims helpers, owner-claim hook, grants, RLS.
-- Two kinds of JWT hit this database, both role=authenticated:
--   owner: app_role='owner', restaurant_id  (added by the access-token hook from public.owners)
--   guest: app_role='guest', restaurant_id + session_id (minted by the session-join Edge Function)
-- Every policy checks app_role, so a guest token can never satisfy an owner policy.

create or replace function public.jwt_restaurant_id() returns uuid
language sql stable as $$ select nullif(auth.jwt() ->> 'restaurant_id', '')::uuid $$;

create or replace function public.jwt_session_id() returns uuid
language sql stable as $$ select nullif(auth.jwt() ->> 'session_id', '')::uuid $$;

create or replace function public.is_owner_of(rid uuid) returns boolean
language sql stable as $$
  select coalesce(auth.jwt() ->> 'app_role', '') = 'owner' and rid is not null and rid = public.jwt_restaurant_id()
$$;

create or replace function public.is_guest_of(sid uuid) returns boolean
language sql stable as $$
  select coalesce(auth.jwt() ->> 'app_role', '') = 'guest' and sid is not null and sid = public.jwt_session_id()
$$;

-- Supabase Auth calls this when issuing a token (enable under Authentication > Hooks).
create or replace function public.custom_access_token_hook(event jsonb) returns jsonb
language plpgsql stable as $$
declare
  claims jsonb := event -> 'claims';
  rid uuid;
begin
  select restaurant_id into rid from public.owners where user_id = (event ->> 'user_id')::uuid;
  if rid is not null then
    claims := jsonb_set(claims, '{restaurant_id}', to_jsonb(rid::text));
    claims := jsonb_set(claims, '{app_role}', '"owner"');
  end if;
  return jsonb_set(event, '{claims}', claims);
end $$;

-- Nothing is callable or readable by client roles unless granted below.
alter default privileges for role postgres in schema public revoke all on tables from anon, authenticated;
alter default privileges for role postgres in schema public revoke all on sequences from anon, authenticated;
alter default privileges for role postgres in schema public revoke all on functions from public, anon, authenticated;
revoke all on all tables    in schema public from anon, authenticated;
revoke all on all sequences in schema public from anon, authenticated;
revoke all on all functions in schema public from public, anon, authenticated;

grant execute on function public.jwt_restaurant_id(), public.jwt_session_id(),
                          public.is_owner_of(uuid), public.is_guest_of(uuid) to authenticated;

grant usage on schema public to supabase_auth_admin;
grant execute on function public.custom_access_token_hook(jsonb) to supabase_auth_admin;
grant select on table public.owners to supabase_auth_admin;

-- Row level security on everything
alter table public.restaurants       enable row level security;
alter table public.owners            enable row level security;
alter table public.devices           enable row level security;
alter table public.dining_tables     enable row level security;
alter table public.coupons           enable row level security;
alter table public.sessions          enable row level security;
alter table public.session_secrets   enable row level security;
alter table public.categories        enable row level security;
alter table public.dishes            enable row level security;
alter table public.dish_variants     enable row level security;
alter table public.addon_groups      enable row level security;
alter table public.addons            enable row level security;
alter table public.dish_addon_groups enable row level security;
alter table public.orders            enable row level security;
alter table public.order_items       enable row level security;
alter table public.service_requests  enable row level security;
alter table public.announcements     enable row level security;
alter table public.invoices          enable row level security;
alter table public.reviews           enable row level security;
alter table public.audit_logs        enable row level security;

create policy auth_admin_reads_owners on public.owners for select to supabase_auth_admin using (true);

-- restaurants: owner reads own row; may edit only profile columns (never subscription/QR fields)
create policy owner_select on public.restaurants for select to authenticated using (public.is_owner_of(id));
create policy owner_update on public.restaurants for update to authenticated
  using (public.is_owner_of(id)) with check (public.is_owner_of(id));
grant select on public.restaurants to authenticated;
grant update (name, address, gstin, gst_mode, gst_rate, service_charge_pct, upi_id, logo_url, mode, is_open)
  on public.restaurants to authenticated;

-- owner full access (menu, coupons, announcements, devices, tables)
do $$
declare t text;
begin
  foreach t in array array['categories','dishes','dish_variants','addon_groups','addons',
                           'dish_addon_groups','coupons','announcements','devices','dining_tables'] loop
    execute format('create policy owner_all on public.%I for all to authenticated
                    using (public.is_owner_of(restaurant_id)) with check (public.is_owner_of(restaurant_id))', t);
    execute format('grant select, insert, delete on public.%I to authenticated', t);
  end loop;
end $$;
grant update on public.categories, public.dishes, public.dish_variants, public.addon_groups,
                public.addons, public.coupons, public.announcements, public.devices to authenticated;
grant update (label, qr_key_version) on public.dining_tables to authenticated;  -- status changes go through functions

-- owner read-only (state changes happen through SECURITY DEFINER functions / Edge Functions)
create policy owner_select on public.sessions   for select to authenticated using (public.is_owner_of(restaurant_id));
create policy owner_select on public.orders     for select to authenticated using (public.is_owner_of(restaurant_id));
create policy owner_select on public.order_items for select to authenticated using (public.is_owner_of(restaurant_id));
create policy owner_select on public.invoices   for select to authenticated using (public.is_owner_of(restaurant_id));
create policy owner_select on public.audit_logs for select to authenticated using (public.is_owner_of(restaurant_id));
grant select on public.sessions, public.orders, public.order_items, public.invoices, public.audit_logs to authenticated;

-- owner can resolve service requests and reply to reviews, nothing else
create policy owner_select on public.service_requests for select to authenticated using (public.is_owner_of(restaurant_id));
create policy owner_update on public.service_requests for update to authenticated
  using (public.is_owner_of(restaurant_id)) with check (public.is_owner_of(restaurant_id));
grant select on public.service_requests to authenticated;
grant update (status, resolved_at) on public.service_requests to authenticated;

create policy owner_select on public.reviews for select to authenticated using (public.is_owner_of(restaurant_id));
create policy owner_update on public.reviews for update to authenticated
  using (public.is_owner_of(restaurant_id)) with check (public.is_owner_of(restaurant_id));
grant select on public.reviews to authenticated;
grant update (reply) on public.reviews to authenticated;

-- guest: read-only, own session only. Menu and all writes go through Edge Functions.
create policy guest_select on public.sessions         for select to authenticated using (public.is_guest_of(id));
create policy guest_select on public.orders           for select to authenticated using (public.is_guest_of(session_id));
create policy guest_select on public.order_items      for select to authenticated using (public.is_guest_of(session_id));
create policy guest_select on public.service_requests for select to authenticated using (public.is_guest_of(session_id));
-- session_secrets, owners: no client grants and no policies at all.
