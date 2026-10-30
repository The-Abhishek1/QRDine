package com.xcloak.qrdine.data

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
@Serializable
data class OnlineCustomerProfile(
    @SerialName("full_name") val fullName: String,
    val phone: String,
    val address: String,
    @SerialName("postal_code") val postalCode: String,
)

@Serializable
data class OnlineOrderInputItem(
    @SerialName("dish_id") val dishId: String,
    val qty: Int,
    @SerialName("variant_id") val variantId: String?,
    @SerialName("addon_ids") val addonIds: List<String>,
    val instructions: String,
)

@Serializable
data class OnlineOrderCreated(
    @SerialName("order_id") val orderId: String,
    val duplicate: Boolean,
    val total: Int,
)

@Serializable
data class OnlineOrder(
    val id: String,
    @SerialName("restaurant_id") val restaurantId: String,
    @SerialName("customer_id") val customerId: String,
    @SerialName("client_key") val clientKey: String = "",
    val status: String,
    val fulfilment: String,
    @SerialName("payment_method") val paymentMethod: String,
    @SerialName("payment_status") val paymentStatus: String,
    @SerialName("customer_name") val customerName: String,
    @SerialName("customer_phone") val customerPhone: String,
    @SerialName("customer_note") val customerNote: String? = null,
    @SerialName("delivery_address") val deliveryAddress: String? = null,
    @SerialName("postal_code") val postalCode: String? = null,
    @SerialName("payment_reference") val paymentReference: String? = null,
    @SerialName("items_snapshot") val items: List<OnlineOrderItem> = emptyList(),
    val subtotal: Int,
    @SerialName("offer_discount") val offerDiscount: Int = 0,
    @SerialName("delivery_fee") val deliveryFee: Int,
    @SerialName("cod_fee") val codFee: Int,
    val total: Int,
    @SerialName("reject_reason") val rejectReason: String? = null,
    @SerialName("eta_minutes") val etaMinutes: Int? = null,
    @SerialName("customer_rating") val customerRating: Int? = null,
    @SerialName("customer_feedback") val customerFeedback: String? = null,
    @SerialName("created_at") val createdAt: String,
    @SerialName("accepted_at") val acceptedAt: String? = null,
    @SerialName("updated_at") val updatedAt: String = "",
)

@Serializable
data class OnlineOrderItem(
    @SerialName("dish_id") val dishId: String = "",
    val name: String,
    val qty: Int,
    @SerialName("unit_price") val unitPrice: Int,
    @SerialName("variant_name") val variantName: String? = null,
    val addons: List<OnlineOrderAddon> = emptyList(),
    val instructions: String? = null,
    @SerialName("offer_free_qty") val offerFreeQty: Int = 0,
)

@Serializable
data class OnlineOrderAddon(
    val id: String,
    val name: String,
    val price: Int,
)

@Serializable
data class OnlineMenuResponse(
    val restaurant: OnlineRestaurant,
    val menu: OnlineMenu,
)

@Serializable
data class OnlineMenu(
    val categories: List<OnlineMenuCategory> = emptyList(),
    val dishes: List<OnlineMenuDish> = emptyList(),
    val announcement: String? = null,
    val offers: List<OnlineOffer> = emptyList(),
)

@Serializable
data class OnlineOffer(
    val id: String,
    val name: String,
    @SerialName("buy_qty") val buyQty: Int,
    @SerialName("get_qty") val getQty: Int,
    @SerialName("buy_dish_id") val buyDishId: String,
    @SerialName("reward_dish_id") val rewardDishId: String,
)

@Serializable
data class OnlineMenuCategory(
    val id: String,
    val name: String,
    val sort: Int = 0,
    @SerialName("name_i18n") val nameI18n: Map<String, String>? = null,
    @SerialName("avail_from") val availFrom: String? = null,
    @SerialName("avail_to") val availTo: String? = null,
    @SerialName("open_now") val openNow: Boolean = true,
)

@Serializable
data class OnlineMenuDish(
    val id: String,
    @SerialName("category_id") val categoryId: String,
    val name: String,
    val description: String? = null,
    @SerialName("image_url") val imageUrl: String? = null,
    @SerialName("base_price") val basePrice: Int,
    @SerialName("original_price") val originalPrice: Int? = null,
    @SerialName("is_veg") val isVeg: Boolean = true,
    @SerialName("is_available") val isAvailable: Boolean = true,
    @SerialName("online_available") val onlineAvailable: Boolean = true,
    @SerialName("stock_left") val stockLeft: Int? = null,
    val variants: List<OnlineVariant> = emptyList(),
    @SerialName("addon_groups") val addonGroups: List<OnlineAddonGroup> = emptyList(),
)

@Serializable
data class OnlineVariant(
    val id: String,
    val name: String,
    val price: Int,
    @SerialName("original_price") val originalPrice: Int? = null,
)

@Serializable
data class OnlineAddonGroup(
    val id: String,
    val name: String,
    @SerialName("min_select") val minSelect: Int = 0,
    @SerialName("max_select") val maxSelect: Int = 1,
    val addons: List<OnlineAddon> = emptyList(),
)

@Serializable
data class OnlineAddon(
    val id: String,
    val name: String,
    val price: Int,
    @SerialName("is_available") val isAvailable: Boolean = true,
)

data class OnlineCartLine(
    val key: String,
    val dishId: String,
    val name: String,
    val qty: Int,
    val unitPrice: Int,
    val variantId: String?,
    val variantName: String?,
    val addonIds: List<String>,
    val addonNames: List<String>,
)
