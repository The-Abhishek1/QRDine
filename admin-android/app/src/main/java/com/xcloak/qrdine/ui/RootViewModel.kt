package com.xcloak.qrdine.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.xcloak.qrdine.QrDineApp
import com.xcloak.qrdine.data.Jwt
import io.github.jan.supabase.auth.status.SessionStatus
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

sealed interface RootState {
    data object Loading : RootState
    data object SignedOut : RootState
    data object NeedsRestaurant : RootState
    /** The SaaS owner: a separate area of the same app. */
    data object Platform : RootState
    /** A hotel that exists but may not operate yet: pending approval, rejected, or on hold. */
    data class Blocked(val status: String) : RootState
    data class Ready(val restaurantId: String, val role: String) : RootState
}

class RootViewModel : ViewModel() {
    private val auth = QrDineApp.container.auth

    val state: StateFlow<RootState> = auth.status.map { s ->
        when (s) {
            is SessionStatus.Authenticated -> {
                val token = s.session.accessToken
                val role = Jwt.claim(token, "app_role")
                val rid = Jwt.claim(token, "restaurant_id")
                when {
                    role == "platform_admin" -> RootState.Platform
                    (role == "owner" || role == "waiter" || role == "kitchen") && rid != null -> RootState.Ready(rid, role)
                    role == "owner_blocked" -> RootState.Blocked(Jwt.claim(token, "hotel_status") ?: "pending")
                    else -> RootState.NeedsRestaurant
                }
            }
            is SessionStatus.NotAuthenticated -> RootState.SignedOut
            else -> RootState.Loading
        }
    }.stateIn(viewModelScope, SharingStarted.Eagerly, RootState.Loading)
}
