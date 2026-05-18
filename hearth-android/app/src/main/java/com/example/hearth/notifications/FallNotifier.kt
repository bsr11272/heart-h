package com.example.hearth.notifications

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.RingtoneManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat

object FallNotifier {

    const val CHANNEL_ID = "hearth_fall_alerts"
    const val NOTIFICATION_ID = 1001

    private val VIBRATION_PATTERN = longArrayOf(0L, 400L, 200L, 400L)

    fun ensureChannel(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Fall alerts",
            NotificationManager.IMPORTANCE_HIGH,
        ).apply {
            description = "High-priority alerts when Hearth detects a fall."
            enableVibration(true)
            vibrationPattern = VIBRATION_PATTERN
            setSound(
                RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION),
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_NOTIFICATION)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build(),
            )
        }
        nm.createNotificationChannel(channel)
    }

    fun postFallAlert(context: Context, source: String, notes: String?) {
        ensureChannel(context)

        val openIntent = Intent().apply {
            setClassName(context, "com.example.hearth.MainActivity")
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        val pi = PendingIntent.getActivity(
            context,
            0,
            openIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

        val body = "$source · ${notes ?: ""}"
        val builder = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_dialog_alert)
            .setContentTitle("Hearth — Fall detected")
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText("$source\n${notes ?: ""}"))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_CALL)
            .setAutoCancel(true)
            .setVibrate(VIBRATION_PATTERN)
            .setDefaults(NotificationCompat.DEFAULT_SOUND)
            .setContentIntent(pi)

        val nmc = NotificationManagerCompat.from(context)
        if (!nmc.areNotificationsEnabled()) return
        try {
            nmc.notify(NOTIFICATION_ID, builder.build())
        } catch (_: SecurityException) {
            // POST_NOTIFICATIONS revoked between the check and the notify call.
        }
    }
}
