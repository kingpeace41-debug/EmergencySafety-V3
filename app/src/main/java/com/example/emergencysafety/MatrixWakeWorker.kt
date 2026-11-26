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
            val delivered = withTimeout(45_000L) {
                MatrixChatManager.receiveWake(app, session, connection, room, event)
            }
            if (delivered && SecureSessionStore.getSessionId(app) == session && !ActiveChatTracker.isChatVisible(connection)) {
                notifyMessage(connection)
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

    private fun notifyMessage(connection: String) {
        val app = applicationContext
        val notifications = NotificationManagerCompat.from(app)
        if (!notifications.areNotificationsEnabled()) return
        val channel = "emergency_safety_messages"
        if (Build.VERSION.SDK_INT >= 26) {
            app.getSystemService(NotificationManager::class.java).createNotificationChannel(
                NotificationChannel(channel, "Emergency Safety Bildirimleri", NotificationManager.IMPORTANCE_HIGH)
            )
        }
        val intent = Intent(app, YellowSafetyActivity::class.java)
            .putExtra("connectionId", connection)
            .putExtra("matrixNotification", true)
            .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP)
        val id = ("matrix:" + connection).hashCode()
        val pending = PendingIntent.getActivity(app, id, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val notification = NotificationCompat.Builder(app, channel)
            .setSmallIcon(R.drawable.ic_notification_wolf)
            .setContentTitle("EmergencySafety").setContentText("Yeni şifreli mesaj")
            .setContentIntent(pending).setAutoCancel(true).setOnlyAlertOnce(true).build()
        try {
            notifications.notify(id, notification)
        } catch (_: SecurityException) {
            // Delivery remains valid when notification permission is revoked.
        }
    }
}
