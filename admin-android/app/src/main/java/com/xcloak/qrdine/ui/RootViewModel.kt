package com.xcloak.qrdine.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.xcloak.qrdine.data.SignInRoutingState
import com.xcloak.qrdine.QrDineApp
import com.xcloak.qrdine.data.Jwt
import io.github.jan.supabase.auth.status.SessionStatus
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn

sealed interface RootState {
    data object Loading : RootState
    data object SignedOut : RootState
    data object NeedsRestaurant : RootState
    /** The SaaS owner: a separate area of the same app. */
    data object Platform : RootState
    data object Customer : RootState
    /** A hotel that exists but may not operate yet: pending approval, rejected, or on hold. */
    data class Blocked(val status: String) : RootState
    data class Ready(val restaurantId: String, val role: String) : RootState
}

class RootViewModel : ViewModel() {
    private val auth = QrDineApp.container.auth

    val state: StateFlow<RootState> = combine(auth.status, auth.signInRoutingState) { sessionStatus, routingState ->
        when (routingState) {
            SignInRoutingState.VALIDATING -> RootState.Loading
            SignInRoutingState.REJECTED -> RootState.SignedOut
            SignInRoutingState.READY -> when (sessionStatus) {
                is SessionStatus.Authenticated -> {
                    val token = sessionStatus.session.accessToken
                    val role = Jwt.claim(token, "app_role")
                    val rid = Jwt.claim(token, "restaurant_id")
                    val customerMetadata = sessionStatus.session.user?.userMetadata
                        ?.get("account_type")?.jsonPrimitive?.contentOrNull == "customer"
                    when {
                        role == "platform_admin" -> RootState.Platform
                        role == "customer" -> RootState.Customer
                        (role == "owner" || role == "waiter" || role == "kitchen") && rid != null -> RootState.Ready(rid, role)
                        role == "owner_blocked" -> RootState.Blocked(Jwt.claim(token, "hotel_status") ?: "pending")
                        role == null && customerMetadata -> RootState.Customer
                        else -> RootState.NeedsRestaurant
                    }
                }
                is SessionStatus.NotAuthenticated -> RootState.SignedOut
                else -> RootState.Loading
            }
        }
    }.stateIn(viewModelScope, SharingStarted.Eagerly, RootState.Loading)
}
