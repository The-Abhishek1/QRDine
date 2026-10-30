package com.xcloak.qrdine.data

import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.auth.auth
import io.github.jan.supabase.auth.OtpType
import io.github.jan.supabase.auth.providers.builtin.Email
import io.github.jan.supabase.postgrest.postgrest
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

const val AUTH_REDIRECT_URL = "qrdine://auth/callback"

class SignOutException(
    val localSessionCleared: Boolean,
    cause: Exception,
) : Exception("Sign-out could not be completed cleanly.", cause)

class SignInIdentityException(val localSessionCleared: Boolean) :
    Exception("The authenticated account did not match the sign-in email.")

class CustomerAccountRequiredException : Exception("Sign in with a customer account to browse restaurants.")
class RestaurantAccountRequiredException : Exception("Sign in with a restaurant account to manage a restaurant.")
class AccountTypeSessionClearException(cause: Exception) : Exception("Could not clear the mismatched account session.", cause)

enum class SignInRoutingState { READY, VALIDATING, REJECTED }

class AuthRepository(private val sb: SupabaseClient) {
    val status get() = sb.auth.sessionStatus
    private val _signInRoutingState = MutableStateFlow(SignInRoutingState.READY)
    val signInRoutingState = _signInRoutingState.asStateFlow()

    private suspend fun authenticate(email: String, password: String) {
        sb.auth.clearSession()
        sb.auth.signInWith(Email) {
            this.email = email.trim()
            this.password = password
        }
        val session = sb.auth.currentSessionOrNull()
        val authenticatedEmail = session?.user?.email
            ?: session?.accessToken?.let { Jwt.claim(it, "email") }
        if (authenticatedEmail?.trim()?.lowercase() != email.trim().lowercase()) {
            val localSessionCleared = try {
                sb.auth.clearSession()
                true
            } catch (_: Exception) {
                false
            }
            throw SignInIdentityException(localSessionCleared)
        }
    }

    suspend fun signInCustomer(email: String, password: String) {
        _signInRoutingState.value = SignInRoutingState.VALIDATING
        try {
            authenticate(email, password)
            if (!isCustomerAccount()) {
                rejectMismatchedAccount()
                throw CustomerAccountRequiredException()
            }
            _signInRoutingState.value = SignInRoutingState.READY
        } finally {
            if (_signInRoutingState.value == SignInRoutingState.VALIDATING) {
                _signInRoutingState.value = if (sb.auth.currentSessionOrNull() == null) {
                    SignInRoutingState.READY
                } else {
                    SignInRoutingState.REJECTED
                }
            }
        }
    }

    suspend fun signInRestaurant(email: String, password: String) {
        _signInRoutingState.value = SignInRoutingState.VALIDATING
        try {
            authenticate(email, password)
            if (isCustomerAccount()) {
                rejectMismatchedAccount()
                throw RestaurantAccountRequiredException()
            }
            _signInRoutingState.value = SignInRoutingState.READY
        } finally {
            if (_signInRoutingState.value == SignInRoutingState.VALIDATING) {
                _signInRoutingState.value = if (sb.auth.currentSessionOrNull() == null) {
                    SignInRoutingState.READY
                } else {
                    SignInRoutingState.REJECTED
                }
            }
        }
    }

    private suspend fun rejectMismatchedAccount() {
        _signInRoutingState.value = SignInRoutingState.REJECTED
        try {
            sb.auth.clearSession()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            throw AccountTypeSessionClearException(e)
        }
    }

    private fun isCustomerAccount(): Boolean {
        val role = sb.auth.currentAccessTokenOrNull()?.let { Jwt.claim(it, "app_role") }
        val customerMetadata = sb.auth.currentSessionOrNull()?.user?.userMetadata
            ?.get("account_type")?.jsonPrimitive?.contentOrNull == "customer"
        return role == "customer" || (role == null && customerMetadata)
    }

    fun customerRoleConfigured(): Boolean =
        sb.auth.currentAccessTokenOrNull()?.let { Jwt.claim(it, "app_role") } == "customer"

    /** Returns true when a session started; false when the project requires email confirmation first. */
    suspend fun signUp(email: String, password: String, accountType: String? = null): Boolean {
        sb.auth.signUpWith(Email, redirectUrl = AUTH_REDIRECT_URL) {
            this.email = email.trim()
            this.password = password
            data = buildJsonObject { put("account_type", accountType ?: "restaurant") }
        }
        return sb.auth.currentSessionOrNull() != null
    }

    suspend fun resendConfirmation(email: String) {
        sb.auth.resendEmail(OtpType.Email.SIGNUP, email.trim())
    }

    suspend fun requestPasswordReset(email: String) {
        sb.auth.resetPasswordForEmail(email.trim(), redirectUrl = AUTH_REDIRECT_URL)
    }

    suspend fun updatePassword(password: String) {
        sb.auth.updateUser { this.password = password }
    }

    fun email(): String? = sb.auth.currentUserOrNull()?.email

    suspend fun signOut() {
        try {
            sb.auth.signOut()
        } catch (e: Exception) {
            val localSessionCleared = try {
                withContext(NonCancellable) { sb.auth.clearSession() }
                true
            } catch (clearError: Exception) {
                e.addSuppressed(clearError)
                false
            }
            if (e is CancellationException) throw e
            throw SignOutException(localSessionCleared, e)
        }
    }

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

}
