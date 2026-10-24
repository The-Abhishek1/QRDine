package com.xcloak.qrdine.notify

import android.Manifest
import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.RingtoneManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.xcloak.qrdine.MainActivity
import com.xcloak.qrdine.R

object Notifier {
    const val CH_ORDERS = "orders_v1"
    const val CH_SERVICE = "service_v1"
    const val ID_SERVICE = 101
    const val ID_ALERT = 102

    fun createChannels(ctx: Context) {
        if (Build.VERSION.SDK_INT < 26) return
        val nm = ctx.getSystemService(NotificationManager::class.java)
        val sound = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
            ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)
        val attrs = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_ALARM)
            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
            .build()
        nm.createNotificationChannel(
            NotificationChannel(CH_ORDERS, "New orders and waiter calls", NotificationManager.IMPORTANCE_HIGH).apply {
                description = "Rings and vibrates until the order or request is handled"
                enableVibration(true)
                vibrationPattern = longArrayOf(0, 600, 300, 600, 300, 600)
                setSound(sound, attrs)
                lockscreenVisibility = Notification.VISIBILITY_PUBLIC
            }
        )
        nm.createNotificationChannel(
            NotificationChannel(CH_SERVICE, "Open for orders", NotificationManager.IMPORTANCE_LOW).apply {
                description = "Shown while the restaurant is open for orders"
            }
        )
    }

    private fun openApp(ctx: Context): PendingIntent = PendingIntent.getActivity(
        ctx, 0,
        Intent(ctx, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    fun serviceNotification(ctx: Context): Notification =
        NotificationCompat.Builder(ctx, CH_SERVICE)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("Open for orders")
            .setContentText("You will be alerted when guests order")
            .setOngoing(true)
            .setContentIntent(openApp(ctx))
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .build()

    @SuppressLint("MissingPermission")
    fun alert(ctx: Context, title: String, text: String, orderId: String? = null) {
        if (Build.VERSION.SDK_INT >= 33 &&
            ctx.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) return
        val builder = NotificationCompat.Builder(ctx, CH_ORDERS)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title)
            .setContentText(text)
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setAutoCancel(true)
            .setContentIntent(openApp(ctx))
        if (orderId != null) {
            fun action(act: String, label: String): NotificationCompat.Action {
                val i = Intent(ctx, OrderActionReceiver::class.java).setAction(act).putExtra(OrderActionReceiver.EXTRA_ORDER, orderId)
                val pi = PendingIntent.getBroadcast(ctx, (act + orderId).hashCode(), i, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
                return NotificationCompat.Action.Builder(0, label, pi).build()
            }
            builder.addAction(action(OrderActionReceiver.ACTION_ACCEPT, "Accept ${OrderActionReceiver.DEFAULT_ETA} min"))
            builder.addAction(action(OrderActionReceiver.ACTION_REJECT, "Reject"))
        }
        // Re-posting with the same id makes the channel sound ring again, which is how the repeat works.
        NotificationManagerCompat.from(ctx).notify(ID_ALERT, builder.build())
    }

    fun clearAlert(ctx: Context) = NotificationManagerCompat.from(ctx).cancel(ID_ALERT)
}
