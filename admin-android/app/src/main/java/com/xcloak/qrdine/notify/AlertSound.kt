package com.xcloak.qrdine.notify

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.Ringtone
import android.media.RingtoneManager
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager

/**
 * Rings the alarm tone and vibrates straight from the alert service. This works even if the notification channel
 * was muted or the notification permission was refused, so an order is never silent while the restaurant is open.
 */
object AlertSound {
    private var ringtone: Ringtone? = null

    fun play(ctx: Context) {
        stop()
        val uri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
            ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)
        val r = RingtoneManager.getRingtone(ctx.applicationContext, uri) ?: return
        if (Build.VERSION.SDK_INT >= 28) {
            r.audioAttributes = AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_ALARM)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .build()
        } else legacyStream(r)
        runCatching { r.play() }
        ringtone = r
        runCatching { vibrator(ctx).vibrate(VibrationEffect.createWaveform(longArrayOf(0, 600, 300, 600, 300, 600), -1)) }
    }

    fun stop() {
        runCatching { ringtone?.stop() }
        ringtone = null
    }

    @Suppress("DEPRECATION")
    private fun legacyStream(r: Ringtone) { r.streamType = AudioManager.STREAM_ALARM }

    @Suppress("DEPRECATION")
    private fun vibrator(ctx: Context): Vibrator =
        if (Build.VERSION.SDK_INT >= 31) ctx.getSystemService(VibratorManager::class.java).defaultVibrator
        else ctx.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
}
