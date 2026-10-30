# QR Dine Backend

The backend runs on Supabase: PostgreSQL migrations and Row Level Security (RLS), Auth access-token claims, Storage, Realtime, and Edge Functions. Database policies and functions enforce tenant boundaries, permissions, order pricing, and online-order eligibility; client-side navigation is not an authorization boundary.

## Contents

- [`supabase/migrations/`](./supabase/migrations/) — ordered database changes
- [`supabase/functions/`](./supabase/functions/) — server-side API and integrations
- [`supabase/tests/database/`](./supabase/tests/database/) — pgTAP database tests
- [`supabase/config.toml`](./supabase/config.toml) — local Supabase configuration

## Prerequisites

- Docker running locally
- Supabase CLI (`npx supabase ...` or a globally installed CLI)
- Node.js/npm for `npx`
- A Supabase project for hosted deployment

## Local development

Run commands from `backend/`:

```bash
npx supabase start
npx supabase db reset
npx supabase test db
```

`db reset` recreates the local database and applies all migrations in order. It is destructive to local Supabase data; do not use it against a hosted project. The checked-in `config.toml` enables the custom access-token hook and configures the Edge Functions.

To serve functions locally, create `supabase/functions/.env` from the function environment requirements and use:

```bash
npx supabase functions serve --env-file supabase/functions/.env
```

For table-ordering development, configure the PWA origin and QR signing secret in the local function environment. See the function source and `.env.example` files for each function's required values.

## Database capabilities

The migrations build the system in chronological order and cover:

- Restaurant, menu, table, order, review, and billing data with tenant isolation and RLS.
- Table sessions, signed guest access, server-side order pricing, idempotency, and order lifecycle controls.
- GST/service-charge billing, coupons, invoice numbering, and restaurant insights.
- Platform administration, restaurant approval and holds, subscriptions, payment records, staff permissions, and audit history.
- Menu variants, stock, availability schedules, optional add-ons, translations, and Buy N Get M offers.
- Customer profiles, online-order settings, delivery/pickup orders, payment state, reviews, and service-fee reporting.
- Opt-in Android diagnostics with restricted access and a 30-day retention policy.

Add database tests whenever introducing or changing security-sensitive tables, policies, functions, or RPC behavior.

## Edge Functions

| Function | Responsibility |
|---|---|
| `session-join` | Validate a signed table QR token and create a scoped guest session |
| `session-state` | Read current table-session and menu state |
| `order-place` | Submit a table order using server-calculated prices |
| `service-request` | Create table service and bill requests |
| `qr-sheet` | Create signed QR links for restaurant staff |
| `review-submit` | Validate and submit a post-order review |
| `session-coupon` | Validate/apply table-session coupons |
| `online-restaurants` | Customer restaurant directory and online-order actions |
| `online-menu` | Read an approved restaurant's online menu |
| `platform-admin` | Restricted platform administration actions |
| `staff-admin` | Manage restaurant staff accounts |
| `notify` | Deliver configured FCM notifications |
| `app-diagnostics` | Accept opted-in diagnostics and provide platform-admin reports |

Guest-token functions are configured with `verify_jwt = false` because they validate their own signed guest token. This does not make them public unauthenticated endpoints. Keep those token checks and server-side authorization in place.

## Customer account access

Customer authentication uses Supabase Auth metadata and the custom access-token hook:

1. Enable the custom access-token hook in Supabase Auth, pointing to `public.custom_access_token_hook`.
2. Apply the customer-account and online-ordering migrations.
3. Create customer accounts with `account_type=customer` metadata.
4. Have customers sign out and back in after enabling or changing the hook so a new token contains `app_role=customer`.

The public anon key alone does not grant customer permissions. Customer RPCs check the authenticated customer role and RLS. Do not assign customer roles based only on client-supplied request fields.

## Hosted deployment

Use a staging project first. From `backend/`:

```bash
npx supabase login
npx supabase link --project-ref YOUR_PROJECT_REF
npx supabase db push
npx supabase functions deploy session-join session-state order-place service-request \
  qr-sheet review-submit session-coupon online-restaurants online-menu
```

Deploy other functions only when their integrations/features are configured:

```bash
npx supabase functions deploy platform-admin staff-admin app-diagnostics
npx supabase functions deploy notify
```

After deployment, configure the custom access-token hook in the Supabase dashboard, check function secrets, and smoke-test both QR/table and online ordering. Database migrations and function deployment are separate operations; deploy compatible client/backend versions together.

### Required and optional secrets

| Secret | Used by | Notes |
|---|---|---|
| `QR_SIGNING_SECRET` | Table QR functions | Generate a strong random secret; backend only |
| `PWA_BASE_URL` | QR generation | Public base URL used in signed QR links |
| `PWA_ORIGIN` | QR/session functions | Allowed PWA origin |
| `WEBHOOK_SECRET` | `notify` | Shared secret for configured database webhooks |
| `FCM_SERVICE_ACCOUNT_JSON` | `notify` | Firebase service-account JSON; optional push integration |

Set secrets with the Supabase CLI, for example:

```bash
npx supabase secrets set QR_SIGNING_SECRET="..." \
  PWA_BASE_URL="https://your-pwa.example" \
  PWA_ORIGIN="https://your-pwa.example"
```

Never put these secrets or a Supabase service-role key in the Android app, PWA, repository, or public build environment.

## Optional FCM notifications

1. Create a Firebase project and register Android package `com.xcloak.qrdine`.
2. Add `google-services.json` to `admin-android/app/` (do not commit production credentials).
3. Configure `FCM_SERVICE_ACCOUNT_JSON` and `WEBHOOK_SECRET` as Supabase function secrets.
4. Deploy `notify` and configure database webhooks for the relevant order/request tables with the `x-webhook-secret` header.

The Android app builds without Firebase configuration; push delivery when the app is killed is optional.

## Optional diagnostics

Apply the diagnostics migration and deploy `app-diagnostics` to enable opt-in Android reports. The app limits reports and the database applies retention. Review the disclosure and privacy policy before enabling this in production.

## Testing

```bash
npx supabase test db
```

The database suite uses pgTAP. Ensure the local Supabase stack is running and migrations are applied. Review test output for failures rather than relying on a historical test count, which changes as coverage is added.

## Production checklist

- Back up the database and review pending migrations before `db push`.
- Confirm RLS, custom access-token hook, and Auth redirect URLs in the target project.
- Configure function secrets and allowlisted origins; never deploy placeholder values.
- Deploy all functions affected by shared contracts or migrations.
- Test guest QR ordering and customer-authenticated online ordering independently.
- Verify payment flows with test data; UPI launch is not proof of payment.
- Configure production email delivery and any desired FCM webhooks.
- Confirm diagnostics disclosure, privacy/terms content, monitoring, and rollback plan.
