# QR Dine

    qrdine/
      backend/        Supabase: migrations (001-010), pgTAP tests (136), Edge Functions (10)
      admin-android/  Kotlin + Jetpack Compose app: hotel owner, waiter, kitchen, and platform admin
      customer-pwa/   Next.js PWA for guests

Order of work: apply backend migrations -> deploy functions -> run the Android app -> run the PWA -> scan a QR -> order
-> accept -> ready -> served -> request bill -> mark paid.
