# QR Dine M6 patch

Unzip this over your `~/qrdine` folder (it keeps the same folder layout). It contains ONLY files that are new or changed since the last zips.
NEW = a file that did not exist before. REPLACE = overwrite your existing file.

## backend  (6 new, 8 replaced)

- NEW      backend/supabase/functions/session-coupon/index.ts
- NEW      backend/supabase/functions/staff-admin/index.ts
- NEW      backend/supabase/migrations/20261018000001_m6_features.sql
- NEW      backend/supabase/migrations/20261018000002_m6_platform.sql
- NEW      backend/supabase/tests/database/06_features.test.sql
- NEW      backend/supabase/tests/database/07_platform2.test.sql
- REPLACE  backend/README.md
- REPLACE  backend/supabase/config.toml.functions-snippet
- REPLACE  backend/supabase/functions/_shared/http.ts
- REPLACE  backend/supabase/functions/_shared/menu.ts
- REPLACE  backend/supabase/functions/notify/index.ts
- REPLACE  backend/supabase/functions/order-place/index.ts
- REPLACE  backend/supabase/functions/platform-admin/index.ts
- REPLACE  backend/supabase/functions/session-state/index.ts

## admin-android  (10 new, 31 replaced)

- NEW      admin-android/app/src/main/java/com/xcloak/qrdine/notify/OrderActionReceiver.kt
- NEW      admin-android/app/src/main/java/com/xcloak/qrdine/print/Escpos.kt
- NEW      admin-android/app/src/main/java/com/xcloak/qrdine/ui/AddItemsScreen.kt
- NEW      admin-android/app/src/main/java/com/xcloak/qrdine/ui/AddonGroupsScreen.kt
- NEW      admin-android/app/src/main/java/com/xcloak/qrdine/ui/CashCountScreen.kt
- NEW      admin-android/app/src/main/java/com/xcloak/qrdine/ui/Charts.kt
- NEW      admin-android/app/src/main/java/com/xcloak/qrdine/ui/HistoryScreen.kt
- NEW      admin-android/app/src/main/java/com/xcloak/qrdine/ui/KitchenScreen.kt
- NEW      admin-android/app/src/main/java/com/xcloak/qrdine/ui/PlatformExtras.kt
- NEW      admin-android/app/src/main/java/com/xcloak/qrdine/ui/SettingsPages2.kt
- REPLACE  admin-android/README.md
- REPLACE  admin-android/app/src/main/AndroidManifest.xml
- REPLACE  admin-android/app/src/main/java/com/xcloak/qrdine/data/MenuRepository.kt
- REPLACE  admin-android/app/src/main/java/com/xcloak/qrdine/data/Models.kt
- REPLACE  admin-android/app/src/main/java/com/xcloak/qrdine/data/OrdersRepository.kt
- REPLACE  admin-android/app/src/main/java/com/xcloak/qrdine/data/OrdersStore.kt
- REPLACE  admin-android/app/src/main/java/com/xcloak/qrdine/data/OwnerRepository.kt
- REPLACE  admin-android/app/src/main/java/com/xcloak/qrdine/data/PlatformRepository.kt
- REPLACE  admin-android/app/src/main/java/com/xcloak/qrdine/data/TableRepository.kt
- REPLACE  admin-android/app/src/main/java/com/xcloak/qrdine/notify/AlertService.kt
- REPLACE  admin-android/app/src/main/java/com/xcloak/qrdine/notify/Notifier.kt
- REPLACE  admin-android/app/src/main/java/com/xcloak/qrdine/notify/OrderFcmService.kt
- REPLACE  admin-android/app/src/main/java/com/xcloak/qrdine/notify/Prefs.kt
- REPLACE  admin-android/app/src/main/java/com/xcloak/qrdine/ui/AppRoot.kt
- REPLACE  admin-android/app/src/main/java/com/xcloak/qrdine/ui/AuthScreens.kt
- REPLACE  admin-android/app/src/main/java/com/xcloak/qrdine/ui/BillScreen.kt
- REPLACE  admin-android/app/src/main/java/com/xcloak/qrdine/ui/DishEditorScreen.kt
- REPLACE  admin-android/app/src/main/java/com/xcloak/qrdine/ui/Format.kt
- REPLACE  admin-android/app/src/main/java/com/xcloak/qrdine/ui/InsightsScreen.kt
- REPLACE  admin-android/app/src/main/java/com/xcloak/qrdine/ui/MainScreen.kt
- REPLACE  admin-android/app/src/main/java/com/xcloak/qrdine/ui/MenuScreen.kt
- REPLACE  admin-android/app/src/main/java/com/xcloak/qrdine/ui/MenuViewModel.kt
- REPLACE  admin-android/app/src/main/java/com/xcloak/qrdine/ui/OrdersScreen.kt
- REPLACE  admin-android/app/src/main/java/com/xcloak/qrdine/ui/OrdersViewModel.kt
- REPLACE  admin-android/app/src/main/java/com/xcloak/qrdine/ui/PlatformApp.kt
- REPLACE  admin-android/app/src/main/java/com/xcloak/qrdine/ui/PlatformHotel.kt
- REPLACE  admin-android/app/src/main/java/com/xcloak/qrdine/ui/PlatformViewModel.kt
- REPLACE  admin-android/app/src/main/java/com/xcloak/qrdine/ui/RootViewModel.kt
- REPLACE  admin-android/app/src/main/java/com/xcloak/qrdine/ui/SettingsScreen.kt
- REPLACE  admin-android/app/src/main/java/com/xcloak/qrdine/ui/TablesScreen.kt
- REPLACE  admin-android/app/src/main/java/com/xcloak/qrdine/ui/TablesViewModel.kt

## customer-pwa  (10 new, 13 replaced)

- NEW      customer-pwa/components/DishSheet.tsx
- NEW      customer-pwa/components/RegisterSW.tsx
- NEW      customer-pwa/lib/cart.ts
- NEW      customer-pwa/lib/i18n.tsx
- NEW      customer-pwa/lib/realtime.ts
- NEW      customer-pwa/public/icons/icon-192.png
- NEW      customer-pwa/public/icons/icon-512.png
- NEW      customer-pwa/public/icons/maskable-512.png
- NEW      customer-pwa/public/offline.html
- NEW      customer-pwa/public/sw.js
- REPLACE  customer-pwa/.env.example
- REPLACE  customer-pwa/README.md
- REPLACE  customer-pwa/app/globals.css
- REPLACE  customer-pwa/app/layout.tsx
- REPLACE  customer-pwa/app/manifest.ts
- REPLACE  customer-pwa/components/CartSheet.tsx
- REPLACE  customer-pwa/components/MenuView.tsx
- REPLACE  customer-pwa/components/OrdersView.tsx
- REPLACE  customer-pwa/components/TableApp.tsx
- REPLACE  customer-pwa/components/ThankYou.tsx
- REPLACE  customer-pwa/lib/api.ts
- REPLACE  customer-pwa/lib/types.ts
- REPLACE  customer-pwa/package.json

## The two fixes you reported
1. Web page crash "Cannot read properties of undefined (reading 'name')": customer-pwa/components/TableApp.tsx now tolerates a missing restaurant,
   but the real cause was an OLD session-state function still deployed. Redeploy all functions (below).
2. Orders screen "permission denied ... anon": admin-android/app/src/main/java/com/xcloak/qrdine/data/OrdersStore.kt retries with a fresh session
   (one refresh at a time). It was missing from the last zips by my mistake.

## Steps after unzipping
    cd ~/qrdine/backend
    npx supabase db push                      # two new migrations (009, 010)
    printf '[functions.session-coupon]\nverify_jwt = false\n[functions.staff-admin]\nverify_jwt = false\n' >> supabase/config.toml
    npx supabase functions deploy session-join session-state order-place service-request qr-sheet review-submit platform-admin notify session-coupon staff-admin
    cd ../customer-pwa && npm install && npm run dev      # new dependency
Optional: PWA .env.local gets NEXT_PUBLIC_SUPABASE_URL and NEXT_PUBLIC_SUPABASE_ANON_KEY (instant updates);
add a Database Webhook on table `reviews` (Insert) -> notify, same x-webhook-secret header.
Then rebuild the Android app in Android Studio (Sync Gradle first: a splash-screen dependency was added earlier, nothing new this time).
