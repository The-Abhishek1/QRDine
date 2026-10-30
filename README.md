# QR Dine

QR Dine is a restaurant ordering platform with a Kotlin/Jetpack Compose Android app, a Next.js customer PWA, and a Supabase backend. It supports both table-based QR orders and authenticated online delivery or pickup orders.

## Project components

| Component | Location | Purpose |
|---|---|---|
| Supabase backend | [`backend/`](./backend/README.md) | PostgreSQL schema, migrations, Row Level Security, Edge Functions, and database tests |
| Android app | [`admin-android/`](./admin-android/README.md) | Restaurant owner and staff operations, platform administration, and customer ordering |
| Customer PWA | [`customer-pwa/`](./customer-pwa/README.md) | QR/table ordering and online restaurant discovery and ordering |

## Product areas

- **Table ordering:** Guests scan a restaurant table QR code, browse the menu, place orders, request service or a bill, and leave a review.
- **Online ordering:** Customers create a customer account, complete a delivery profile, and order from approved restaurants for delivery or pickup.
- **Restaurant operations:** Owners and staff manage orders, menus, sizes, optional extras, offers, availability, tables, sales insights, and settings.
- **Platform administration:** Platform admins review online-order applications, manage restaurant accounts and subscriptions, and record payments.

Online ordering is distinct from QR/table ordering. A restaurant must be approved for online ordering and enable its online service before it appears in the online directory.

## Architecture

```text
Android app ───────────────┐
                           ├── Supabase Auth, PostgreSQL/RLS, Storage, Realtime, Edge Functions
Customer PWA ──────────────┘
```

The database is authoritative for authorization, order pricing, stock, and offer discounts. The Android app and PWA use the public Supabase anon/publishable key with scoped user sessions. Never ship a service-role key, QR signing secret, or other server secret in either client.

## Development setup

1. Configure Supabase and apply the database migrations. See the [backend setup and deployment guide](./backend/README.md).
2. Configure and run the [Android app](./admin-android/README.md) or [customer PWA](./customer-pwa/README.md).
3. Configure the Supabase Auth custom access-token hook for customer accounts. This is required for customer order permissions; adding the anon key to a client alone is not sufficient.
4. Deploy the Edge Functions required by the features you enable.

### Typical development sequence

```text
Start local Supabase → apply/reset migrations → run database tests
→ configure an app client → deploy/serve required Edge Functions
→ test table ordering and online ordering separately
```

## Important configuration

- Allow `qrdine://auth/callback` in Supabase Auth redirect URLs for Android sign-up confirmation and password recovery.
- Enable the `public.custom_access_token_hook` Auth hook. Customer accounts require `app_role=customer` in a fresh access token.
- Online orders require the online-ordering migrations and `online-restaurants` and `online-menu` functions.
- Table QR ordering uses signed guest tokens and the `QR_SIGNING_SECRET`; keep this secret on the backend only.
- FCM push notifications are optional. Realtime and in-app refresh behavior remain available without Firebase push configuration.

## Documentation

- [Backend: local development, database, functions, security, and deployment](./backend/README.md)
- [Android: setup, authentication, builds, and app capabilities](./admin-android/README.md)
- [Customer PWA: environment, development, deployment, and ordering flows](./customer-pwa/README.md)

## Security and operations

Use separate Supabase projects for development and production. Review migrations before applying them to a hosted database, back up production data, and deploy client and backend changes in a compatible order. Keep credentials and service secrets out of source control. Configure production email delivery and review privacy/terms content before public release.
