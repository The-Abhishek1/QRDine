package com.xcloak.qrdine.data

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

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
