package com.xcloak.qrdine.notify

import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.SystemClock
import androidx.core.app.ServiceCompat
import com.xcloak.qrdine.QrDineApp
import com.xcloak.qrdine.data.OwnerSnapshot
import io.github.jan.supabase.auth.auth
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Runs while the restaurant is "open for orders": keeps the process alive, keeps the order store live,
 * and re-rings every 30 s until nothing needs the owner (a new order rings immediately).
 */
class AlertService : Service() {
    companion object {
        @Volatile var running = false
    }

    private var job: Job? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Notifier.createChannels(this)
        ServiceCompat.startForeground(
            this, Notifier.ID_SERVICE, Notifier.serviceNotification(this),
            if (Build.VERSION.SDK_INT >= 34) ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE else 0,
        )
        if (!QrDineApp.configured || !Prefs.openForOrders(this)) {
            stopSelf()
            return START_NOT_STICKY
        }
        running = true
        if (job?.isActive != true) job = QrDineApp.appScope.launch { loop() }
        return START_STICKY
    }

    private suspend fun loop() {
        val c = QrDineApp.container
        c.supabase.auth.awaitInitialization()
        val rid = c.auth.restaurantId()
        if (rid == null) { stopSelf(); return }
        c.ordersStore.start(rid)

        var last = 0
        var lastRing = 0L
        while (currentCoroutineContext().isActive) {
            val snap = c.ordersStore.snapshot.value
            val n = snap.attention
            val now = SystemClock.elapsedRealtime()
            if (n > 0 && (n > last || now - lastRing >= 30_000)) {
                val (title, text) = describe(snap)
                val single = if (snap.pending.size == 1 && snap.requests.isEmpty()) snap.pending[0].order.id else null
                Notifier.alert(this, title, text, single)
                AlertSound.play(this)
                lastRing = now
            }
            if (n == 0 && last > 0) { Notifier.clearAlert(this); AlertSound.stop() }
            last = n
            delay(2000)
        }
    }

    private fun describe(s: OwnerSnapshot): Pair<String, String> {
        val tables = (s.pending.map { it.tableLabel } + s.requests.map { it.tableLabel }).distinct().joinToString(", ")
        val title = when {
            s.pending.isNotEmpty() && s.requests.isNotEmpty() -> "${s.pending.size} order(s) and ${s.requests.size} request(s) waiting"
            s.pending.isNotEmpty() -> if (s.pending.size == 1) "New order waiting" else "${s.pending.size} orders waiting"
            else -> if (s.requests.size == 1) "A table needs you" else "${s.requests.size} tables need you"
        }
        return title to "Table $tables. Open the app to respond."
    }

    override fun onDestroy() {
        running = false
        job?.cancel()
        AlertSound.stop()
        Notifier.clearAlert(this)
        super.onDestroy()
    }
}
