package com.example.emergencysafety

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.RingtoneManager
import android.os.Build
import androidx.core.app.NotificationCompat

internal object CallAlerts {
    fun name(context: Context, connection: String): String {
        val email = context.getSharedPreferences("EmergencySafetyConnectionEmails", 0).getString(connection, null)
        return email?.let { ContactNames.getDisplayName(context, it) } ?: "EmergencySafety kişisi"
    }
    private fun manager(context: Context) = context.getSystemService(NotificationManager::class.java)
    @Synchronized fun silence(context: Context, id: String) {
        context.getSharedPreferences("handled_calls", 0).edit().putBoolean(id, true).apply()
        manager(context).cancel(id, 1)
    }
    @Synchronized fun show(context: Context, connection: String, id: String, kind: String, remaining: Long) {
        val incoming = kind == "invite" || kind == "videoInvite"
        val prefs = context.getSharedPreferences("handled_calls", 0)
        if (incoming && (remaining <= 0 || prefs.contains(id))) return
        if (!incoming) silence(context, id)
        if (kind.endsWith("Connected") || kind.endsWith("Failed")) { manager(context).cancel(id, 2); return }
        val video = kind.startsWith("video")
        val channel = if (incoming) "es_ringing_calls_v1" else "es_missed_calls_v1"
        val tone = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE)
        if (Build.VERSION.SDK_INT >= 26) {
            val config = NotificationChannel(channel, if (incoming) "Gelen aramalar" else "Cevapsız aramalar",
                NotificationManager.IMPORTANCE_HIGH)
            if (incoming) config.setSound(tone, AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_NOTIFICATION_RINGTONE).build())
            manager(context).createNotificationChannel(config)
        }
        val intent = Intent(context, YellowSafetyActivity::class.java)
            .putExtra("connectionId", connection).putExtra("matrixNotification", true)
            .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP)
        val pending = PendingIntent.getActivity(context, id.hashCode(), intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val type = if (video) "görüntülü arama" else "sesli arama"
        val notification = NotificationCompat.Builder(context, channel)
            .setContentTitle(name(context, connection)).setSmallIcon(R.drawable.ic_notification_wolf)
            .setContentText(if (incoming) "Gelen $type · Yanıtlamak için dokun" else "Cevapsız $type · Geri aramak için dokun")
            .setPriority(NotificationCompat.PRIORITY_HIGH).setCategory(NotificationCompat.CATEGORY_CALL)
            .setContentIntent(pending).setAutoCancel(true).setOnlyAlertOnce(true)
        if (incoming) notification.setSound(tone).setTimeoutAfter(remaining)
        val built = notification.build()
        if (incoming) built.flags = built.flags or Notification.FLAG_INSISTENT
        try { manager(context).notify(id, if (incoming) 1 else 2, built) } catch (_: SecurityException) {}
    }
}