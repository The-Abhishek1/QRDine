# Backend (Supabase), M0 Foundations

## Local setup (needs Docker running)
    npm i -g supabase            # or: npx supabase ...
    cd backend
    supabase init                # creates supabase/config.toml (keeps the folders already here)

Add this to supabase/config.toml so local Auth runs the owner-claim hook:

    [auth.hook.custom_access_token]
    enabled = true
    uri = "pg-functions://postgres/public/custom_access_token_hook"

Also set `enable_anonymous_sign_ins = false` under [auth] (default) - guests never use Supabase Auth.

    supabase start
    supabase db reset            # applies all migrations
    supabase test db             # runs tests/database/*.test.sql (pgTAP) - all 14 must pass

## Hosted project
    supabase login
    supabase link --project-ref <your-project-ref>
    supabase db push
Then in the dashboard: Authentication > Hooks > Custom Access Token > select
`public.custom_access_token_hook`. Disable anonymous sign-ins. Enable Email + Google providers.

## What M0 gives you
- 001 schema: every tenant table has restaurant_id; composite FKs stop cross-hotel links
- 002 security: claims helpers, access-token hook, revoked default grants, RLS, column-level grants
  (owner cannot edit sub_status / trial / purchase token / QR version)
- 003 functions: create_restaurant, open_table, accept_order, reject_order (rpc from the app)
- 004 storage bucket `dish-images` + Realtime publication
- tests: 14 cross-tenant / guest / anon checks. Add a test whenever you add a table or function.

## M2: ordering (migration 005 + Edge Functions)
Migration `20261013000001_m2_ordering.sql` adds place_order (server-side pricing, idempotent, rate-limited),
request_service, verify_session_pin (5 wrong = 10 min lock), open_session_if_needed, void_session.
Tests: `supabase test db` runs 37 checks (14 tenancy + 19 ordering + 4 owner order actions).

### Edge Functions (supabase/functions)
| Function | Caller | Auth |
|---|---|---|
| session-join | PWA | signed QR token (+ optional table PIN) -> guest token + menu |
| session-state | PWA, polled every 5 s | guest token |
| order-place | PWA | guest token; prices read in SQL, client sends ids and quantities only |
| service-request | PWA | guest token (call waiter / request bill) |
| qr-sheet | Android | owner access token, checked with auth.getUser(); returns signed QR links |

### Setup
1. Append `config.toml.functions-snippet` to `supabase/config.toml`.
2. Secrets:
       supabase secrets set QR_SIGNING_SECRET=$(openssl rand -hex 32) \
         PWA_BASE_URL=https://order.yourdomain.com PWA_ORIGIN=https://order.yourdomain.com
3. Deploy:
       supabase db push
       supabase functions deploy session-join session-state order-place service-request qr-sheet
4. Local: copy `functions/.env.example` to `functions/.env`, then
       supabase functions serve --env-file supabase/functions/.env --no-verify-jwt
   To test from a phone use your computer's LAN IP in PWA_BASE_URL / PWA_ORIGIN and in the apps' URLs.

Guests do not use Supabase Auth or Realtime in M2: the PWA polls session-state with a signed guest token.
(Realtime for guests would need tokens signed with your project's JWT secret, which depends on your
project's key setup; polling every 5 s is enough for ordering and keeps the PWA free of any Supabase key.)
Never put QR_SIGNING_SECRET or the service-role key in an app or in the PWA.

## M3: order alerts (migration 006 + notify function)
`complete_order` (mark served). The `notify` function sends a high-priority FCM data message to every device
registered for the restaurant whenever an order or a waiter/bill request is created.

### Push setup (optional until you want alerts when the app is killed; realtime + the alert service work without it)
1. Firebase console: create a project, add an Android app with package `com.xcloak.qrdine`, download
   `google-services.json` and put it in `admin-android/app/`.
2. Firebase > Project settings > Service accounts > Generate new private key. Then:
       supabase secrets set WEBHOOK_SECRET=$(openssl rand -hex 24)
       supabase secrets set FCM_SERVICE_ACCOUNT_JSON="$(cat path/to/service-account.json)"
   (Make sure the "Firebase Cloud Messaging API (V1)" is enabled for the project.)
3. `supabase functions deploy notify`
4. Dashboard > Database > Webhooks > Create two webhooks, both type "Supabase Edge Functions" -> `notify`,
   event Insert, and an HTTP header `x-webhook-secret` = the WEBHOOK_SECRET value:
   one on table `orders`, one on table `service_requests`.
Dead tokens are removed automatically. The service-account key and the webhook secret never go in an app.

## M4: billing, insights, settings (migration 007 + review-submit)
Apply and redeploy (the shared error mapping changed, so redeploy every function):

    npx supabase db push
    npx supabase functions deploy session-join session-state order-place service-request qr-sheet review-submit
    # only if you set up push: npx supabase functions deploy notify

What it adds: `compute_bill` / `get_bill` (GST, service charge, coupon, round-off), `close_session` (mark paid:
serves leftover cooking orders, gap-free invoice number per financial year, closes the table, resolves requests),
`owner_serving`, `owner_stats`, `delete_category`, `submit_review`, phone + FSSAI columns, and the bill rules:
a bill can be requested only when every order is served (or rejected), and re-ordering cancels the open bill request.
Tests: `supabase test db` runs 62 checks (14 + 19 + 4 + 25).

## M5: platform admin, hotel approval, subscription ledger (migration 008 + platform-admin function)
You (the SaaS owner) get a separate area in the same Android app. Hotels apply, you approve, hold or delete them,
and you record their subscription payments (cash, UPI or bank).

### Update an existing setup
    npx supabase db push
    printf '[functions.platform-admin]\nverify_jwt = false\n' >> supabase/config.toml
    npx supabase functions deploy platform-admin

Existing hotels become **approved** automatically. Every new signup starts as **pending**.

### Create YOUR platform admin login (once)
1. Supabase dashboard > Authentication > Users > Add user > Create new user: your email + a strong password, tick "Auto Confirm User".
2. SQL Editor, run (with your email):

       insert into public.platform_admins (user_id) select id from auth.users where email = 'you@example.com';

3. Open the app and sign in with that email. You land in the Platform area (Dashboard, Hotels, Payments, Settings).
   Change the password any time in Platform > Settings. If you ever forget it, set a new one in the dashboard (Users).
Never sign up your admin email through the app's normal sign-up; that creates a hotel application.

### How access is enforced (not just hidden in the app)
- A hotel that is pending, rejected or on hold: the database treats its owner as nobody (RLS), so even an old token reads nothing,
  and guests cannot order (`restaurant_can_take_orders`).
- Trial, active and the 3-day grace period take orders. After that guests are blocked; the owner can still log in to read and export data.
- Hotels cannot approve themselves: the approval columns are not in their UPDATE grant.
- Only users in `platform_admins` can run the platform functions; the account actions (create hotel, reset password, delete hotel)
  run in the `platform-admin` Edge Function and re-check the caller in the database.
- Deleting a hotel removes everything about it, but its payment records stay in your ledger.
Tests: `supabase test db` runs 83 checks (14 + 19 + 4 + 25 + 21).

## M6: 17 features + fixes (migrations 009 and 010)
Apply, then redeploy **all** functions (shared code changed) and add the new ones:

    cd ~/qrdine/backend
    npx supabase db push
    npx supabase functions deploy session-join session-state order-place service-request qr-sheet review-submit platform-admin notify session-coupon staff-admin

New functions: `session-coupon` (guest coupons), `staff-admin` (owner creates waiter/kitchen logins).
Low-review alerts: add a third Database Webhook on table `reviews` (Insert) pointing at `notify`, with the same `x-webhook-secret` header.

What the migrations add: variants and add-ons priced in SQL; stock counts that sell out and give stock back; timed menus; translations;
staff roles (owner / waiter / kitchen, enforced by RLS and role checks in every function); cancel an item with a reason; "ready" status;
move and merge tables; manager-PIN discounts (wrong PINs counted and locked); guest coupons; cash count; bestsellers and "people also order";
live-update pings; platform insights, read-only view-as (logged), broadcast, payment receipts, your business details.
**Security fix:** your private note about a hotel used to sit in a row the hotel could read. It now lives in `hotel_notes`, which no client can read.
Tests: `supabase test db` runs 136 checks (14 + 19 + 4 + 25 + 21 + 41 + 12).

## M7: optional extras, Buy N Get M offers, review endpoint config
Apply the new migration and redeploy the guest functions using the checked-in `supabase/config.toml` (guest-token endpoints must have
`verify_jwt = false`; they authenticate with their signed guest token instead):

    cd ~/qrdine/backend
    npx supabase db push
    npx supabase functions deploy session-join session-state order-place review-submit session-coupon

Existing add-on groups are made optional by setting their minimum selection to zero. Owners manage extras and offers from the Menu
screen in the Android app. An offer selects one qualifying dish and one reward dish; selecting the same dish creates a same-item deal.
On each submitted order, every qualifying bundle discounts the cheapest eligible reward units already in that order. Offers do not add
items to the guest's cart and a reward unit already discounted by an offer cannot be discounted again. The PWA displays active deals,
previews the free quantities in the cart, and the SQL order function applies the authoritative discount.
Database checks now total 143, including Buy N Get M repetition, cheapest-reward, non-stacking, and optional-extra checks.

### Live order updates for guests (optional, recommended)
The database sends a tiny "something changed" ping on `session:<id>`. Guests refresh instantly when the PWA has
`NEXT_PUBLIC_SUPABASE_URL` and `NEXT_PUBLIC_SUPABASE_ANON_KEY` set. In Supabase: Realtime settings must allow public channels (the default).
If it is off or unreachable, the page still polls (every 4 s while something is in progress, 12 s otherwise).
