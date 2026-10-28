package com.xcloak.qrdine.ui

import android.content.Context
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.xcloak.qrdine.Diagnostics
import com.xcloak.qrdine.QrDineApp
import com.xcloak.qrdine.data.CouponRow
import com.xcloak.qrdine.data.ImageUtil
import com.xcloak.qrdine.data.Restaurant
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class SettingsUi(
    val loading: Boolean = true,
    val busy: Boolean = false,
    val restaurant: Restaurant? = null,
    val coupons: List<CouponRow> = emptyList(),
    val announcement: String = "",
    val onlineCodAvailable: Boolean = false,
    val email: String? = null,
    val message: String? = null,
)

class SettingsViewModel(private val restaurantId: String) : ViewModel() {
    private val repo = QrDineApp.container.owner
    private val _state = MutableStateFlow(SettingsUi())
    val state: StateFlow<SettingsUi> = _state

    init { load() }

    fun load() {
        viewModelScope.launch {
            try {
                _state.update {
                    it.copy(
                        loading = false, restaurant = repo.restaurant(restaurantId), coupons = repo.coupons(restaurantId),
                        announcement = repo.todayAnnouncement()?.text.orEmpty(), email = repo.email(),
                        onlineCodAvailable = repo.onlineCodAvailable(),
                    )
                }
            } catch (e: Exception) {
                Diagnostics.record(QrDineApp.appContext, e, "settings")
                _state.update {
                    it.copy(
                        loading = false,
                        email = repo.email(),
                        message = (e.message ?: "Could not load settings").take(160),
                    )
                }
            }
        }
    }

    private fun friendly(e: Exception): String {
        val m = e.message ?: return "Something went wrong"
        return when {
            "duplicate key" in m -> "That already exists."
            "gstin" in m && "check" in m -> "A GSTIN has exactly 15 characters."
            "weak" in m.lowercase() || "password" in m.lowercase() -> m.take(160)
            else -> m.take(160)
        }
    }

    private fun act(ok: String?, block: suspend (rid: String) -> Unit) {
        val rid = _state.value.restaurant?.id ?: return
        _state.update { it.copy(busy = true) }
        viewModelScope.launch {
            try {
                block(rid)
                _state.update { it.copy(busy = false, message = ok) }
                load()
                QrDineApp.container.ordersStore.refresh()
            } catch (e: Exception) {
                Diagnostics.record(QrDineApp.appContext, e, "settings")
                _state.update { it.copy(busy = false, message = friendly(e)) }
            }
        }
    }

    fun saveProfile(name: String, address: String, phone: String, fssai: String) =
        act("Saved") { repo.saveProfile(it, name, address, phone, fssai) }

    fun saveBilling(gstMode: String, gstRate: Double, gstin: String, serviceCharge: Double, upi: String) =
        act("Saved") { repo.saveBilling(it, gstMode, gstRate, gstin, serviceCharge, upi) }

    fun setMode(mode: String) = act("Saved") { repo.setMode(it, mode) }

    fun saveOnlineOrderSettings(
        enabled: Boolean,
        delivery: Boolean,
        pickup: Boolean,
        upi: Boolean,
        cod: Boolean,
        deliveryFeePaise: Int,
        codFeePaise: Int,
    ) = act("Online ordering settings saved") {
        repo.saveOnlineOrderSettings(it, enabled, delivery, pickup, upi, cod, deliveryFeePaise, codFeePaise)
    }

    fun submitOnlineApplication(note: String) = act("Application submitted for platform review") {
        repo.submitOnlineApplication(note)
    }

    fun uploadLogo(context: Context, uri: Uri) = act("Photo updated") { rid ->
        val bytes = withContext(Dispatchers.IO) { ImageUtil.compress(context, uri) }
        repo.setLogo(rid, repo.uploadLogo(rid, bytes))
    }

    fun changePassword(newPassword: String) = act("Password changed") { repo.changePassword(newPassword) }

    fun addCoupon(code: String, type: String, value: Int, minBillPaise: Int, maxUses: Int?) =
        act("Coupon added") { repo.addCoupon(it, code, type, value, minBillPaise, maxUses) }

    fun updateCoupon(coupon: CouponRow, code: String, type: String, value: Int, minBillPaise: Int, maxUses: Int?) =
        act("Coupon updated") { rid -> repo.updateCoupon(rid, coupon.id, code, type, value, minBillPaise, maxUses) }

    fun setCouponActive(c: CouponRow, active: Boolean) = act(null) { rid -> repo.setCouponActive(rid, c.id, active) }

    fun deleteCoupon(coupon: CouponRow) = act(
        if (coupon.usedCount > 0) "Coupon deactivated and kept for bill history" else "Coupon deleted",
    ) { rid ->
        if (coupon.usedCount > 0) repo.setCouponActive(rid, coupon.id, false) else repo.deleteCoupon(rid, coupon.id)
    }

    fun saveAnnouncement(text: String) = act(if (text.isBlank()) "Announcement removed" else "Announcement saved") { repo.setAnnouncement(it, text) }

    fun messageShown() = _state.update { it.copy(message = null) }

    class Factory(private val restaurantId: String) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T = SettingsViewModel(restaurantId) as T
    }
}
