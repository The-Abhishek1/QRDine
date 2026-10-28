package com.xcloak.qrdine.ui

import android.widget.Toast
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.xcloak.qrdine.Diagnostics
import com.xcloak.qrdine.QrDineApp
import com.xcloak.qrdine.data.SignInIdentityException
import com.xcloak.qrdine.data.SignOutException
import com.xcloak.qrdine.data.CustomerAccountRequiredException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class AuthUiState(
    val loading: Boolean = false,
    val error: String? = null,
    val info: String? = null,
    val verificationPending: Boolean = false,
    val recoveryComplete: Boolean = false,
)

class AuthViewModel : ViewModel() {
    private val auth = QrDineApp.container.auth
    private val _state = MutableStateFlow(AuthUiState())
    val state: StateFlow<AuthUiState> = _state

    fun submit(email: String, password: String, signUp: Boolean, customerAccount: Boolean = false) {
        if (email.isBlank() || password.length < 8) {
            _state.value = AuthUiState(error = "Enter a valid email and a password of at least 8 characters.")
            return
        }
        _state.value = AuthUiState(loading = true)
        viewModelScope.launch {
            try {
                if (signUp) {
                    val started = auth.signUp(email, password, if (customerAccount) "customer" else null)
                    _state.value = if (started) AuthUiState()
                    else AuthUiState(
                        info = "We sent a confirmation link to your email. Confirm it before signing in.",
                        verificationPending = true,
                    )
                } else {
                    if (customerAccount) auth.signInCustomer(email, password) else auth.signIn(email, password)
                    _state.value = AuthUiState()
                }
            } catch (e: Exception) {
                Diagnostics.record(QrDineApp.appContext, e, "auth")
                _state.value = AuthUiState(
                    error = if (e is SignInIdentityException) {
                        if (e.localSessionCleared) {
                            "Sign-in was stopped because the authenticated account did not match the email entered."
                        } else {
                            "The authenticated account did not match the email entered, and we couldn't clear the session. Restart the app before trying another account."
                        }
                    } else if (e is CustomerAccountRequiredException) {
                        "That account is not a customer account. Use the restaurant sign-in option, or create a customer account."
                    } else authError(e),
                )
            }
        }
    }

    fun resendConfirmation(email: String) {
        if (email.isBlank()) { _state.update { it.copy(error = "Enter your email address first.") }; return }
        runAuthAction("A new confirmation link is on its way. Check your inbox.") {
            auth.resendConfirmation(email)
        }
    }

    fun requestPasswordReset(email: String) {
        if (email.isBlank()) { _state.update { it.copy(error = "Enter your email address first.") }; return }
        runAuthAction("If an account exists for that address, a password reset link is on its way.") {
            auth.requestPasswordReset(email)
        }
    }

    fun updateRecoveryPassword(password: String, confirmation: String) {
        if (password.length < 8) { _state.update { it.copy(error = "Use at least 8 characters for your new password.") }; return }
        if (password != confirmation) { _state.update { it.copy(error = "The passwords do not match.") }; return }
        _state.update { it.copy(loading = true, error = null, info = null) }
        viewModelScope.launch {
            try {
                auth.updatePassword(password)
                _state.update { it.copy(loading = false, recoveryComplete = true, info = "Your password has been updated.") }
            } catch (e: Exception) {
                Diagnostics.record(QrDineApp.appContext, e, "auth")
                _state.update { it.copy(loading = false, error = authError(e)) }
            }
        }
    }

    fun finishRecovery() {
        QrDineApp.passwordRecoveryPending.value = false
        _state.value = AuthUiState()
    }

    private fun runAuthAction(success: String, action: suspend () -> Unit) {
        _state.update { it.copy(loading = true, error = null, info = null) }
        viewModelScope.launch {
            try {
                action()
                _state.update { it.copy(loading = false, error = null, info = success) }
            } catch (e: Exception) {
                Diagnostics.record(QrDineApp.appContext, e, "auth")
                _state.update { it.copy(loading = false, error = authError(e), info = null) }
            }
        }
    }

    private fun authError(e: Exception): String {
        val message = e.message.orEmpty().lowercase()
        return when {
            "invalid login credentials" in message -> "Email or password is incorrect."
            "email not confirmed" in message -> "Confirm your email from the link we sent, then sign in."
            "already registered" in message || "user already exists" in message -> "An account already exists for this email. Sign in or reset its password."
            "password" in message && ("weak" in message || "least" in message) -> "Choose a stronger password with at least 8 characters."
            else -> "We couldn't complete that request. Check your connection and try again."
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
                Diagnostics.record(QrDineApp.appContext, e, "setup")
                _state.value = AuthUiState(error = authError(e))
            }
        }
    }

    fun signOut() {
        viewModelScope.launch {
            val ctx = QrDineApp.appContext
            runCatching { com.xcloak.qrdine.notify.Fcm.unregister(ctx) }   // this phone stops receiving pushes
            com.xcloak.qrdine.notify.AlertControl.stopAll(ctx)
            try {
                auth.signOut()
                _state.value = AuthUiState()
            } catch (e: CancellationException) {
                throw e
            } catch (e: SignOutException) {
                Diagnostics.record(ctx, e, "auth")
                val message = if (e.localSessionCleared) {
                    "Signed out on this device, but the server could not be reached to revoke the session."
                } else {
                    "We couldn't clear this device's saved sign-in. Try again before signing in as another account."
                }
                _state.value = AuthUiState(error = message)
                if (!e.localSessionCleared) Toast.makeText(ctx, message, Toast.LENGTH_LONG).show()
            } catch (e: Exception) {
                Diagnostics.record(ctx, e, "auth")
                _state.value = AuthUiState(error = "We couldn't sign out. Please try again.")
            }
        }
    }

    fun clearMessages() = _state.update { it.copy(error = null, info = null) }
}
