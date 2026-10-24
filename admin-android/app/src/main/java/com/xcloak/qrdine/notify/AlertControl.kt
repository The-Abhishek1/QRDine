package com.xcloak.qrdine.notify

import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat
import com.xcloak.qrdine.QrDineApp

object AlertControl {
    /** Follow the restaurant's is_open flag (so a second device alerts too). Safe to call repeatedly. */
    fun sync(ctx: Context, open: Boolean) {
        Prefs.setOpenForOrders(ctx, open)
        val intent = Intent(ctx, AlertService::class.java)
        if (open) runCatching { ContextCompat.startForegroundService(ctx, intent) } else ctx.stopService(intent)
    }

    fun stopAll(ctx: Context) {
        Prefs.setOpenForOrders(ctx, false)
        ctx.stopService(Intent(ctx, AlertService::class.java))
        QrDineApp.container.ordersStore.stop()
        Notifier.clearAlert(ctx)
    }
}
