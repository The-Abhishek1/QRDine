package com.xcloak.qrdine.data

import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.auth.auth
import io.github.jan.supabase.functions.functions
import io.github.jan.supabase.postgrest.from
import io.github.jan.supabase.postgrest.postgrest
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpMethod
import io.ktor.http.contentType
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.time.LocalDate
import java.time.ZoneId

sealed interface CloseOutcome {
    data class Done(val result: CloseResult) : CloseOutcome
    data class Failed(val code: String) : CloseOutcome
}

/** Everything the owner and the staff do beyond orders: billing, insights, settings, offers. All under RLS / role-checked functions. */
class OwnerRepository(private val sb: SupabaseClient) {
    private val ist = ZoneId.of("Asia/Kolkata")
    private val json = Json { ignoreUnknownKeys = true }

    // ---- billing
    suspend fun bill(sessionId: String, coupon: String?, manualPaise: Long = 0): Bill =
        sb.postgrest.rpc("get_bill", buildJsonObject {
            put("p_session_id", sessionId)
            if (!coupon.isNullOrBlank()) put("p_coupon_code", coupon.trim())
            if (manualPaise > 0) put("p_manual_discount", manualPaise)
        }).decodeAs<Bill>()

    suspend fun closeSession(sessionId: String, method: String, cash: Long?, upi: Long?, coupon: String?, manualPaise: Long, pin: String?): CloseOutcome {
        val r = sb.postgrest.rpc("close_session", buildJsonObject {
            put("p_session_id", sessionId)
            put("p_method", method)
            if (method == "split") put("p_split", buildJsonObject { put("cash", cash ?: 0L); put("upi", upi ?: 0L) } as JsonObject)
            if (!coupon.isNullOrBlank()) put("p_coupon_code", coupon.trim())
            if (manualPaise > 0) { put("p_manual_discount", manualPaise); if (!pin.isNullOrBlank()) put("p_pin", pin) }
        }).decodeAs<CloseResponse>()
        return if (r.error != null) CloseOutcome.Failed(r.error)
        else CloseOutcome.Done(CloseResult(r.invoiceId.orEmpty(), r.fy.orEmpty(), r.number ?: 0, r.total ?: 0))
    }

    suspend fun clearGuestCoupon(sessionId: String) {
        sb.postgrest.rpc("clear_session_coupon", buildJsonObject { put("p_session_id", sessionId) })
    }

    suspend fun invoice(id: String): InvoiceRow =
        sb.from("invoices").select { filter { eq("id", id) } }.decodeSingle<InvoiceRow>()

    suspend fun invoices(fromIso: String, toIso: String): List<InvoiceRow> =
        sb.from("invoices").select { filter { gte("issued_at", fromIso); lt("issued_at", toIso) } }
            .decodeList<InvoiceRow>().sortedByDescending { it.issuedAt }

    // ---- manager PIN and the day's cash
    suspend fun hasManagerPin(): Boolean = runCatching { sb.postgrest.rpc("has_manager_pin").decodeAs<Boolean>() }.getOrDefault(false)

    suspend fun setManagerPin(pin: String, oldPin: String?): PinResponse =
        sb.postgrest.rpc("set_manager_pin", buildJsonObject {
            put("p_pin", pin); if (!oldPin.isNullOrBlank()) put("p_old_pin", oldPin)
        }).decodeAs<PinResponse>()

    suspend fun cashCounts(): List<CashCount> = sb.postgrest.rpc("owner_cash_counts").decodeAs<List<CashCount>>()

    suspend fun closeDay(day: String, openingPaise: Long, countedPaise: Long, note: String): CashCount =
        sb.postgrest.rpc("owner_close_day", buildJsonObject {
            put("p_day", day); put("p_opening", openingPaise); put("p_counted", countedPaise); put("p_note", note)
        }).decodeAs<CashCount>()

    // ---- insights
    suspend fun stats(fromIso: String, toIso: String): Stats =
        sb.postgrest.rpc("owner_stats", buildJsonObject { put("p_from", fromIso); put("p_to", toIso) }).decodeAs<Stats>()

    suspend fun reviews(): List<ReviewRow> =
        sb.from("reviews").select().decodeList<ReviewRow>().sortedByDescending { it.createdAt }

    suspend fun setReviewNote(id: String, note: String) {
        sb.from("reviews").update({ set("reply", note.trim().ifBlank { null }) }) { filter { eq("id", id) } }
    }

    // ---- hotel status (works while the hotel is pending, rejected or on hold)
    suspend fun hotelStatus(): HotelStatus? = runCatching { sb.postgrest.rpc("my_hotel_status").decodeAs<HotelStatus>() }.getOrNull()

    // ---- settings
    suspend fun restaurant(id: String): Restaurant =
        sb.from("restaurants").select { filter { eq("id", id) } }.decodeSingle<Restaurant>()

    suspend fun saveProfile(id: String, name: String, address: String, phone: String, fssai: String) {
        sb.from("restaurants").update({
            set("name", name.trim())
            set("address", address.trim().ifBlank { null })
            set("phone", phone.trim().ifBlank { null })
            set("fssai", fssai.trim().ifBlank { null })
        }) { filter { eq("id", id) } }
    }

    suspend fun saveBilling(id: String, gstMode: String, gstRate: Double, gstin: String, serviceChargePct: Double, upi: String) {
        sb.from("restaurants").update({
            set("gst_mode", gstMode)
            set("gst_rate", gstRate)
            set("gstin", gstin.trim().uppercase().ifBlank { null })
            set("service_charge_pct", serviceChargePct)
            set("upi_id", upi.trim().ifBlank { null })
        }) { filter { eq("id", id) } }
    }

    suspend fun setMode(id: String, mode: String) {
        sb.from("restaurants").update({ set("mode", mode) }) { filter { eq("id", id) } }
    }

    suspend fun saveOnlineOrderSettings(
        id: String,
        enabled: Boolean,
        delivery: Boolean,
        pickup: Boolean,
        upi: Boolean,
        cod: Boolean,
        deliveryFeePaise: Int,
        codFeePaise: Int,
    ) {
        sb.from("restaurants").update({
            set("online_orders_enabled", enabled)
            set("online_delivery_enabled", delivery)
            set("online_pickup_enabled", pickup)
            set("online_upi_enabled", upi)
            set("online_cod_enabled", cod)
            set("online_delivery_fee", deliveryFeePaise)
            set("online_cod_fee", codFeePaise)
        }) { filter { eq("id", id) } }
    }

    suspend fun setLanguages(id: String, languages: List<String>) {
        sb.from("restaurants").update({ set("languages", languages) }) { filter { eq("id", id) } }
    }

    suspend fun setLogo(id: String, url: String) {
        sb.from("restaurants").update({ set("logo_url", url) }) { filter { eq("id", id) } }
    }

    suspend fun uploadLogo(restaurantId: String, bytes: ByteArray): String = MenuRepository(sb, restaurantId).uploadImage(bytes)

    fun email(): String? = sb.auth.currentUserOrNull()?.email

    suspend fun changePassword(newPassword: String) {
        sb.auth.updateUser { password = newPassword }
    }

    // ---- offers
    suspend fun coupons(restaurantId: String): List<CouponRow> =
        sb.from("coupons").select { filter { eq("restaurant_id", restaurantId) } }.decodeList<CouponRow>().sortedBy { it.code }

    suspend fun addCoupon(restaurantId: String, code: String, type: String, value: Int, minBillPaise: Int, maxUses: Int?) {
        sb.from("coupons").insert(NewCoupon(restaurantId, code.trim().uppercase(), type, value, minBillPaise, maxUses))
    }

    suspend fun updateCoupon(restaurantId: String, id: String, code: String, type: String, value: Int, minBillPaise: Int, maxUses: Int?) {
        sb.from("coupons").update({
            set("code", code.trim().uppercase())
            set("type", type)
            set("value", value)
            set("min_bill", minBillPaise)
            set("max_uses", maxUses)
        }) { filter { eq("id", id); eq("restaurant_id", restaurantId) } }
    }

    suspend fun setCouponActive(restaurantId: String, id: String, active: Boolean) {
        sb.from("coupons").update({ set("active", active) }) { filter { eq("id", id); eq("restaurant_id", restaurantId) } }
    }

    suspend fun deleteCoupon(restaurantId: String, id: String) {
        sb.from("coupons").delete { filter { eq("id", id); eq("restaurant_id", restaurantId) } }
    }

    private fun today(): String = LocalDate.now(ist).toString()

    suspend fun todayAnnouncement(): AnnouncementRow? =
        sb.from("announcements").select { filter { eq("show_date", today()) } }.decodeList<AnnouncementRow>().lastOrNull()

    suspend fun setAnnouncement(restaurantId: String, text: String) {
        sb.from("announcements").delete { filter { eq("show_date", today()) } }
        if (text.isNotBlank()) sb.from("announcements").insert(NewAnnouncement(restaurantId, text.trim(), today()))
    }

    // ---- staff logins (waiter, kitchen)
    suspend fun staff(): List<StaffRow> = sb.postgrest.rpc("owner_staff").decodeAs<List<StaffRow>>()

    private suspend fun staffCall(body: JsonObject): AdminResult {
        val resp = sb.functions.invoke("staff-admin") {
            method = HttpMethod.Post
            contentType(ContentType.Application.Json)
            setBody(body.toString())
        }
        return json.decodeFromString<AdminResult>(resp.bodyAsText())
    }

    suspend fun createStaff(name: String, email: String, password: String, role: String): AdminResult =
        staffCall(buildJsonObject { put("action", "create"); put("name", name); put("email", email); put("password", password); put("role", role) })

    suspend fun setStaffActive(userId: String, active: Boolean): AdminResult =
        staffCall(buildJsonObject { put("action", "set_active"); put("user_id", userId); put("active", active) })

    suspend fun resetStaffPassword(userId: String, password: String): AdminResult =
        staffCall(buildJsonObject { put("action", "reset_password"); put("user_id", userId); put("new_password", password) })

    suspend fun deleteStaff(userId: String): AdminResult =
        staffCall(buildJsonObject { put("action", "delete"); put("user_id", userId) })
}
