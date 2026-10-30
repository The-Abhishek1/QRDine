package com.xcloak.qrdine.ui

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.xcloak.qrdine.QrDineApp
import com.xcloak.qrdine.data.ActivityRow
import com.xcloak.qrdine.data.AdminResult
import com.xcloak.qrdine.data.HotelRow
import com.xcloak.qrdine.data.OnlineApplication
import com.xcloak.qrdine.data.OnlineOrderServicePayment
import com.xcloak.qrdine.data.DiagnosticReport
import com.xcloak.qrdine.data.PaymentRow
import com.xcloak.qrdine.data.PlatformSummary
import com.xcloak.qrdine.data.SupportInfo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

data class PlatformUi(
    val loading: Boolean = true,
    val busy: Boolean = false,
    val summary: PlatformSummary = PlatformSummary(),
    val hotels: List<HotelRow> = emptyList(),
    val onlineApplications: List<OnlineApplication> = emptyList(),
    val onlineOrderMonthlyFee: Int = 0,
    val onlineOrderPayments: List<OnlineOrderServicePayment> = emptyList(),
    val onlineBillingUnavailable: Boolean = false,
    val onlineCodAvailable: Boolean = false,
    val payments: List<PaymentRow> = emptyList(),
    val support: SupportInfo = SupportInfo(),
    val activity: List<ActivityRow> = emptyList(),
    val diagnostics: List<DiagnosticReport> = emptyList(),
    val diagnosticsUnavailable: Boolean = false,
    val email: String? = null,
    val message: String? = null,
    val shareFile: File? = null,
    val insights: com.xcloak.qrdine.data.PlatformInsights? = null,
    val viewing: com.xcloak.qrdine.data.HotelView? = null,
    val viewingName: String = "",
)

class PlatformViewModel : ViewModel() {
    private val repo = QrDineApp.container.platform
    private val owner = QrDineApp.container.owner
    private val _state = MutableStateFlow(PlatformUi(email = owner.email()))
    val state: StateFlow<PlatformUi> = _state

    init { refresh() }

    private fun friendly(e: Exception): String {
        val m = e.message ?: return "Something went wrong"
        return when {
            "not_platform_admin" in m -> "This account is not a platform admin."
            "hotel_not_found" in m -> "That hotel no longer exists."
            "reason_required" in m -> "Please give a reason."
            "bad_days" in m -> "Days must be between 0 and 365 (extend: 1 to 365)."
            "bad_method" in m -> "Pick cash, UPI or bank."
            "bad_amount" in m -> "Enter an amount above zero."
            "bad_months" in m -> "Months must be between 1 and 36."
            "bad_dates" in m -> "Enter valid dates."
            "payment_not_found" in m -> "That payment no longer exists."
            "bad_online_order_fee" in m -> "Monthly fee must be between ₹0 and ₹10,000."
            "online_order_fee_not_configured" in m -> "Set a monthly fee before recording a payment."
            "online_order_payment_insufficient" in m -> "The payment must cover the selected number of months."
            "online_application_required" in m -> "Approve the restaurant's online-order application first."
            else -> m.take(160)
        }
    }

    private fun adminError(code: String?): String = when (code) {
        "bad_email" -> "Enter a valid email address."
        "weak_password" -> "The password needs at least 8 characters."
        "name_required" -> "Enter the hotel name."
        "email_taken" -> "That email already has an account."
        "hotel_not_found" -> "That hotel no longer exists."
        "name_mismatch" -> "The name you typed does not match the hotel."
        "bad_days" -> "Trial days must be between 0 and 365."
        else -> "The action failed. Please try again."
    }

    fun refresh() {
        viewModelScope.launch {
            try {
                val diagnosticsResult = runCatching { repo.diagnostics() }
                val onlineBillingResult = runCatching {
                    repo.onlineOrderMonthlyFee() to repo.onlineOrderPayments()
                }
                _state.update {
                    it.copy(
                        loading = false, summary = repo.summary(), hotels = repo.hotels(), payments = repo.payments(),
                        onlineApplications = repo.onlineApplications(), onlineCodAvailable = repo.onlineCodAvailable(),
                        support = repo.support(), activity = repo.activity(), insights = runCatching { repo.insights() }.getOrNull(),
                        onlineOrderMonthlyFee = onlineBillingResult.getOrNull()?.first ?: 0,
                        onlineOrderPayments = onlineBillingResult.getOrNull()?.second.orEmpty(),
                        onlineBillingUnavailable = onlineBillingResult.isFailure,
                        diagnostics = diagnosticsResult.getOrDefault(emptyList()),
                        diagnosticsUnavailable = diagnosticsResult.isFailure,
                    )
                }
            } catch (e: Exception) {
                _state.update { it.copy(loading = false, message = friendly(e)) }
            }
        }
    }

    private fun act(ok: String?, block: suspend () -> Unit) {
        _state.update { it.copy(busy = true) }
        viewModelScope.launch {
            try {
                block()
                _state.update { it.copy(busy = false, message = ok) }
                refresh()
            } catch (e: Exception) {
                _state.update { it.copy(busy = false, message = friendly(e)) }
            }
        }
    }

    /** For Edge Function actions, which report expected failures as {ok:false,error}. */
    private fun actAdmin(ok: String, onDone: () -> Unit = {}, block: suspend () -> AdminResult) {
        _state.update { it.copy(busy = true) }
        viewModelScope.launch {
            try {
                val r = block()
                if (r.ok) { _state.update { it.copy(busy = false, message = ok) }; refresh(); onDone() }
                else _state.update { it.copy(busy = false, message = adminError(r.error)) }
            } catch (e: Exception) {
                _state.update { it.copy(busy = false, message = "Could not reach the server. Check your connection.") }
            }
        }
    }

    fun approve(h: HotelRow, trialDays: Int) = act("${h.name} approved") { repo.review(h.id, true, null, trialDays) }
    fun reject(h: HotelRow, reason: String) = act("${h.name} rejected") { repo.review(h.id, false, reason, 0) }
    fun reviewOnlineApplication(app: OnlineApplication, approve: Boolean, reason: String? = null) =
        act(if (approve) "Online orders approved for ${app.name}" else "Online application rejected for ${app.name}") {
            repo.reviewOnlineApplication(app.id, approve, reason)
        }
    fun setOnlineCodAvailable(enabled: Boolean) = act(
        if (enabled) "Cash on delivery is now available to approved restaurants" else "Cash on delivery disabled platform-wide",
    ) { repo.setOnlineCodAvailable(enabled) }
    fun setOnlineOrderMonthlyFee(amountPaise: Int) = act("Online-order monthly fee updated") {
        repo.setOnlineOrderMonthlyFee(amountPaise)
    }
    fun recordOnlineOrderPayment(rid: String, amountPaise: Int, months: Int, reference: String, note: String) =
        act("Online-order payment recorded") {
            repo.recordOnlineOrderPayment(rid, amountPaise, months, reference, note)
        }
    fun setOnlineOrderHold(app: OnlineApplication, hold: Boolean, reason: String? = null) =
        act(if (hold) "Online orders held for ${app.name}" else "Online-order hold released for ${app.name}") {
            repo.setOnlineOrderHold(app.id, hold, reason)
        }
    fun hold(h: HotelRow, hold: Boolean, reason: String?) = act(if (hold) "${h.name} is on hold" else "Hold released") { repo.setHold(h.id, hold, reason) }
    fun update(h: HotelRow, name: String, phone: String, address: String, gstin: String, note: String) =
        act("Saved") { repo.updateHotel(h.id, name, phone, address, gstin, note) }
    fun extend(h: HotelRow, days: Int) = act("Extended by $days days") { repo.extend(h.id, days) }
    fun setDates(h: HotelRow, trialEnd: String, subEnd: String?) = act("Dates updated") { repo.setDates(h.id, trialEnd, subEnd) }
    fun recordPayment(rid: String, amount: Long, method: String, ref: String, months: Int, paidOn: String, note: String) =
        act("Payment recorded") { repo.recordPayment(rid, amount, method, ref, months, paidOn, note) }
    fun deletePayment(p: PaymentRow) = act("Payment record removed") { repo.deletePayment(p.id) }
    fun saveSupport(phone: String, email: String, info: String, bizName: String, bizAddress: String, bizGstin: String) =
        act("Details saved") { repo.setSupport(phone, email, info, bizName, bizAddress, bizGstin) }
    fun broadcast(message: String, days: Int) = act(if (message.isBlank()) "Broadcast cleared" else "Broadcast sent to every hotel") { repo.setBroadcast(message, days) }
    fun say(m: String) = _state.update { it.copy(message = m) }

    fun viewHotel(h: HotelRow) {
        _state.update { it.copy(busy = true) }
        viewModelScope.launch {
            try {
                val v = repo.viewHotel(h.id)
                _state.update { it.copy(busy = false, viewing = v, viewingName = h.name) }
            } catch (e: Exception) { _state.update { it.copy(busy = false, message = friendly(e)) } }
        }
    }
    fun closeView() = _state.update { it.copy(viewing = null) }
    fun changePassword(pw: String) = act("Password changed") { owner.changePassword(pw) }

    fun addHotel(email: String, password: String, name: String, phone: String, address: String, days: Int) =
        actAdmin("$name added and approved") { repo.createHotel(email, password, name, phone, address, days) }
    fun resetPassword(h: HotelRow, pw: String) = actAdmin("Password reset for ${h.name}") { repo.resetPassword(h.id, pw) }
    fun deleteHotel(h: HotelRow, confirmName: String, onDone: () -> Unit) =
        actAdmin("${h.name} deleted", onDone) { repo.deleteHotel(h.id, confirmName) }

    private fun export(context: Context, fileName: String, csv: String) {
        viewModelScope.launch {
            val file = withContext(Dispatchers.IO) {
                File(File(context.cacheDir, "exports").apply { mkdirs() }, fileName).also { it.writeText(csv) }
            }
            _state.update { it.copy(shareFile = file) }
        }
    }

    fun exportHotels(context: Context) {
        val sb = StringBuilder("Hotel,Owner email,Phone,Address,GSTIN,Status,Applied on,Approved on,Trial ends,Paid through,Total paid (INR),Orders last 30 days,Last order,Tables,Admin note\n")
        for (h in _state.value.hotels) {
            sb.append(listOf(
                Csv.cell(h.name), Csv.cell(h.ownerEmail), Csv.cell(h.phone), Csv.cell(h.address), Csv.cell(h.gstin), Csv.cell(h.state),
                Csv.cell(dateLabel(h.createdAt)), Csv.cell(dateLabel(h.approvedAt)), Csv.cell(dateLabel(h.trialEndsAt)), Csv.cell(dateLabel(h.paidThrough)),
                Csv.rupees(h.totalPaid), h.orders30d.toString(), Csv.cell(h.lastOrderAt?.let { istTime(it) }), h.tables.toString(), Csv.cell(h.adminNote),
            ).joinToString(",")).append('\n')
        }
        export(context, "hotels.csv", sb.toString())
    }

    fun exportPayments(context: Context, rows: List<PaymentRow>) {
        val sb = StringBuilder("Date,Hotel,Method,Amount (INR),Months,Reference,Note\n")
        for (p in rows) {
            sb.append(listOf(Csv.cell(p.paidOn), Csv.cell(p.hotelName), Csv.cell(p.method), Csv.rupees(p.amount), p.months.toString(), Csv.cell(p.reference), Csv.cell(p.note)).joinToString(",")).append('\n')
        }
        export(context, "subscription-payments.csv", sb.toString())
    }

    fun shareConsumed() = _state.update { it.copy(shareFile = null) }
    fun messageShown() = _state.update { it.copy(message = null) }
}
