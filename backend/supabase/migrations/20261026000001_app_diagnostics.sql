-- Keep a short, privacy-minimized record of Android crashes and handled errors.
create table public.app_diagnostic_reports (
  id uuid primary key default gen_random_uuid(),
  event_id uuid not null unique,
  restaurant_id uuid references public.restaurants(id) on delete set null,
  kind text not null check (kind in ('crash', 'error')),
  error_type text not null check (char_length(error_type) between 1 and 120),
  area text not null check (char_length(area) between 1 and 40),
  app_version text not null check (char_length(app_version) between 1 and 30),
  os_version text not null check (char_length(os_version) between 1 and 30),
  device_model text not null check (char_length(device_model) between 1 and 80),
  frames jsonb not null default '[]'::jsonb check (jsonb_typeof(frames) = 'array'),
  occurred_at timestamptz not null,
  received_at timestamptz not null default now()
);

create index app_diagnostic_reports_received_at_idx
  on public.app_diagnostic_reports (received_at desc);

alter table public.app_diagnostic_reports enable row level security;
revoke all on public.app_diagnostic_reports from anon, authenticated;
grant all on public.app_diagnostic_reports to service_role;
