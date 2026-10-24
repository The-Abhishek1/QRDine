package com.xcloak.qrdine.notify

import android.content.Intent
import androidx.core.content.ContextCompat
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import com.xcloak.qrdine.QrDineApp
import kotlinx.coroutines.launch

/** High-priority data pushes from the notify Edge Function. They wake the app when the socket was killed. */
class OrderFcmService : FirebaseMessagingService() {

    override fun onNewToken(token: String) {
        QrDineApp.appScope.launch { Fcm.registerIfPossible(applicationContext) }
    }

    override fun onMessageReceived(message: RemoteMessage) {
        val d = message.data
        Notifier.alert(this, d["title"] ?: "New order", d["body"] ?: "Open the app to respond.", if (d["kind"] == "order") d["order_id"] else null)
        AlertSound.play(this)
        if (Prefs.openForOrders(this)) {
            // restarts the alert loop (and the live connection) if the system had killed it
            runCatching { ContextCompat.startForegroundService(this, Intent(this, AlertService::class.java)) }
        }
    }
}
