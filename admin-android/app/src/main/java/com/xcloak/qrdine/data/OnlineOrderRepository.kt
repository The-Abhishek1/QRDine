package com.xcloak.qrdine.data

import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.postgrest.postgrest
import io.github.jan.supabase.storage.storage
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.time.Duration.Companion.minutes

class OnlineOrderRepository(private val sb: SupabaseClient) {
    suspend fun orders(): List<OnlineOrder> = sb.postgrest.rpc(
        "restaurant_online_orders",
        buildJsonObject { put("p_limit", 50) },
    ).decodeAs()

    suspend fun pendingOrders(): List<OnlineOrder> = sb.postgrest.rpc(
        "restaurant_online_orders",
        buildJsonObject { put("p_limit", 100) },
    ).decodeAs<List<OnlineOrder>>().filter { it.status == "pending" }

    suspend fun paymentScreenshotUrl(path: String): String =
        sb.storage.from("payment-proofs").createSignedUrl(path, 10.minutes)

    suspend fun action(orderId: String, action: String, reason: String? = null, etaMinutes: Int? = null) {
        sb.postgrest.rpc("restaurant_online_order_action", buildJsonObject {
            put("p_order_id", orderId)
            put("p_action", action)
            if (reason != null) put("p_reason", reason)
            if (etaMinutes != null) put("p_eta_minutes", etaMinutes)
        })
    }
}
