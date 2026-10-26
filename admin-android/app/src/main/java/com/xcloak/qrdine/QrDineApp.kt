package com.xcloak.qrdine

import android.app.Application
import android.content.Context
import com.xcloak.qrdine.data.AppContainer
import com.xcloak.qrdine.notify.Notifier
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow

class QrDineApp : Application() {
    override fun onCreate() {
        super.onCreate()
        appContext = applicationContext
        Notifier.createChannels(this)
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
