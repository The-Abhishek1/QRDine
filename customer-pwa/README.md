# QR Dine Customer PWA

The QR Dine PWA is a Next.js application for customer ordering and a restaurant-owner web workspace:

1. **Table QR ordering** at `/t/<signed-token>` using a restaurant-issued QR code.
2. **Online ordering** at `/explore`, with customer authentication and delivery or pickup from approved restaurants.
3. **Restaurant workspace** at `/restaurant`, with owner login/signup, online-order management, menu availability, and online-order setup.

The restaurant workspace complements the Android app. Restaurant owner accounts can manage online orders from a desktop browser; staff workflows and the full dine-in operation remain in the Android app.

## Requirements

- Node.js and npm
- A Supabase project with the required database migrations and Edge Functions deployed
- Supabase public anon/publishable key for customer authentication

## Configure

```bash
cp .env.example .env.local
```

Set these variables in `.env.local`:

| Variable | Required | Purpose |
|---|---|---|
| `NEXT_PUBLIC_API_BASE` | Yes | Supabase Edge Functions base URL, ending in `/functions/v1` |
| `NEXT_PUBLIC_SUPABASE_URL` | For online accounts; recommended | Supabase project URL |
| `NEXT_PUBLIC_SUPABASE_ANON_KEY` | For online accounts; recommended | Public anon/publishable key |

Example:

```dotenv
NEXT_PUBLIC_API_BASE=https://YOUR_PROJECT_REF.supabase.co/functions/v1
NEXT_PUBLIC_SUPABASE_URL=https://YOUR_PROJECT_REF.supabase.co
NEXT_PUBLIC_SUPABASE_ANON_KEY=YOUR_PUBLIC_ANON_KEY
```

Only use the public anon/publishable key in this browser app. **Never** add a service-role key or QR signing secret to a `NEXT_PUBLIC_*` variable.

## Run locally

From `customer-pwa/`:

```bash
npm ci
npm run dev
```

Open `http://localhost:3000`. To test QR links from a phone, use a reachable HTTPS or LAN origin and configure the backend's `PWA_BASE_URL` and `PWA_ORIGIN` to match.

Production build check:

```bash
npm run build
npm start
```

## PWA workflows

### Table QR orders

The QR flow validates the signed token through `session-join`, loads table/menu state, and submits orders through backend functions. It supports menu categories and search, sizes, optional extras, notes, restaurant offers, cart, order status, bill requests, UPI payment handoff, and post-order reviews. Session tokens and server-side pricing prevent the browser from deciding trusted prices or restaurant identity.

### Online orders

Customers register/sign in with a customer account, complete their delivery profile, browse the approved online restaurant directory, and place delivery or pickup orders. The PWA displays order status, items, fees, payment state, and feedback controls. Online ordering requires:

- The customer-account and online-ordering migrations.
- `online-restaurants` and `online-menu` Edge Functions.
- The Supabase custom access-token hook enabled for `public.custom_access_token_hook`.
- The public Supabase URL and anon/publishable key configured in this app.

After enabling the Auth hook, customers should sign out and back in so their access token contains `app_role=customer`. Adding the key to a hosting provider alone does not enable customer permissions.

UPI payment handoff does not verify payment. The restaurant must check the transfer and confirm it using the configured order workflow. COD is subject to platform and restaurant settings.

### Restaurant workspace

Open `/restaurant` from the home page to sign in or create a restaurant owner account. New accounts submit restaurant details for platform approval; an existing owner can finish restaurant setup after confirming their email and signing in. The owner workspace shows restaurant approval and online-service status, incoming online orders, payment verification and order actions, dish online availability, and fulfilment/payment settings.

The workspace uses the public Supabase anon/publishable key with authenticated owner JWTs, existing RLS policies, and backend RPCs. It does not use a service-role key. Enable the custom Auth hook before testing: restaurant owner tokens need the correct `app_role` and `restaurant_id` claims, while customer accounts are rejected from the restaurant workspace.

## Backend deployment

Follow the [backend deployment guide](../backend/README.md). In summary, apply the required migrations, deploy the relevant Edge Functions, enable the custom Auth hook, and configure the PWA origin and QR signing secret as backend secrets. Existing table QR ordering continues to use `/t/<signed-token>` independently of `/explore`.

## Deploy

Deploy to any host that supports Next.js 15. For Vercel:

1. Set the three environment variables above in the project settings.
2. Ensure the variables are available to the target deployment environment.
3. Deploy or redeploy after changing environment values.
4. Configure the backend PWA origin/base URL to the deployed HTTPS origin.
5. Test customer signup/sign-in, restaurant owner signup/sign-in and approval status, table QR entry, menu loading, and order status on the deployed site.

The PWA uses a web manifest and service worker for installability. Do not cache authenticated customer data or active table-session data in a way that could expose it on a shared device.

## Troubleshooting

- **Customer role/permission error:** Enable the Supabase custom access-token hook and sign in again to refresh the token.
- **Restaurant workspace says the account is not an owner:** Sign in with the restaurant owner account, not a customer or staff account; enable the custom Auth hook and refresh the owner session.
- **Restaurant application or online orders are unavailable:** Check restaurant approval, online-order application status, service-fee status, and fulfilment/payment settings in the dashboard and platform admin.
- **Online directory/menu does not load:** Check the Supabase URL/key, deployed `online-restaurants`/`online-menu` functions, and function/database logs.
- **QR link fails:** Verify the QR signing secret, PWA base URL/origin, signed token, and deployed `session-join` function.
- **Build fails after dependency changes:** Run `npm ci` to install the committed lockfile dependencies.

## Privacy and security

Treat customer profile, order, payment-reference, and review data as sensitive. Use RLS and backend authorization as the security boundary. Do not log secrets or include service credentials in frontend code. Review `/privacy` and `/terms` and confirm operator/contact details before public release.
