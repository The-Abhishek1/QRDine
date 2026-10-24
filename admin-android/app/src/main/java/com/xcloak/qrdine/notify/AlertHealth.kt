package com.xcloak.qrdine.notify

import android.app.NotificationManager
import android.content.Context
import android.os.Build
import android.os.PowerManager
import androidx.core.app.NotificationManagerCompat

data class HealthItem(val ok: Boolean, val title: String, val fix: String)

/** The checklist behind "no alert arrived": every reason an owner can miss an order, checked in one place. */
object AlertHealth {
    fun check(ctx: Context, restaurantOpen: Boolean): List<HealthItem> {
        val items = mutableListOf<HealthItem>()
        items += HealthItem(restaurantOpen, "Open for orders is on", "Switch it on in the Orders tab. Alerts only run while you are open.")
        items += HealthItem(AlertService.running, "Alert service is running", "Turn Open for orders off and on again. If it stays red, reopen the app.")
        items += HealthItem(NotificationManagerCompat.from(ctx).areNotificationsEnabled(), "Notifications are allowed", "Open notification settings and allow notifications for this app.")

        val nm = ctx.getSystemService(NotificationManager::class.java)
        val channelOk = if (Build.VERSION.SDK_INT >= 26) {
            val ch = nm.getNotificationChannel(Notifier.CH_ORDERS)
            ch != null && ch.importance >= NotificationManager.IMPORTANCE_HIGH && ch.sound != null
        } else true
        items += HealthItem(channelOk, "Order sound is on", "In notification settings open \"New orders and waiter calls\" and set Importance to Urgent with a sound.")

        val dndOff = nm.currentInterruptionFilter == NotificationManager.INTERRUPTION_FILTER_ALL ||
            nm.currentInterruptionFilter == NotificationManager.INTERRUPTION_FILTER_UNKNOWN
        items += HealthItem(dndOff, "Do Not Disturb is off", "Turn Do Not Disturb off during service hours, or allow this app to override it.")

        val pm = ctx.getSystemService(PowerManager::class.java)
        items += HealthItem(pm.isIgnoringBatteryOptimizations(ctx.packageName), "Battery use is unrestricted", "Open battery settings and set this app to Unrestricted. On Xiaomi, Oppo, Vivo, Realme and OnePlus also allow Autostart.")
        items += HealthItem(Fcm.available(ctx), "Push for a closed app is set up", "Optional. Add google-services.json to get alerts even after the system closes the app.")
        return items
    }
}
