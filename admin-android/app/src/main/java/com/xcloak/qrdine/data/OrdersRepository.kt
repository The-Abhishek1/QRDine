package com.xcloak.qrdine.data

import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.postgrest.from
import io.github.jan.supabase.postgrest.postgrest
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

data class OrderCard(val order: OrderRow, val items: List<OrderItemRow>, val tableLabel: String) {
    val total: Int get() = items.filter { !it.cancelled }.sumOf { it.qty * it.unitPrice }
}
data class StaffItem(val dishId: String, val qty: Int, val variantId: String?, val addonIds: List<String>)
data class RequestCard(val req: ServiceRequestRow, val tableLabel: String)
data class OwnerSnapshot(
    val loaded: Boolean = false,
    val isOpen: Boolean = false,
    val pending: List<OrderCard> = emptyList(),
    val accepted: List<OrderCard> = emptyList(),
    val requests: List<RequestCard> = emptyList(),
    val serving: List<ServingRow> = emptyList(),
    val error: String? = null,
) {
    /** Things that need the owner right now. The alert service keeps ringing while this is above zero. */
    val attention: Int get() = pending.size + requests.size
}

class OrdersRepository(private val sb: SupabaseClient) {

    suspend fun load(): OwnerSnapshot {
        val orders = sb.from("orders").select { filter { isIn("status", listOf("pending", "accepted")) } }.decodeList<OrderRow>()
        val reqs = sb.from("service_requests").select { filter { eq("status", "open") } }.decodeList<ServiceRequestRow>()
        val items = if (orders.isEmpty()) emptyList()
        else sb.from("order_items").select { filter { isIn("order_id", orders.map { it.id }) } }.decodeList<OrderItemRow>()
        val sessionIds = (orders.map { it.sessionId } + reqs.map { it.sessionId }).distinct()
        val sessions = if (sessionIds.isEmpty()) emptyList()
        else sb.from("sessions").select { filter { isIn("id", sessionIds) } }.decodeList<SessionRow>()
        val tables = sb.from("dining_tables").select().decodeList<DiningTable>()
        val rest = sb.from("restaurants").select().decodeSingle<Restaurant>()
        val serving = sb.postgrest.rpc("owner_serving").decodeAs<List<ServingRow>>()

        val label = sessions.associate { s -> s.id to (tables.firstOrNull { it.id == s.tableId }?.label ?: "?") }
        fun card(o: OrderRow) = OrderCard(o, items.filter { it.orderId == o.id }, label[o.sessionId] ?: "?")
        val sorted = orders.sortedBy { it.createdAt }
        return OwnerSnapshot(
            loaded = true,
            isOpen = rest.isOpen,
            pending = sorted.filter { it.status == "pending" }.map(::card),
            accepted = sorted.filter { it.status == "accepted" }.map(::card),
            requests = reqs.sortedBy { it.createdAt }.map { RequestCard(it, label[it.sessionId] ?: "?") },
            serving = serving,
        )
    }

    suspend fun accept(orderId: String, etaMinutes: Int) {
        sb.postgrest.rpc("accept_order", buildJsonObject { put("p_order_id", orderId); put("p_eta_minutes", etaMinutes) })
    }

    suspend fun reject(orderId: String, reason: String) {
        sb.postgrest.rpc("reject_order", buildJsonObject { put("p_order_id", orderId); put("p_reason", reason) })
    }

    suspend fun markReady(orderId: String) {
        sb.postgrest.rpc("mark_order_ready", buildJsonObject { put("p_order_id", orderId) })
    }

    /** Staff-entered order for a table. items: dish id, qty, optional variant and add-on ids. Returns once the kitchen has it. */
    suspend fun addOrder(sessionId: String, items: List<StaffItem>, parcel: Boolean, note: String) {
        sb.postgrest.rpc("owner_add_order", buildJsonObject {
            put("p_session_id", sessionId)
            put("p_items", kotlinx.serialization.json.buildJsonArray {
                items.forEach { i ->
                    add(buildJsonObject {
                        put("dish_id", i.dishId); put("qty", i.qty)
                        if (i.variantId != null) put("variant_id", i.variantId)
                        put("addon_ids", kotlinx.serialization.json.buildJsonArray { i.addonIds.forEach { add(kotlinx.serialization.json.JsonPrimitive(it)) } })
                    })
                }
            })
            put("p_order_type", if (parcel) "parcel" else "dine_in")
            if (note.isNotBlank()) put("p_note", note.trim())
        })
    }

    suspend fun cancelItem(itemId: String, reason: String) {
        sb.postgrest.rpc("cancel_order_item", buildJsonObject { put("p_item_id", itemId); put("p_reason", reason) })
    }

    suspend fun complete(orderId: String) {
        sb.postgrest.rpc("complete_order", buildJsonObject { put("p_order_id", orderId) })
    }

    suspend fun resolveRequest(id: String) {
        sb.from("service_requests").update({
            set("status", "resolved")
            set("resolved_at", java.time.Instant.now().toString())
        }) { filter { eq("id", id) } }
    }

    suspend fun setOpen(restaurantId: String, open: Boolean) {
        sb.from("restaurants").update({ set("is_open", open) }) { filter { eq("id", restaurantId) } }
    }

    suspend fun registerDevice(restaurantId: String, token: String) {
        // (restaurant_id, fcm_token) is unique, so registering twice just fails harmlessly
        runCatching { sb.from("devices").insert(NewDevice(restaurantId, token)) }
    }

    suspend fun deleteDevice(token: String) {
        sb.from("devices").delete { filter { eq("fcm_token", token) } }
    }
}
