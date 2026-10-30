# QR Dine Android

The Android app is a Kotlin and Jetpack Compose application for restaurant owners and staff, platform administrators, and customers. The same app provides separate role-based areas after authentication.

| Account | App area |
|---|---|
| Restaurant owner | Orders, online orders, tables, menu, insights, settings, staff, and restaurant service configuration |
| Waiter or kitchen staff | Role-limited restaurant operations |
| Platform administrator | Restaurant applications, subscription/payment records, and platform settings |
| Customer | Restaurant discovery, online menus, delivery/pickup orders, order history, and profile |

Package: `com.xcloak.qrdine` · Minimum Android version: API 26 · Java/Kotlin target: 17.

## Requirements

- Android Studio with JDK 17
- Android SDK configured by Android Studio
- A Supabase project with the backend migrations and Edge Functions required by the selected features

## Configure Supabase

Copy `local.properties.example` to `local.properties` and set:

```properties
SUPABASE_URL=https://YOUR_PROJECT_REF.supabase.co
SUPABASE_ANON_KEY=YOUR_PUBLIC_ANON_KEY
```

Android Studio may add `sdk.dir` to `local.properties`. This file is local and must not be committed. Use only the Supabase public anon/publishable key in the client. Never embed a service-role key or backend signing secret.

In Supabase Auth, allow the redirect URL:

```text
qrdine://auth/callback
```

Configure email confirmation and a production SMTP provider before public use. Enable the `public.custom_access_token_hook` for customer role claims; customer accounts need a fresh token after the hook is enabled.

## Build and test

Open this directory in Android Studio and allow Gradle sync, or run:

```bash
./gradlew testDebugUnitTest assembleDebug
```

The debug APK is written under `app/build/outputs/apk/debug/`. Push notifications are optional: the app builds without `app/google-services.json`, but FCM delivery while the app is killed will be unavailable.

## Authentication and account types

The sign-in screen separates **Restaurant** and **Customer** access. Account type is validated after Supabase authenticates credentials but before the app routes to a dashboard:

- Customer accounts must use Customer sign-in.
- Restaurant/platform/staff accounts must use Restaurant sign-in.
- A mismatch shows an error on the sign-in screen; it should not briefly open the other dashboard.
- New signups are tagged as `customer` or `restaurant`. Restaurant owners complete restaurant setup separately.

Platform administrator accounts are provisioned by an administrator in Supabase; do not create them through ordinary customer or restaurant signup. See the [backend guide](../backend/README.md).

## Customer app

The customer area includes restaurant discovery and search, online menus with categories and dish options, centered restaurant imagery, cart and checkout, delivery/pickup order details, order history, feedback, and profile settings. Light, dark, and system appearance choices are persisted on the device.

Customer checkout requires the customer account migration, online-order settings/order migrations, deployed `online-restaurants` and `online-menu` functions, and the Supabase Auth custom access-token hook. A public anon key alone does not enable customer permissions.

## Restaurant operations

- **Orders:** Table order requests, waiter/bill requests, online order attention, status actions, and refresh.
- **Online orders:** Accept or reject customer delivery/pickup orders and review their payment/customer details.
- **Menu:** Categories, dishes, photos, availability, sizes, stock, optional add-ons, and Buy N Get M offers.
- **Tables:** Table sessions and QR code generation.
- **Insights:** Sales, dishes, order channels, reviews/feedback, and date-range reporting.
- **Settings:** Restaurant details, online ordering, alerts, account, and appearance.

Capabilities shown in the app require matching database migrations, Edge Functions, and Supabase project configuration. Consult the [backend README](../backend/README.md) before enabling a feature in production.

## Optional FCM push notifications

1. Register package `com.xcloak.qrdine` in Firebase.
2. Place the appropriate `google-services.json` in `app/` (do not commit production secrets).
3. Configure Firebase service credentials and the webhook secret as Supabase function secrets.
4. Deploy the `notify` function and configure the required database webhooks.

Realtime refresh and in-app alerts can work without FCM. See the backend guide for complete webhook setup.

## Owner email flows and privacy

- Email-confirmation signup offers a resend action when confirmation is pending.
- **Forgot password?** sends a reset link; the reset flow returns through `qrdine://auth/callback`.
- Privacy and terms are available from the sign-in and restaurant settings screens.
- Android diagnostics are opt-in under Settings > This device. Remote reports require the diagnostics migration and `app-diagnostics` function.

Review the legal pages, diagnostics disclosure, and operator contact details before publishing the app.

## Troubleshooting

- **Customer permissions are unavailable:** Enable the Supabase custom access-token hook and sign out/in to obtain a fresh customer-role token.
- **Account-type message appears:** Choose the matching Restaurant or Customer sign-in section. A customer account cannot manage a restaurant.
- **Restaurant setup fails:** Confirm the required migrations are deployed and the authenticated account is a restaurant owner account.
- **No push notifications:** Check Firebase configuration, notification permission, function secrets, and database webhooks. Push is optional.
- **Supabase request fails:** Verify `local.properties`, deployed functions, migration state, Auth redirect URL, and project logs.

## Security

Client-side role routing improves UX but is not the security boundary. Supabase RLS, token claims, and Edge Function authorization must enforce access. Never put service-role credentials, QR signing secrets, or webhook secrets in Android resources, `local.properties`, or client-visible code.
