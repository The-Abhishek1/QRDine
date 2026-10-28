package com.xcloak.qrdine.data

import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.functions.functions
import io.github.jan.supabase.postgrest.from
import io.github.jan.supabase.postgrest.postgrest
import io.ktor.client.statement.bodyAsText
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

class TableRepository(private val sb: SupabaseClient, private val restaurantId: String) {
    private val json = Json { ignoreUnknownKeys = true }
    private fun trailingNumber(label: String): Int? = Regex("(\\d+)$").find(label)?.groupValues?.get(1)?.toIntOrNull()

    suspend fun tables(): List<DiningTable> =
        sb.from("dining_tables").select { filter { eq("restaurant_id", restaurantId) } }.decodeList<DiningTable>()
            .sortedWith(compareBy({ trailingNumber(it.label) ?: Int.MAX_VALUE }, { it.label }))

    /** Adds T<n+1>... continuing after the highest existing number. */
    suspend fun addTables(count: Int, existing: List<DiningTable>) {
        val start = (existing.mapNotNull { trailingNumber(it.label) }.maxOrNull() ?: 0)
        sb.from("dining_tables").insert((1..count).map { NewTable(restaurantId, "T${start + it}") })
    }

    suspend fun deleteTable(id: String) {
        sb.from("dining_tables").delete { filter { eq("id", id) } }
    }

    suspend fun openTable(id: String, pin: String?) {
        sb.postgrest.rpc("open_table", buildJsonObject {
            put("p_table_id", id)
            if (!pin.isNullOrBlank()) put("p_pin", pin)
        })
    }

    suspend fun moveSession(sessionId: String, toTableId: String) {
        sb.postgrest.rpc("move_session", buildJsonObject { put("p_session_id", sessionId); put("p_to_table_id", toTableId) })
    }

    suspend fun mergeSessions(fromSessionId: String, intoSessionId: String) {
        sb.postgrest.rpc("merge_sessions", buildJsonObject { put("p_from", fromSessionId); put("p_into", intoSessionId) })
    }

    suspend fun voidSession(tableId: String) {
        sb.postgrest.rpc("void_session", buildJsonObject { put("p_table_id", tableId) })
    }

    /** Old printed QR codes stop working: the signed token carries the table's key version. */
    suspend fun rotateQr(tables: List<DiningTable>) {
        tables.forEach { t ->
            sb.from("dining_tables").update({ set("qr_key_version", t.qrKeyVersion + 1) }) { filter { eq("id", t.id) } }
        }
    }

    /** Asks the qr-sheet Edge Function for signed QR links (the signing secret never reaches the app). */
    suspend fun qrLinks(): List<QrItem> {
        val resp = sb.functions.invoke("qr-sheet")
        return json.decodeFromString<QrSheetResponse>(resp.bodyAsText()).tables
    }
}
