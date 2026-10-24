package com.xcloak.qrdine.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.xcloak.qrdine.QrDineApp
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class AuthUiState(val loading: Boolean = false, val error: String? = null, val info: String? = null)

class AuthViewModel : ViewModel() {
    private val auth = QrDineApp.container.auth
    private val _state = MutableStateFlow(AuthUiState())
    val state: StateFlow<AuthUiState> = _state

    fun submit(email: String, password: String, signUp: Boolean) {
        if (email.isBlank() || password.length < 8) {
            _state.value = AuthUiState(error = "Enter a valid email and a password of at least 8 characters.")
            return
        }
        _state.value = AuthUiState(loading = true)
        viewModelScope.launch {
            try {
                if (signUp) {
                    val started = auth.signUp(email, password)
                    _state.value = if (started) AuthUiState()
                    else AuthUiState(info = "Check your email to confirm your account, then sign in.")
                } else {
                    auth.signIn(email, password)
                    _state.value = AuthUiState()
                }
            } catch (e: Exception) {
                _state.value = AuthUiState(error = e.message ?: "Something went wrong")
            }
        }
    }

    fun createRestaurant(name: String, phone: String, address: String) {
        if (name.isBlank()) { _state.value = AuthUiState(error = "Enter your restaurant name."); return }
        _state.value = AuthUiState(loading = true)
        viewModelScope.launch {
            try {
                auth.createRestaurant(name, phone, address)
                _state.value = AuthUiState()
            } catch (e: Exception) {
                _state.value = AuthUiState(error = e.message ?: "Could not create restaurant")
            }
        }
    }

    fun signOut() {
        viewModelScope.launch {
            val ctx = QrDineApp.appContext
            runCatching { com.xcloak.qrdine.notify.Fcm.unregister(ctx) }   // this phone stops receiving pushes
            com.xcloak.qrdine.notify.AlertControl.stopAll(ctx)
            runCatching { auth.signOut() }
        }
    }

    fun clearMessages() = _state.update { it.copy(error = null, info = null) }
}
