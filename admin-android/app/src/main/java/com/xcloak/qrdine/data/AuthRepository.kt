package com.xcloak.qrdine.data

import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.auth.auth
import io.github.jan.supabase.auth.providers.builtin.Email
import io.github.jan.supabase.postgrest.from
import io.github.jan.supabase.postgrest.postgrest
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

class AuthRepository(private val sb: SupabaseClient) {
    val status get() = sb.auth.sessionStatus

    suspend fun signIn(email: String, password: String) {
        sb.auth.signInWith(Email) {
            this.email = email.trim()
            this.password = password
        }
    }

    /** Returns true when a session started; false when the project requires email confirmation first. */
    suspend fun signUp(email: String, password: String): Boolean {
        sb.auth.signUpWith(Email) {
            this.email = email.trim()
            this.password = password
        }
        return sb.auth.currentSessionOrNull() != null
    }

    suspend fun signOut() = sb.auth.signOut()

    /** restaurant_id comes from the access-token hook; null until the user has created a restaurant. */
    fun restaurantId(): String? =
        sb.auth.currentAccessTokenOrNull()?.let { Jwt.claim(it, "restaurant_id") }

    /** Submits the hotel for approval. The new token carries restaurant_id and the "pending" status. */
    suspend fun createRestaurant(name: String, phone: String, address: String) {
        sb.postgrest.rpc("create_restaurant", buildJsonObject {
            put("p_name", name.trim())
            if (phone.isNotBlank()) put("p_phone", phone.trim())
            if (address.isNotBlank()) put("p_address", address.trim())
        })
        sb.auth.refreshCurrentSession()
    }

    /** Re-issues the token so the claims (owner / blocked / platform admin) are recalculated. */
    suspend fun refresh() { sb.auth.refreshCurrentSession() }

    suspend fun restaurant(): Restaurant =
        sb.from("restaurants").select().decodeSingle<Restaurant>()
}
