package com.example.emergencysafety

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withTimeout

class MatrixWakeWorker(context: Context, parameters: WorkerParameters) : CoroutineWorker(context, parameters) {
    override suspend fun doWork(): Result {
        val app = applicationContext
        val session = inputData.getString("session") ?: return Result.failure()
        val connection = inputData.getString("connectionId") ?: return Result.failure()
        val room = inputData.getString("roomId") ?: return Result.failure()
        val event = inputData.getString("eventId") ?: return Result.failure()
        if (SecureSessionStore.getSessionId(app) != session) return Result.success()
        try {
            if (inputData.getBoolean("receiptOnly", false)) {
                MatrixChatManager.sendReceipt(app, connection, MatrixChatManager.Receipt.DELIVERED, listOf(event))
                return Result.success()
            }
            val delivered = withTimeout(45_000L) {
                MatrixChatManager.receiveWake(app, session, connection, room, event)
            }
            if (delivered?.startsWith("call|") == true && SecureSessionStore.getSessionId(app) == session) {
                val parts = delivered.split('|')
                val kind = parts[2]
                if (kind.endsWith("Missed")) UnseenActivity.update(app, listOf("missed:" + parts[1]), false, session, connection)
                val remaining = 45000L - (System.currentTimeMillis() - parts[3].toLong())
                if (kind != "invite" && kind != "videoInvite" || !ActiveChatTracker.isChatVisible(connection))
                    CallAlerts.show(app, connection, parts[1], kind, remaining.coerceAtMost(45000L))
                return Result.success()
            }
            if (delivered == "message" && SecureSessionStore.getSessionId(app) == session) {
                UnseenActivity.update(app, listOf("matrix:$event"), false, session, connection)
                val receipt = androidx.work.OneTimeWorkRequestBuilder<MatrixWakeWorker>()
                    .setInputData(androidx.work.Data.Builder().putAll(inputData).putBoolean("receiptOnly", true).build())
                    .setBackoffCriteria(androidx.work.BackoffPolicy.LINEAR, 10, java.util.concurrent.TimeUnit.SECONDS)
                    .build()
                androidx.work.WorkManager.getInstance(app).enqueueUniqueWork("receipt:$session:$event", androidx.work.ExistingWorkPolicy.KEEP, receipt)
            }
            if (delivered != null && SecureSessionStore.getSessionId(app) == session && !ActiveChatTracker.isChatVisible(connection)) {
                notifyMessage(connection, delivered)
            }
            return Result.success()
        } catch (_: TimeoutCancellationException) {
            return retryIfCurrent()
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            return retryIfCurrent()
        }
    }

    private fun retryIfCurrent() = if (runAttemptCount < 5) Result.retry() else Result.failure()

    private fun notifyMessage(connection: String, kind: String) {
        val call = kind == "call" || kind == "videoCall"
        val app = applicationContext
        val notifications = NotificationManagerCompat.from(app)
        if (!notifications.areNotificationsEnabled()) return
        val channel = if (call) "es_voice_calls" else "emergency_safety_messages"
        if (Build.VERSION.SDK_INT >= 26) {
            app.getSystemService(NotificationManager::class.java).createNotificationChannel(
                NotificationChannel(channel, if (call) "Aramalar" else "Emergency Safety Bildirimleri", NotificationManager.IMPORTANCE_HIGH)
            )
        }
        val intent = Intent(app, YellowSafetyActivity::class.java)
            .putExtra("connectionId", connection)
            .putExtra("matrixNotification", true)
            .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP)
        val id = ("matrix:" + connection).hashCode()
        val pending = PendingIntent.getActivity(app, id, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val email = app.getSharedPreferences("EmergencySafetyConnectionEmails", Context.MODE_PRIVATE)
            .getString(connection, null)
        val senderName = email?.let { ContactNames.getSavedName(app, it) }
        val notification = NotificationCompat.Builder(app, channel)
            .setSmallIcon(R.drawable.ic_notification_wolf)
            .setContentTitle(senderName ?: "EmergencySafety")
            .setContentText(if (kind == "videoCall") "Gelen görüntülü arama · Yanıtlamak için dokun" else if (call) "Gelen sesli arama · Yanıtlamak için dokun" else "Yeni şifreli mesaj")
            .apply { if (call) setCategory(NotificationCompat.CATEGORY_CALL).setTimeoutAfter(45000L) }
            .setContentIntent(pending).setAutoCancel(true).setOnlyAlertOnce(true).build()
        try {
            notifications.notify(id, notification)
        } catch (_: SecurityException) {
            // Delivery remains valid when notification permission is revoked.
        }
    }
}
