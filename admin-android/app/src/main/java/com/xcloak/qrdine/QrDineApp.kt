package com.xcloak.qrdine

import android.app.Application
import android.content.Context
import com.xcloak.qrdine.data.AppContainer
import com.xcloak.qrdine.data.Jwt
import com.xcloak.qrdine.notify.Notifier
import com.xcloak.qrdine.ui.AppThemePreference
import io.github.jan.supabase.auth.auth
import io.github.jan.supabase.auth.status.SessionStatus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableStateFlow

class QrDineApp : Application() {
    override fun onCreate() {
        super.onCreate()
        appContext = applicationContext
        AppThemePreference.initialize(this)
        Notifier.createChannels(this)
        Diagnostics.install(this)
        if (configured) appScope.launch {
            val app = this@QrDineApp
            val client = container.supabase
            client.auth.awaitInitialization()
            container.auth.status.collect { status ->
                when (status) {
                    is SessionStatus.Authenticated -> {
                        Diagnostics.setRestaurantId(app, Jwt.claim(status.session.accessToken, "restaurant_id"))
                        Diagnostics.flush(app, client)
                    }
                    is SessionStatus.NotAuthenticated -> Diagnostics.setRestaurantId(app, null)
                    else -> Unit
                }
            }
        }
    }

    companion object {
        lateinit var appContext: Context
            private set

        val passwordRecoveryPending = MutableStateFlow(false)

        /** Lives as long as the process: the order store and the alert service run in it. */
        val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

        val configured: Boolean
            get() = BuildConfig.SUPABASE_URL.isNotBlank() && BuildConfig.SUPABASE_ANON_KEY.isNotBlank()

        /** Created lazily so a missing local.properties shows a message instead of crashing. */
        val container: AppContainer by lazy { AppContainer() }
    }
}
