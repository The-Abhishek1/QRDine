# QR Dine Admin (Android), M1

Kotlin + Jetpack Compose, supabase-kt. Package: com.xcloak.qrdine, minSdk 26.

## Run
1. Android Studio > Open > this folder. Let Gradle sync (JDK 17 = Studio's bundled JDK).
2. Copy `local.properties.example` into `local.properties`, add SUPABASE_URL and SUPABASE_ANON_KEY
   (Supabase dashboard > Project Settings > API). Rebuild.
3. For development, turn OFF "Confirm email" (Authentication > Providers > Email) so sign-up logs in at once.
4. Run on a device or emulator.

## M1 includes
Email/password sign-up and sign-in, restaurant setup (create_restaurant rpc + token refresh),
menu manager: categories (food/beverage), dishes with photo upload (compressed under 1 MB),
veg/non-veg, price, out-of-stock switch, soft delete. Everything goes through RLS as the owner.

## Acceptance checks
- Two accounts, two restaurants: neither can see the other's categories or dishes.
- Dish photo appears after save; stock switch changes immediately and survives a restart.
- Killing the app and reopening keeps you signed in.

## M2a: tables and QR codes
Bottom bar: Menu | Tables. Tables: add tables (T1, T2, ...), open a table (optional 4-digit code), cancel an empty
session, remove a closed table, print QR codes as an A4 PDF (4 per page, share/print), replace all QR codes.
QR links are signed by the `qr-sheet` Edge Function; deploy it first (see backend/README.md).
Table status refreshes every 8 s while the screen is open (realtime and the order alerts come in M3).

## M3: orders and alerts
Tabs: Orders | Menu | Tables (badge on Orders = things waiting for you).
- Orders: "Open for orders" switch (this is `restaurants.is_open`; guests see "not taking orders" when off).
  Needs you now: waiter/bill requests (Done) and pending orders (Accept with a wait time, or Reject with a reason).
  Cooking: accepted orders (Mark served). Two devices can be logged in: the first to act wins, the other is told.
- Live updates: realtime change events trigger a reload, with a 20 s poll as a safety net.
- Alerts: while open, a foreground service re-rings every 30 s until nothing is waiting (a new order rings at once).
  With `google-services.json` present, FCM pushes also wake the app when the system killed it.
  The first launch asks for notification permission; the Orders menu has "Make alerts reliable" (battery and
  autostart steps). Signing out stops the alerts and unregisters this phone from pushes.
- Without `app/google-services.json` the app still builds and runs; only killed-app pushes are off.
Play Console note: the alert service uses the `specialUse` foreground service type, which needs a short
declaration in the app content form ("keeps a live connection to alert the owner to customer orders").

## M4: billing, serving view, insights, settings
Five tabs: Orders | Tables | Menu | Insights | Settings.
- Orders > Needs action: new orders (accept with wait time / reject), waiter calls, bill requests ("Open bill"), cooking orders.
- Orders > Serving: every table in service with running total, what is waiting/cooking/served. Served orders stay here
  until the bill is paid. Tap a table (also from the Tables tab) for the bill screen: every dish, taxes, coupon code,
  Cash / UPI / Cash + UPI, "Mark paid". That closes the table, numbers the bill, thanks the guest, and offers a PDF to share/print.
- Insights: today / 7 / 30 days / this month. Revenue per day, cash vs UPI, top and slowest dishes, busy hours, reviews
  (newest or lowest first), recent bills (tap to reprint), CSV export for your accountant.
- Settings: restaurant name, photo, address, phone, FSSAI; GST mode/rate/GSTIN, service charge, UPI ID; ordering mode;
  announcement and coupons; Alert health (checklist + test alert); email, change password, sign out.
- Menu: rename or delete a category (a category must be empty to delete).
- Menu: Extras and Offers are available directly from the menu screen. Existing extras are made optional; groups can still be configured
  with a minimum if a restaurant specifically needs a required choice. Create Buy N Get M offers for the same dish or a different free dish.
- Alerts also ring from the alert service itself (alarm tone + vibration), so they no longer depend only on notification settings.

## M5: platform admin area, approval flow, sales downloads, polish
Same app, three kinds of sign-in:
- **Hotel owner, not yet approved**: after signing up they fill in the restaurant and see "Application submitted, waiting for the admin's approval".
  The screen checks by itself and opens the app the moment you approve. Rejected or held hotels see your message and your support contact.
- **Approved hotel owner**: the normal app. Renewal banners appear 7 days before the trial or subscription ends, when overdue, and when ended.
- **You (platform admin)**: Dashboard (applications waiting, active, trial, overdue, collected money), Hotels (search, filters,
  approve / reject / hold / release / edit / extend / adjust dates / reset password / delete, export CSV), Payments (cash, UPI or bank ledger,
  record payments, export CSV), Settings (support phone/email and "how to pay" shown to hotels, your password, activity log).
Hotel owners can now download a **sales summary PDF** for today, 7 days, 30 days, this month or any custom date range
(Insights tab), plus the bills CSV.
Polish: brand theme (light and dark), splash screen, app icon, show/hide password, consistent screens.
The shared UI theme uses translucent surfaces and a soft, adaptive gradient backdrop for a glass-style finish across auth,
owner, kitchen and platform-admin screens.
Create your platform admin login first: see backend/README.md (M5).
