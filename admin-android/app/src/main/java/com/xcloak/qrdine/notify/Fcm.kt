package com.xcloak.qrdine.notify

import android.content.Context
import com.google.firebase.FirebaseApp
import com.google.firebase.messaging.FirebaseMessaging
import com.xcloak.qrdine.QrDineApp
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

/** Push is optional: without google-services.json Firebase never initialises and these calls do nothing. */
object Fcm {
    fun available(ctx: Context) = FirebaseApp.getApps(ctx).isNotEmpty()

    private suspend fun token(): String? = suspendCancellableCoroutine { cont ->
        FirebaseMessaging.getInstance().token.addOnCompleteListener { t ->
            if (cont.isActive) cont.resume(if (t.isSuccessful) t.result else null)
        }
    }

    suspend fun registerIfPossible(ctx: Context) {
        if (!available(ctx)) return
        val c = QrDineApp.container
        val rid = c.auth.restaurantId() ?: return
        val tok = token() ?: return
        runCatching { c.orders.registerDevice(rid, tok) }
    }

    suspend fun unregister(ctx: Context) {
        if (!available(ctx)) return
        val tok = token() ?: return
        runCatching { QrDineApp.container.orders.deleteDevice(tok) }
    }
}
