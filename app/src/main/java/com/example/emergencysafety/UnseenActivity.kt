package com.example.emergencysafety

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat

internal object UnseenActivity {
    private const val SUMMARY = 796013
    fun prefs(ctx: Context) = ctx.getSharedPreferences("yellow_activity_counts", Context.MODE_PRIVATE)
    fun count(ctx: Context): Int {
        val session = SecureSessionStore.getSessionId(ctx) ?: return 0
        return prefs(ctx).getStringSet("unread:$session", emptySet()).orEmpty().size
    }

    fun rowCount(ctx: Context, connection: String, legacyCount: Int): Int {
        val session = SecureSessionStore.getSessionId(ctx) ?: return 0
        val prefs = prefs(ctx)
        val keys = prefs.getStringSet("unread:$session", emptySet()).orEmpty().filter {
            prefs.getString("owner:$session:$it", null) == connection
        }
        val legacy = keys.count { it.startsWith("legacy:") }
        return maxOf(legacy, legacyCount) + keys.size - legacy
    }

    @Synchronized fun update(ctx: Context, ids: Collection<String>, seen: Boolean, session: String? = SecureSessionStore.getSessionId(ctx), connection: String? = null) {
        if (session == null || ids.isEmpty() || session != SecureSessionStore.getSessionId(ctx)) return
        val prefs = prefs(ctx)
        val key = "unread:$session"
        val unread = prefs.getStringSet(key, emptySet()).orEmpty().toMutableSet()
        val edit = prefs.edit()
        var changed = false
        for (id in ids.distinct()) {
            val marker = "seen:$session:$id"
            if (!seen && !connection.isNullOrBlank() && !prefs.contains(marker)) edit.putString("owner:$session:$id", connection)
            if (seen) {
                edit.remove("owner:$session:$id")
                if (!prefs.contains(marker)) edit.putBoolean(marker, true)
                if (unread.remove(id)) changed = true
            } else if (!prefs.contains(marker) && unread.add(id)) changed = true
        }
        if (changed) edit.putStringSet(key, unread)
        edit.apply()
        if (changed) publish(ctx)
    }

    fun publish(ctx: Context) {
        val nm = ctx.getSystemService(NotificationManager::class.java)
        val total = count(ctx)
        if (total == 0) { nm.cancel(SUMMARY); return }
        val channel = "es_unseen_activity"
        if (Build.VERSION.SDK_INT >= 26) nm.createNotificationChannel(
            NotificationChannel(channel, "Mesaj kalkanı sayacı", NotificationManager.IMPORTANCE_LOW).apply {
                setShowBadge(true); setSound(null, null)
            })
        val intent = PendingIntent.getActivity(ctx, SUMMARY, Intent(ctx, YellowSafetyActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val note = NotificationCompat.Builder(ctx, channel).setSmallIcon(R.drawable.ic_notification_wolf)
            .setContentTitle("EmergencySafety").setContentText("$total görülmemiş etkinlik")
            .setBadgeIconType(NotificationCompat.BADGE_ICON_SMALL)
            .setNumber(total).setOnlyAlertOnce(true).setSound(null).setContentIntent(intent).build()
        try { nm.notify(SUMMARY, note) } catch (_: SecurityException) {}
    }
}