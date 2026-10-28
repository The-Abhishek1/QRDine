# QR Dine customer PWA (Next.js 15)

    cp .env.example .env.local     # set the functions URL, Supabase URL and public anon key
    npm install
    npm run dev                    # http://<your-LAN-IP>:3000 ; QR links look like /t/<signed-token>
    npm run build && npm start     # production check

Deploy anywhere that runs Next.js (Vercel, Cloudflare, your VPS). The table-ordering PWA uses Edge Functions;
customer sign-in also requires the public Supabase anon key in `NEXT_PUBLIC_SUPABASE_ANON_KEY`. Never use a
service-role key in the PWA. Set PWA_BASE_URL and PWA_ORIGIN in Supabase secrets to this site's https origin.

Included: scan -> menu (search, veg filter, sections, sold-out, restaurant offers) -> cart (eat here / parcel, note) -> order ->
status with ready-by time -> running bill -> call waiter / request bill. Table-closed, PIN, shop-closed and
session-ended screens. Cart survives reloads; double taps and bad networks never create duplicate orders.
Bill with GST breakdown, Request bill only after everything is served, Pay with UPI button, thank-you and review after payment.
Before launch add app icons to `app/manifest.ts` so it can be installed to the home screen.

## Customer online ordering (in progress)
`/explore` supports a separate customer account, lists restaurants that have enabled online orders, and lets
customers browse menus, configure variants and optional/required add-ons, see active offers, and build a
stock-limited local selection. This selection is not submitted as an order; checkout, delivery-area matching,
and online order management are later stages.

Set the public Supabase URL and anon key in `.env.local`, apply migrations
`20261028000002_online_order_settings.sql` and `20261028000003_customer_accounts.sql`, and deploy the
`online-restaurants` and `online-menu` Edge Functions. Ensure Supabase Authentication's custom access token
hook is enabled for `public.custom_access_token_hook`. Never put a service-role key in the PWA.

## M6 additions
Sizes and extras with a pick-options sheet, special instructions per dish, "people also order" and bestseller badges, timed menu sections,
low-stock tags, guest coupon box, "ready" status, language switcher (English + Hindi/Kannada labels; dish names in any language the hotel adds),
instant updates (optional env vars, see `.env.example`), installable on the home screen over https (icons + service worker; never caches table pages).
Run `npm install` again: a new dependency (`@supabase/supabase-js`) was added for the instant updates.

## M7 offers
Active Buy N Get M offers appear above the menu. Customers add the qualifying dish and reward dish; the cheapest eligible reward units
are discounted automatically in the cart and order. Add-on groups default to optional; customers can add extras without being forced to
choose them. Deploy the M7 backend migration and Edge Functions with the matching `config.toml` before deploying this PWA.

## Legal pages
Public privacy and terms pages are available at `/privacy` and `/terms`, and linked in the site footer. Review the wording
with qualified counsel and confirm the operator/contact details before using these pages for a public or store submission.
