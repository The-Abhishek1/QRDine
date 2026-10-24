package com.xcloak.qrdine.notify

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.xcloak.qrdine.QrDineApp
import io.github.jan.supabase.auth.auth
import kotlinx.coroutines.launch

/** Handles the Accept / Reject buttons on an order notification, without opening the app. */
class OrderActionReceiver : BroadcastReceiver() {
    companion object {
        const val ACTION_ACCEPT = "com.xcloak.qrdine.ACCEPT_ORDER"
        const val ACTION_REJECT = "com.xcloak.qrdine.REJECT_ORDER"
        const val EXTRA_ORDER = "order_id"
        const val DEFAULT_ETA = 15
    }

    override fun onReceive(context: Context, intent: Intent) {
        val orderId = intent.getStringExtra(EXTRA_ORDER) ?: return
        val action = intent.action ?: return
        val pending = goAsync()
        QrDineApp.appScope.launch {
            try {
                val c = QrDineApp.container
                c.supabase.auth.awaitInitialization()
                when (action) {
                    ACTION_ACCEPT -> c.orders.accept(orderId, DEFAULT_ETA)
                    ACTION_REJECT -> c.orders.reject(orderId, "Too busy right now")
                }
                c.ordersStore.refresh()
                Notifier.clearAlert(context)
                AlertSound.stop()
            } catch (_: Exception) {
                // already handled on another device, or offline: the alert keeps ringing so it is not lost
            } finally {
                pending.finish()
            }
        }
    }
}
