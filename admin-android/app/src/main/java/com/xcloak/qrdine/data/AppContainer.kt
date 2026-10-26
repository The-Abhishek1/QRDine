package com.xcloak.qrdine.data

import com.xcloak.qrdine.BuildConfig
import io.github.jan.supabase.auth.Auth
import io.github.jan.supabase.auth.FlowType
import io.github.jan.supabase.createSupabaseClient
import io.github.jan.supabase.functions.Functions
import io.github.jan.supabase.postgrest.Postgrest
import io.github.jan.supabase.realtime.Realtime
import com.xcloak.qrdine.QrDineApp
import io.github.jan.supabase.storage.Storage

class AppContainer {
    val supabase = createSupabaseClient(
        supabaseUrl = BuildConfig.SUPABASE_URL,
        supabaseKey = BuildConfig.SUPABASE_ANON_KEY,
    ) {
        install(Auth) {
            scheme = "qrdine"
            host = "auth"
            flowType = FlowType.IMPLICIT
        }
        install(Postgrest)
        install(Storage)
        install(Functions)
        install(Realtime)
    }
    val auth = AuthRepository(supabase)
    val orders = OrdersRepository(supabase)
    val owner = OwnerRepository(supabase)
    val platform = PlatformRepository(supabase)
    val ordersStore = OrdersStore(orders, supabase, QrDineApp.appScope)
}
