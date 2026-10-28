package com.xcloak.qrdine.data

import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.functions.functions
import io.ktor.client.statement.bodyAsText
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
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
}
