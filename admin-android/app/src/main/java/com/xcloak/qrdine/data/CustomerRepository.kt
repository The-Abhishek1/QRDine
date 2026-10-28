package com.xcloak.qrdine.data

import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.functions.functions
import io.github.jan.supabase.postgrest.postgrest
import io.ktor.client.statement.bodyAsText
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

@Serializable
private data class OnlineRestaurantResponse(val restaurants: List<OnlineRestaurant>)

class CustomerRepository(private val sb: SupabaseClient) {
    private val json = Json { ignoreUnknownKeys = true }

    suspend fun restaurants(): List<OnlineRestaurant> {
        val response = sb.functions.invoke("online-restaurants")
        return json.decodeFromString<OnlineRestaurantResponse>(response.bodyAsText()).restaurants
    }

    suspend fun menu(restaurantId: String): OnlineMenuResponse {
        val response = sb.functions.invoke(
            "online-menu",
            kotlinx.serialization.json.buildJsonObject {
                put("restaurant_id", kotlinx.serialization.json.JsonPrimitive(restaurantId))
            },
        )
        return json.decodeFromString<OnlineMenuResponse>(response.bodyAsText())
    }

    suspend fun profile(): OnlineCustomerProfile? {
        val result = sb.postgrest.rpc("get_customer_profile").decodeAs<JsonElement>()
        return if (result == JsonNull) null else
            json.decodeFromJsonElement(OnlineCustomerProfile.serializer(), result)
    }

    suspend fun saveProfile(profile: OnlineCustomerProfile) {
        sb.postgrest.rpc("save_customer_profile", buildJsonObject {
            put("p_full_name", profile.fullName)
            put("p_phone", profile.phone)
            put("p_address", profile.address)
            put("p_postal_code", profile.postalCode)
        })
    }

    suspend fun placeOrder(
        restaurantId: String,
        clientKey: String,
        fulfilment: String,
        payment: String,
        items: List<OnlineOrderInputItem>,
    ): OnlineOrderCreated = sb.postgrest.rpc("place_online_order", buildJsonObject {
        put("p_restaurant_id", restaurantId)
        put("p_client_key", clientKey)
        put("p_fulfilment", fulfilment)
        put("p_payment", payment)
        put("p_note", "")
        put("p_items", json.encodeToJsonElement(ListSerializer(OnlineOrderInputItem.serializer()), items))
    }).decodeAs<OnlineOrderCreated>()

    suspend fun submitPaymentReference(orderId: String, reference: String) {
        sb.postgrest.rpc("submit_online_payment_reference", buildJsonObject {
            put("p_order_id", orderId)
            put("p_reference", reference.trim())
        })
    }

    suspend fun orders(): List<OnlineOrder> =
        sb.postgrest.rpc("customer_online_orders", buildJsonObject { put("p_limit", 25) }).decodeAs()
}
