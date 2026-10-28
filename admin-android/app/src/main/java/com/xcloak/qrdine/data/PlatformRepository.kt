package com.xcloak.qrdine.data

import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.functions.functions
import io.github.jan.supabase.postgrest.postgrest
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpMethod
import io.ktor.http.contentType
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** Everything the SaaS owner does. SQL functions check platform_admins themselves; account actions go through an Edge Function. */
class PlatformRepository(private val sb: SupabaseClient) {
    private val json = Json { ignoreUnknownKeys = true }

    suspend fun summary(): PlatformSummary = sb.postgrest.rpc("platform_summary").decodeAs<PlatformSummary>()
    suspend fun hotels(): List<HotelRow> = sb.postgrest.rpc("platform_hotels").decodeAs<List<HotelRow>>()
    suspend fun onlineApplications(): List<OnlineApplication> =
        sb.postgrest.rpc("platform_online_applications").decodeAs<List<OnlineApplication>>()
    suspend fun onlineCodAvailable(): Boolean = sb.postgrest.rpc("online_cod_available").decodeAs<Boolean>()
    suspend fun setOnlineCodAvailable(enabled: Boolean) {
        sb.postgrest.rpc("platform_set_online_cod", buildJsonObject { put("p_enabled", enabled) })
    }
    suspend fun reviewOnlineApplication(id: String, approve: Boolean, reason: String?) {
        sb.postgrest.rpc("platform_review_online_application", buildJsonObject {
            put("p_rid", id)
            put("p_approve", approve)
            if (!reason.isNullOrBlank()) put("p_reason", reason.trim())
        })
    }
    suspend fun payments(): List<PaymentRow> = sb.postgrest.rpc("platform_payments").decodeAs<List<PaymentRow>>()
    suspend fun support(): SupportInfo = sb.postgrest.rpc("platform_get_support").decodeAs<SupportInfo>()
    suspend fun activity(): List<ActivityRow> =
        sb.postgrest.rpc("platform_activity", buildJsonObject { put("p_limit", 40) }).decodeAs<List<ActivityRow>>()

    suspend fun diagnostics(): List<DiagnosticReport> {
        val response = sb.functions.invoke("app-diagnostics") {
            method = HttpMethod.Post
            contentType(ContentType.Application.Json)
            setBody(buildJsonObject { put("action", "list") }.toString())
        }.bodyAsText()
        val result = json.decodeFromString<DiagnosticResponse>(response)
        if (!result.ok) error("diagnostics_unavailable")
        return result.reports
    }

    suspend fun review(rid: String, approve: Boolean, reason: String?, trialDays: Int) {
        sb.postgrest.rpc("platform_review", buildJsonObject {
            put("p_rid", rid); put("p_approve", approve); put("p_trial_days", trialDays)
            if (!reason.isNullOrBlank()) put("p_reason", reason.trim())
        })
    }

    suspend fun setHold(rid: String, hold: Boolean, reason: String?) {
        sb.postgrest.rpc("platform_set_hold", buildJsonObject {
            put("p_rid", rid); put("p_hold", hold)
            if (!reason.isNullOrBlank()) put("p_reason", reason.trim())
        })
    }

    suspend fun updateHotel(rid: String, name: String, phone: String, address: String, gstin: String, note: String) {
        sb.postgrest.rpc("platform_update_hotel", buildJsonObject {
            put("p_rid", rid); put("p_name", name); put("p_phone", phone); put("p_address", address)
            put("p_gstin", gstin); put("p_admin_note", note)
        })
    }

    suspend fun extend(rid: String, days: Int) {
        sb.postgrest.rpc("platform_extend", buildJsonObject { put("p_rid", rid); put("p_days", days) })
    }

    suspend fun setDates(rid: String, trialEndsAt: String, subExpiresAt: String?) {
        sb.postgrest.rpc("platform_set_dates", buildJsonObject {
            put("p_rid", rid); put("p_trial_ends_at", trialEndsAt)
            put("p_sub_expires_at", subExpiresAt?.let(::JsonPrimitive) ?: JsonNull)
        })
    }

    suspend fun recordPayment(rid: String, amountPaise: Long, method: String, reference: String, months: Int, paidOn: String, note: String) {
        sb.postgrest.rpc("platform_record_payment", buildJsonObject {
            put("p_rid", rid); put("p_amount", amountPaise); put("p_method", method); put("p_reference", reference)
            put("p_months", months); put("p_paid_on", paidOn); put("p_note", note)
        })
    }

    suspend fun deletePayment(id: String) {
        sb.postgrest.rpc("platform_delete_payment", buildJsonObject { put("p_id", id) })
    }

    suspend fun setSupport(phone: String, email: String, paymentInfo: String, bizName: String, bizAddress: String, bizGstin: String) {
        sb.postgrest.rpc("platform_set_support", buildJsonObject {
            put("p_phone", phone); put("p_email", email); put("p_payment_info", paymentInfo)
            put("p_business_name", bizName); put("p_business_address", bizAddress); put("p_business_gstin", bizGstin)
        })
    }

    suspend fun insights(): PlatformInsights = sb.postgrest.rpc("platform_insights").decodeAs<PlatformInsights>()

    suspend fun viewHotel(rid: String): HotelView = sb.postgrest.rpc("platform_view_hotel", buildJsonObject { put("p_rid", rid) }).decodeAs<HotelView>()

    suspend fun setBroadcast(message: String, days: Int) {
        sb.postgrest.rpc("platform_set_broadcast", buildJsonObject { put("p_message", message); put("p_days", days) })
    }

    private suspend fun adminCall(body: JsonObject): AdminResult {
        val resp = sb.functions.invoke("platform-admin") {
            method = HttpMethod.Post
            contentType(ContentType.Application.Json)
            setBody(body.toString())
        }
        return json.decodeFromString<AdminResult>(resp.bodyAsText())
    }

    suspend fun createHotel(email: String, password: String, name: String, phone: String, address: String, trialDays: Int): AdminResult =
        adminCall(buildJsonObject {
            put("action", "create_hotel"); put("email", email); put("password", password); put("name", name)
            put("phone", phone); put("address", address); put("trial_days", trialDays)
        })

    suspend fun resetPassword(rid: String, newPassword: String): AdminResult =
        adminCall(buildJsonObject { put("action", "reset_password"); put("restaurant_id", rid); put("new_password", newPassword) })

    suspend fun deleteHotel(rid: String, confirmName: String): AdminResult =
        adminCall(buildJsonObject { put("action", "delete_hotel"); put("restaurant_id", rid); put("confirm_name", confirmName) })
}
