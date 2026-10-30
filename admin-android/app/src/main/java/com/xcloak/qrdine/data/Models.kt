package com.xcloak.qrdine.data

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class Restaurant(
    val id: String,
    val name: String,
    val address: String? = null,
    val phone: String? = null,
    val gstin: String? = null,
    val fssai: String? = null,
    @SerialName("gst_mode") val gstMode: String = "none",
    @SerialName("gst_rate") val gstRate: Double = 5.0,
    @SerialName("service_charge_pct") val serviceChargePct: Double = 0.0,
    @SerialName("upi_id") val upiId: String? = null,
    @SerialName("logo_url") val logoUrl: String? = null,
    val mode: String = "staff_activated",
    @SerialName("sub_status") val subStatus: String = "trial",
    @SerialName("trial_ends_at") val trialEndsAt: String? = null,
    @SerialName("is_open") val isOpen: Boolean = false,
    val languages: List<String> = emptyList(),
    @SerialName("online_orders_enabled") val onlineOrdersEnabled: Boolean = false,
    @SerialName("online_delivery_enabled") val onlineDeliveryEnabled: Boolean = false,
    @SerialName("online_pickup_enabled") val onlinePickupEnabled: Boolean = false,
    @SerialName("online_upi_enabled") val onlineUpiEnabled: Boolean = false,
    @SerialName("online_cod_enabled") val onlineCodEnabled: Boolean = false,
    @SerialName("online_delivery_fee") val onlineDeliveryFee: Int = 0,
    @SerialName("online_cod_fee") val onlineCodFee: Int = 0,
    @SerialName("online_application_status") val onlineApplicationStatus: String = "not_applied",
    @SerialName("online_application_note") val onlineApplicationNote: String? = null,
    @SerialName("online_application_review") val onlineApplicationReview: String? = null,
)

@Serializable
data class OnlineRestaurant(
    val id: String,
    val name: String,
    val address: String? = null,
    val phone: String? = null,
    @SerialName("logo_url") val logoUrl: String? = null,
    @SerialName("upi_id") val upiId: String? = null,
    @SerialName("online_delivery_enabled") val deliveryEnabled: Boolean = false,
    @SerialName("online_pickup_enabled") val pickupEnabled: Boolean = false,
    @SerialName("online_delivery_fee") val deliveryFee: Int = 0,
    @SerialName("online_cod_enabled") val codEnabled: Boolean = false,
    @SerialName("online_cod_fee") val codFee: Int = 0,
    @SerialName("online_upi_enabled") val upiEnabled: Boolean = false,
)

@Serializable
data class Category(
    val id: String,
    @SerialName("restaurant_id") val restaurantId: String,
    val name: String,
    val kind: String = "food",
    val sort: Int = 0,
    @SerialName("deleted_at") val deletedAt: String? = null,
    @SerialName("name_i18n") val nameI18n: Map<String, String>? = null,
    @SerialName("avail_from") val availFrom: String? = null,
    @SerialName("avail_to") val availTo: String? = null,
)

@Serializable
data class NewCategory(
    @SerialName("restaurant_id") val restaurantId: String,
    val name: String,
    val kind: String,
    val sort: Int,
    @SerialName("name_i18n") val nameI18n: Map<String, String>? = null,
)

@Serializable
data class Dish(
    val id: String,
    @SerialName("restaurant_id") val restaurantId: String,
    @SerialName("category_id") val categoryId: String,
    val name: String,
    val description: String? = null,
    @SerialName("image_url") val imageUrl: String? = null,
    @SerialName("base_price") val basePrice: Int,
    @SerialName("original_price") val originalPrice: Int? = null,
    @SerialName("is_veg") val isVeg: Boolean = true,
    @SerialName("is_available") val isAvailable: Boolean = true,
    @SerialName("online_available") val onlineAvailable: Boolean = true,
    val sort: Int = 0,
    @SerialName("deleted_at") val deletedAt: String? = null,
    @SerialName("stock_qty") val stockQty: Int? = null,
    @SerialName("name_i18n") val nameI18n: Map<String, String>? = null,
    @SerialName("description_i18n") val descriptionI18n: Map<String, String>? = null,
)

@Serializable
data class NewDish(
    @SerialName("restaurant_id") val restaurantId: String,
    @SerialName("category_id") val categoryId: String,
    val name: String,
    val description: String?,
    @SerialName("image_url") val imageUrl: String?,
    @SerialName("base_price") val basePrice: Int,
    @SerialName("is_veg") val isVeg: Boolean,
    @SerialName("stock_qty") val stockQty: Int? = null,
    @SerialName("name_i18n") val nameI18n: Map<String, String>? = null,
    @SerialName("description_i18n") val descriptionI18n: Map<String, String>? = null,
    @SerialName("original_price") val originalPrice: Int? = null,
)

/** What the editor screen collects. Price is in paise. */
data class DishForm(
    val name: String,
    val description: String,
    val pricePaise: Int,
    val isVeg: Boolean,
    val categoryId: String,
    val stockQty: Int? = null,
    val nameI18n: Map<String, String>? = null,
    val descI18n: Map<String, String>? = null,
    val originalPricePaise: Int? = null,
)

data class CsvDish(
    val category: String,
    val categoryKind: String?,
    val categoryI18n: Map<String, String>,
    val name: String,
    val nameI18n: Map<String, String>,
    val pricePaise: Int,
    val originalPricePaise: Int?,
    val isVeg: Boolean,
    val description: String,
    val descriptionI18n: Map<String, String>,
    val variantName: String?,
    val variantPricePaise: Int?,
    val variantOriginalPricePaise: Int?,
)

@Serializable
data class DiningTable(
    val id: String,
    @SerialName("restaurant_id") val restaurantId: String,
    val label: String,
    @SerialName("qr_key_version") val qrKeyVersion: Int = 1,
    val status: String = "closed",
    @SerialName("active_session_id") val activeSessionId: String? = null,
)

@Serializable
data class NewTable(
    @SerialName("restaurant_id") val restaurantId: String,
    val label: String,
)

@Serializable
data class QrItem(
    @SerialName("table_id") val tableId: String,
    val label: String,
    val url: String,
)

@Serializable
data class QrSheetResponse(val tables: List<QrItem>)

@Serializable
data class OrderRow(
    val id: String,
    @SerialName("restaurant_id") val restaurantId: String,
    @SerialName("session_id") val sessionId: String,
    @SerialName("order_type") val orderType: String,
    val status: String,
    @SerialName("eta_minutes") val etaMinutes: Int? = null,
    val note: String? = null,
    @SerialName("created_at") val createdAt: String,
    @SerialName("ready_at") val readyAt: String? = null,
    val source: String = "guest",
)

@Serializable
data class AddonSnap(val name: String = "", val price: Int = 0)

@Serializable
data class OrderItemRow(
    val id: String,
    @SerialName("order_id") val orderId: String,
    @SerialName("name_snapshot") val name: String,
    @SerialName("variant_snapshot") val variant: String? = null,
    val addons: List<AddonSnap> = emptyList(),
    val qty: Int,
    @SerialName("unit_price") val unitPrice: Int,
    val instructions: String? = null,
    val cancelled: Boolean = false,
    @SerialName("cancel_reason") val cancelReason: String? = null,
)

/** "Paneer (Half) + Cheese", the same text the bill shows. */
fun OrderItemRow.label(): String =
    name + (variant?.let { " ($it)" } ?: "") + (if (addons.isEmpty()) "" else " + " + addons.joinToString(", ") { it.name })

@Serializable
data class ServiceRequestRow(
    val id: String,
    @SerialName("session_id") val sessionId: String,
    val type: String,
    val status: String,
    @SerialName("created_at") val createdAt: String,
)

@Serializable
data class SessionRow(
    val id: String,
    @SerialName("table_id") val tableId: String,
)

@Serializable
data class NewDevice(
    @SerialName("restaurant_id") val restaurantId: String,
    @SerialName("fcm_token") val fcmToken: String,
)

@Serializable data class BillLine(val name: String, val qty: Long, val price: Long, val amount: Long)
@Serializable
data class BillItem(
    val id: String = "", val name: String, val qty: Int, val price: Int,
    val cancelled: Boolean = false, val reason: String? = null,
)
@Serializable
data class BillOrder(
    val id: String, val status: String, val type: String, val eta: Int? = null, val ready: Boolean = false, val source: String = "guest",
    @SerialName("created_at") val createdAt: String, val items: List<BillItem> = emptyList(),
)

@Serializable
data class Bill(
    @SerialName("session_id") val sessionId: String,
    val table: String,
    val status: String,
    @SerialName("opened_at") val openedAt: String,
    val lines: List<BillLine> = emptyList(),
    val orders: List<BillOrder> = emptyList(),
    val subtotal: Long,
    val discount: Long = 0,
    val coupon: String? = null,
    @SerialName("coupon_discount") val couponDiscount: Long = 0,
    @SerialName("manual_discount") val manualDiscount: Long = 0,
    @SerialName("service_charge") val serviceCharge: Long = 0,
    @SerialName("service_charge_pct") val serviceChargePct: Double = 0.0,
    val tax: Long = 0,
    val cgst: Long = 0,
    val sgst: Long = 0,
    @SerialName("gst_rate") val gstRate: Double = 0.0,
    @SerialName("round_off") val roundOff: Long = 0,
    val total: Long,
)

@Serializable
data class CloseResult(@SerialName("invoice_id") val invoiceId: String, val fy: String, val number: Int, val total: Long)

@Serializable
data class ServingRow(
    @SerialName("session_id") val sessionId: String,
    val table: String,
    val status: String,
    @SerialName("opened_at") val openedAt: String,
    val total: Long,
    val items: Long,
    val pending: Int,
    val cooking: Int,
    val served: Int,
)

@Serializable data class DailyPoint(val day: String, val revenue: Long, val bills: Int)
@Serializable data class DishStat(val name: String, val qty: Long, val revenue: Long)
@Serializable data class HourStat(val hour: Int, val orders: Int)
@Serializable
data class Stats(
    val revenue: Long = 0, val bills: Int = 0, val discounts: Long = 0, val tax: Long = 0, val items: Long = 0,
    val cash: Long = 0, val upi: Long = 0,
    val daily: List<DailyPoint> = emptyList(), val dishes: List<DishStat> = emptyList(), val hours: List<HourStat> = emptyList(),
)

@Serializable
data class OnlineFeedback(
    val id: String,
    val rating: Int,
    val feedback: String? = null,
    @SerialName("created_at") val createdAt: String,
)

@Serializable
data class OnlineOrderInsights(
    val orders: Int = 0,
    val sales: Long = 0,
    val items: Long = 0,
    val dishes: List<DishStat> = emptyList(),
    val daily: List<DailyPoint> = emptyList(),
    @SerialName("ratings_count") val ratingsCount: Int = 0,
    @SerialName("average_rating") val averageRating: Double = 0.0,
    val feedback: List<OnlineFeedback> = emptyList(),
)

@Serializable
data class SnapRestaurant(
    val name: String = "", val address: String? = null, val gstin: String? = null,
    @SerialName("gst_mode") val gstMode: String = "none", val phone: String? = null, val fssai: String? = null,
)

@Serializable
data class InvoiceSnapshot(
    val restaurant: SnapRestaurant, val table: String,
    @SerialName("paid_at") val paidAt: String, val method: String, val bill: Bill,
)

@Serializable
data class InvoiceRow(
    val id: String, val fy: String, val number: Int,
    @SerialName("issued_at") val issuedAt: String, val snapshot: InvoiceSnapshot,
)

@Serializable
data class ReviewRow(
    val id: String,
    @SerialName("rating_food") val ratingFood: Int? = null,
    @SerialName("rating_service") val ratingService: Int? = null,
    val comment: String? = null, val reply: String? = null,
    @SerialName("created_at") val createdAt: String,
)

@Serializable
data class CouponRow(
    val id: String, val code: String, val type: String, val value: Int,
    @SerialName("min_bill") val minBill: Int = 0,
    @SerialName("max_uses") val maxUses: Int? = null,
    @SerialName("used_count") val usedCount: Int = 0,
    val active: Boolean = true,
)

@Serializable
data class NewCoupon(
    @SerialName("restaurant_id") val restaurantId: String, val code: String, val type: String, val value: Int,
    @SerialName("min_bill") val minBill: Int, @SerialName("max_uses") val maxUses: Int?,
)

@Serializable data class AnnouncementRow(val id: String, val text: String, @SerialName("show_date") val showDate: String)
@Serializable
data class NewAnnouncement(
    @SerialName("restaurant_id") val restaurantId: String, val text: String, @SerialName("show_date") val showDate: String,
)

// ---------- platform admin (the SaaS owner) ----------
@Serializable
data class HotelRow(
    val id: String,
    val name: String,
    val phone: String? = null,
    val address: String? = null,
    val gstin: String? = null,
    @SerialName("owner_email") val ownerEmail: String? = null,
    val approval: String = "approved",
    @SerialName("on_hold") val onHold: Boolean = false,
    @SerialName("hold_reason") val holdReason: String? = null,
    @SerialName("admin_note") val adminNote: String? = null,
    val state: String = "trial",
    @SerialName("trial_ends_at") val trialEndsAt: String? = null,
    @SerialName("sub_expires_at") val subExpiresAt: String? = null,
    @SerialName("paid_through") val paidThrough: String? = null,
    @SerialName("created_at") val createdAt: String = "",
    @SerialName("approved_at") val approvedAt: String? = null,
    @SerialName("last_order_at") val lastOrderAt: String? = null,
    @SerialName("orders_30d") val orders30d: Int = 0,
    val tables: Int = 0,
    @SerialName("total_paid") val totalPaid: Long = 0,
)

@Serializable
data class OnlineApplication(
    val id: String,
    val name: String,
    val phone: String? = null,
    val address: String? = null,
    @SerialName("online_application_status") val status: String,
    @SerialName("online_application_note") val note: String? = null,
    @SerialName("online_application_submitted_at") val submittedAt: String? = null,
    @SerialName("online_application_review") val review: String? = null,
    @SerialName("online_orders_enabled") val ordersEnabled: Boolean = false,
    @SerialName("online_orders_admin_hold") val adminHold: Boolean = false,
    @SerialName("online_orders_admin_hold_reason") val adminHoldReason: String? = null,
    @SerialName("online_orders_paid_through") val paidThrough: String? = null,
)

@Serializable
data class OnlineOrderServicePayment(
    val id: String,
    @SerialName("restaurant_id") val restaurantId: String,
    val amount: Int,
    val months: Int,
    val method: String,
    @SerialName("paid_on") val paidOn: String,
    val reference: String? = null,
    val note: String? = null,
    @SerialName("period_start") val periodStart: String,
    @SerialName("period_end") val periodEnd: String,
    @SerialName("created_at") val createdAt: String,
)

@Serializable
data class PaymentRow(
    val id: String,
    @SerialName("restaurant_id") val restaurantId: String? = null,
    @SerialName("hotel_name") val hotelName: String,
    val amount: Long,
    val method: String,
    val reference: String? = null,
    val months: Int = 1,
    @SerialName("paid_on") val paidOn: String,
    val note: String? = null,
    @SerialName("created_at") val createdAt: String = "",
    @SerialName("receipt_no") val receiptNo: Long? = null,
    @SerialName("valid_from") val validFrom: String? = null,
    @SerialName("valid_until") val validUntil: String? = null,
)

@Serializable
data class PlatformSummary(
    val pending: Int = 0, val rejected: Int = 0, val hold: Int = 0, val trial: Int = 0, val active: Int = 0,
    val grace: Int = 0, val expired: Int = 0,
    @SerialName("expiring_soon") val expiringSoon: Int = 0,
    @SerialName("collected_month") val collectedMonth: Long = 0,
    @SerialName("collected_total") val collectedTotal: Long = 0,
)

@Serializable
data class SupportInfo(
    val phone: String? = null, val email: String? = null,
    @SerialName("payment_info") val paymentInfo: String? = null,
    @SerialName("business_name") val businessName: String? = null,
    @SerialName("business_address") val businessAddress: String? = null,
    @SerialName("business_gstin") val businessGstin: String? = null,
    val broadcast: String? = null,
)

@Serializable
data class BroadcastMsg(val id: Long, val message: String)

@Serializable
data class HotelStatus(
    val state: String,
    val role: String = "owner",
    val note: String? = null,
    @SerialName("hotel_name") val hotelName: String = "",
    @SerialName("paid_through") val paidThrough: String? = null,
    @SerialName("days_left") val daysLeft: Int? = null,
    val support: SupportInfo = SupportInfo(),
    val broadcast: BroadcastMsg? = null,
)

@Serializable
data class ActivityRow(
    val id: Long,
    val action: String,
    @SerialName("hotel_name") val hotelName: String? = null,
    @SerialName("created_at") val createdAt: String = "",
)

@Serializable
data class DiagnosticReport(
    val id: String,
    @SerialName("restaurant_id") val restaurantId: String? = null,
    @SerialName("restaurant_name") val restaurantName: String? = null,
    val kind: String,
    @SerialName("error_type") val errorType: String,
    val area: String,
    @SerialName("app_version") val appVersion: String,
    @SerialName("os_version") val osVersion: String,
    @SerialName("device_model") val deviceModel: String,
    val frames: List<String> = emptyList(),
    @SerialName("occurred_at") val occurredAt: String,
    @SerialName("received_at") val receivedAt: String,
)

@Serializable
data class DiagnosticResponse(
    val ok: Boolean,
    val reports: List<DiagnosticReport> = emptyList(),
)

@Serializable
data class AdminResult(
    val ok: Boolean = false,
    val error: String? = null,
    @SerialName("restaurant_id") val restaurantId: String? = null,
)

// ---------- M6 ----------
@Serializable
data class DishVariant(
    val id: String, @SerialName("dish_id") val dishId: String, val name: String, val price: Int,
    @SerialName("original_price") val originalPrice: Int? = null,
)

@Serializable
data class AddonGroup(
    val id: String, val name: String,
    @SerialName("min_select") val minSelect: Int = 0, @SerialName("max_select") val maxSelect: Int = 1,
)

@Serializable
data class Addon(
    val id: String, @SerialName("group_id") val groupId: String, val name: String, val price: Int = 0,
    @SerialName("is_available") val isAvailable: Boolean = true,
)

@Serializable
data class Offer(
    val id: String, val name: String,
    @SerialName("buy_qty") val buyQty: Int,
    @SerialName("get_qty") val getQty: Int,
    @SerialName("buy_dish_id") val buyDishId: String,
    @SerialName("reward_dish_id") val rewardDishId: String,
    val active: Boolean = true,
)

@Serializable
data class DishAddonLink(@SerialName("dish_id") val dishId: String, @SerialName("group_id") val groupId: String)

@Serializable
data class StaffRow(
    @SerialName("user_id") val userId: String, val role: String, val name: String? = null,
    val active: Boolean = true, val email: String? = null,
)

@Serializable
data class CashCount(
    val id: String, val day: String,
    @SerialName("opening_float") val openingFloat: Long = 0, @SerialName("cash_sales") val cashSales: Long = 0,
    @SerialName("expected_cash") val expectedCash: Long = 0, @SerialName("counted_cash") val countedCash: Long = 0,
    val difference: Long = 0, val note: String? = null,
)

@Serializable data class CloseResponse(
    val error: String? = null, @SerialName("invoice_id") val invoiceId: String? = null,
    val fy: String? = null, val number: Int? = null, val total: Long? = null,
)
@Serializable data class PinResponse(val ok: Boolean = false, val error: String? = null)

@Serializable data class MonthAmount(val month: String, val amount: Long = 0)
@Serializable data class IdName(val id: String, val name: String, @SerialName("last_order_at") val lastOrderAt: String? = null)
@Serializable
data class PlatformInsights(
    val monthly: List<MonthAmount> = emptyList(),
    @SerialName("approved_total") val approvedTotal: Int = 0,
    @SerialName("ever_paid") val everPaid: Int = 0,
    @SerialName("trial_ended_unpaid") val trialEndedUnpaid: Int = 0,
    @SerialName("no_menu") val noMenu: List<IdName> = emptyList(),
    @SerialName("no_orders") val noOrders: List<IdName> = emptyList(),
    val quiet: List<IdName> = emptyList(),
)

@Serializable
data class HotelInfo(
    val name: String = "", val phone: String? = null, val address: String? = null, val gstin: String? = null,
    val mode: String = "staff_activated", @SerialName("gst_mode") val gstMode: String = "none",
    @SerialName("gst_rate") val gstRate: Double = 0.0, @SerialName("upi_id") val upiId: String? = null,
    @SerialName("is_open") val isOpen: Boolean = false, val languages: List<String> = emptyList(), val state: String = "",
)
@Serializable data class MenuCounts(val categories: Int = 0, val dishes: Int = 0, val unavailable: Int = 0)
@Serializable data class StaffBrief(val name: String? = null, val role: String = "", val active: Boolean = true)
@Serializable data class RecentOrder(val table: String = "", val status: String = "", @SerialName("created_at") val createdAt: String = "", val total: Long = 0)
@Serializable data class RecentBill(val fy: String = "", val number: Int = 0, val total: Long = 0, val table: String = "", @SerialName("paid_at") val paidAt: String = "")
@Serializable
data class HotelView(
    val hotel: HotelInfo = HotelInfo(), val serving: List<ServingRow> = emptyList(), val today: Stats = Stats(),
    val menu: MenuCounts = MenuCounts(), val staff: List<StaffBrief> = emptyList(),
    @SerialName("recent_orders") val recentOrders: List<RecentOrder> = emptyList(),
    @SerialName("recent_bills") val recentBills: List<RecentBill> = emptyList(),
)

@Serializable data class NewVariant(@SerialName("restaurant_id") val restaurantId: String, @SerialName("dish_id") val dishId: String, val name: String, val price: Int, @SerialName("original_price") val originalPrice: Int? = null)
@Serializable data class NewLink(@SerialName("restaurant_id") val restaurantId: String, @SerialName("dish_id") val dishId: String, @SerialName("group_id") val groupId: String)
@Serializable data class NewGroup(@SerialName("restaurant_id") val restaurantId: String, val name: String, @SerialName("min_select") val minSelect: Int, @SerialName("max_select") val maxSelect: Int)
@Serializable data class NewAddon(@SerialName("restaurant_id") val restaurantId: String, @SerialName("group_id") val groupId: String, val name: String, val price: Int)
@Serializable data class NewOffer(
    @SerialName("restaurant_id") val restaurantId: String, val name: String,
    @SerialName("buy_qty") val buyQty: Int, @SerialName("get_qty") val getQty: Int,
    @SerialName("buy_dish_id") val buyDishId: String, @SerialName("reward_dish_id") val rewardDishId: String,
)
