
package com.example.emergencysafety

import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.WorkManager
import androidx.work.workDataOf
import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage

class FcmMessagingService : FirebaseMessagingService() {

    companion object {
        private const val MESSAGE_CHANNEL_ID =
            "emergency_safety_messages"

        private const val MESSAGE_CHANNEL_NAME =
            "Emergency Safety Bildirimleri"

        private const val EMERGENCY_CHANNEL_ID =
            "es_emergency_alerts_v1"

        private const val EMERGENCY_CHANNEL_NAME =
            "ES Acil Durum Alarmları"

        const val EMERGENCY_PREFS =
            "EsIncomingEmergency"

        const val KEY_PENDING =
            "pending_emergency"

        const val KEY_CONNECTION_ID =
            "emergency_connection_id"

        const val KEY_SENDER_NAME =
            "emergency_sender_name"

        const val KEY_PREVIEW =
            "emergency_preview"

        const val KEY_RECEIVED_AT =
            "emergency_received_at"
    }

    override fun onNewToken(token: String) {
        super.onNewToken(token)

        FcmTokenRegistrar.registerNewToken(
            applicationContext,
            token
        )
    }

    override fun onMessageReceived(message: RemoteMessage) {
        super.onMessageReceived(message)
        val type = message.data["type"]
        if (type == "message_request" || type == "message_request_accepted") {
            if (SecureSessionStore.getSessionId(applicationContext) == null) return
            createMessageNotificationChannel()
            val accepted = type == "message_request_accepted"
            val connection = message.data["connectionId"].orEmpty()
            if (accepted && !Regex("^[a-fA-F0-9]{8}(?:-[a-fA-F0-9]{4}){3}-[a-fA-F0-9]{12}$").matches(connection)) return
            val activityId = if (accepted) "accepted:$connection" else
                "invite:" + message.data["requestId"].orEmpty().ifBlank { connection }
            if (!activityId.endsWith(":")) UnseenActivity.update(this, listOf(activityId), false)
            if (!notificationsAllowed()) return
            val id = if (accepted) ("accepted:" + connection).hashCode()
                else ("request:" + message.data["requestId"].orEmpty()).hashCode()
            val target = Intent(this, YellowSafetyActivity::class.java)
                .apply {
                    if (accepted) {
                        putExtra("connectionId", connection)
                        putExtra("matrixNotification", true)
                    } else putExtra("messageRequestNotification", true)
                }
                .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            val pending = PendingIntent.getActivity(this, id, target, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
            val name = ContactNames.getSavedName(this, message.data["senderEmail"].orEmpty())
                ?.takeIf { it.isNotBlank() && !it.contains('@') }
            val label = if (accepted) {
                name?.let { "$it mesaj isteğini kabul etti." } ?: "Mesaj isteğin kabul edildi."
            } else {
                name?.let { "$it sana mesaj isteği gönderdi." } ?: "Yeni bir mesaj isteğin var."
            }
            postNotification(id, NotificationCompat.Builder(this, MESSAGE_CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_notification_wolf)
                .setContentTitle("EmergencySafety").setContentText(label)
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setContentIntent(pending).setAutoCancel(true).build())
            return
        }
        if (message.data["type"] == "matrix_wake") {
            val session = SecureSessionStore.getSessionId(applicationContext) ?: return
            val connection = message.data["connectionId"] ?: return
            val room = message.data["roomId"] ?: return
            val event = message.data["eventId"] ?: return
            if (connection.length != 36 || room.length !in 2..255 || !room.startsWith("!") ||
                event.length !in 2..255 || !event.startsWith("$")) return
            val job = OneTimeWorkRequestBuilder<MatrixWakeWorker>()
                .setInputData(workDataOf("session" to session, "connectionId" to connection,
                    "roomId" to room, "eventId" to event))
                .apply { if (Build.VERSION.SDK_INT >= 31) setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST) }
                .build()
            WorkManager.getInstance(applicationContext).enqueueUniqueWork(
                "matrix-wake:$session:$event", ExistingWorkPolicy.KEEP, job)
            return
        }

        val senderUsername =
            message.data["senderUsername"].orEmpty().trim()

        val senderEmail =
            message.data["senderEmail"].orEmpty().trim()

        val messagePreview =
            message.data["messagePreview"].orEmpty().trim()

        val connectionId =
            message.data["connectionId"].orEmpty().trim()

        val messageType =
            message.data["messageType"].orEmpty().trim()

        if (messageType == "emergency") {
            // Acil durumda e-posta adresini hicbir zaman gosterme.
            // Kayitli isim yoksa guvenli ve genel bir baslik kullan.
            val cachedEmail = getSharedPreferences(
                "EmergencySafetyConnectionEmails",
                MODE_PRIVATE
            ).getString(connectionId, "").orEmpty()

            // FCM e-posta alani bos olsa bile baglanti kimliginden
            // gonderenin telefonda kayitli adini bul.
            val redContacts = getSharedPreferences(
                "EmergencyContactSettings",
                MODE_PRIVATE
            )
            val redEmail = (1..3).firstNotNullOfOrNull { slot ->
                if (redContacts.getString("es_contact${slot}_id", "") == connectionId) {
                    redContacts.getString("es_contact${slot}_email", "")
                } else {
                    null
                }
            }.orEmpty()

            val senderName = sequenceOf(
                cachedEmail,
                redEmail,
                senderEmail
            ).filter { it.isNotBlank() }
                .mapNotNull { ContactNames.getSavedName(this, it) }
                .firstOrNull { it.isNotBlank() && !it.contains("@") }
                ?: "Bir kişi acil durumda"

            saveIncomingEmergency(
                senderName = senderName,
                messagePreview = messagePreview,
                connectionId = connectionId
            )

            // Acil durum bildirimlerinde ozel sireni baslat.
            // Normal sohbet mesajlari bu ses yoneticisini kullanmaz.
            EmergencySirenPlayer.start()

            showEmergencyNotification(
                senderName = senderName,
                messagePreview = messagePreview,
                connectionId = connectionId
            )

            return
        }

        // Normal mesajlarda bildirim gorunmese bile teslim onayi gonder.
        // Acil durum alarmi bundan etkilenmez.
        val messageId =
            message.data["messageId"].orEmpty().trim()

        // A live-location message is part of the emergency, not a second alert.
        // Keep the message in the inbox and acknowledge delivery, but never
        // open a chat or create a separate push notification for it.
        if (messageType == "location" &&
            messagePreview.contains("ES_LOCATION_SESSION:")
        ) {
            acknowledgeDelivery(messageId)
            return
        }

        if (messageId.isNotBlank()) UnseenActivity.update(this, listOf("legacy:$messageId"), false)
        if (
            ActiveChatTracker.isChatVisible(connectionId)
        ) {
            acknowledgeDelivery(messageId)
            return
        }

        showPrivateNotification(
            senderUsername = senderUsername,
            senderEmail = senderEmail,
            messagePreview = messagePreview,
            connectionId = connectionId
        )

        acknowledgeDelivery(messageId)
    }

    private fun acknowledgeDelivery(messageId: String) {
        if (messageId.isBlank()) return

        try {
            val result = ConnectionApi.markMessageDelivered(
                applicationContext,
                messageId
            )
            if (!result.success) {
                android.util.Log.w(
                    "FcmMessagingService",
                    "Teslim onayi gonderilemedi: ${result.message}"
                )
            }
        } catch (error: Exception) {
            android.util.Log.w(
                "FcmMessagingService",
                "Teslim onayi hatasi",
                error
            )
        }
    }

    private fun saveIncomingEmergency(
        senderName: String,
        messagePreview: String,
        connectionId: String
    ) {
        getSharedPreferences(
            EMERGENCY_PREFS,
            Context.MODE_PRIVATE
        ).edit()
            .putBoolean(KEY_PENDING, true)
            .putString(
                KEY_CONNECTION_ID,
                connectionId
            )
            .putString(
                KEY_SENDER_NAME,
                senderName
            )
            .putString(
                KEY_PREVIEW,
                messagePreview
            )
            .putLong(
                KEY_RECEIVED_AT,
                System.currentTimeMillis()
            )
            .commit()
    }

    private fun notificationsAllowed(): Boolean {
        if (
            Build.VERSION.SDK_INT >=
                Build.VERSION_CODES.TIRAMISU
        ) {
            return ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED
        }

        return true
    }

    private fun senderDisplayName(
        senderUsername: String,
        senderEmail: String,
        connectionId: String
    ): String {
        val cachedEmail = getSharedPreferences(
            "EmergencySafetyConnectionEmails",
            MODE_PRIVATE
        ).getString(connectionId, "").orEmpty()

        // Bildirim alicisinin cihazinda kayitli adi kullan.
        // Kayit yoksa e-posta adresini bildirimde gosterme.
        return sequenceOf(cachedEmail, senderEmail)
            .filter { it.isNotBlank() }
            .mapNotNull { ContactNames.getSavedName(this, it) }
            .firstOrNull { it.isNotBlank() && !it.contains("@") }
            ?: senderUsername.takeIf {
                it.isNotBlank() &&
                    !it.contains("@") &&
                    !it.matches(
                        Regex(
                            "^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$"
                        )
                    ) &&
                    !it.matches(Regex("^[0-9a-fA-F]{24,}$")) &&
                    !it.matches(
                        Regex("(?i)^g_[0-9a-f]{12,}$")
                    )
            }
            ?: "Yeni mesaj"
    }

    private fun createChatIntent(
        connectionId: String
    ): Intent {
        return Intent(
            this,
            YellowSafetyActivity::class.java
        ).apply {
            flags =
                Intent.FLAG_ACTIVITY_NEW_TASK or
                    Intent.FLAG_ACTIVITY_CLEAR_TOP

            putExtra(
                "connectionId",
                connectionId
            )
        }
    }

    private fun createPendingIntent(
        connectionId: String,
        notificationId: Int
    ): PendingIntent {
        return PendingIntent.getActivity(
            this,
            notificationId,
            createChatIntent(connectionId),
            PendingIntent.FLAG_UPDATE_CURRENT or
                PendingIntent.FLAG_IMMUTABLE
        )
    }

    private fun createEmergencyPendingIntent(
        notificationId: Int
    ): PendingIntent {
        val intent = Intent(
            this,
            MainActivity::class.java
        ).apply {
            flags =
                Intent.FLAG_ACTIVITY_NEW_TASK or
                    Intent.FLAG_ACTIVITY_CLEAR_TOP or
                    Intent.FLAG_ACTIVITY_SINGLE_TOP
        }

        return PendingIntent.getActivity(
            this,
            notificationId,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or
                PendingIntent.FLAG_IMMUTABLE
        )
    }

    private fun showPrivateNotification(
        senderUsername: String,
        senderEmail: String,
        messagePreview: String,
        connectionId: String
    ) {
        if (!notificationsAllowed()) return

        createMessageNotificationChannel()

        val notificationKey =
            connectionId.ifBlank {
                System.currentTimeMillis().toString()
            }

        val notificationId =
            notificationKey.hashCode()

        val title = senderDisplayName(
            senderUsername,
            senderEmail,
            connectionId
        )

        val preview =
            messagePreview.ifBlank {
                "Yeni mesajınız var."
            }

        val notification =
            NotificationCompat.Builder(
                this,
                MESSAGE_CHANNEL_ID
            )
                .setSmallIcon(
                    R.drawable.ic_notification_wolf
                )
                .setContentTitle(title)
                .setContentText(preview)
                .setStyle(
                    NotificationCompat.BigTextStyle()
                        .bigText(preview)
                )
                .setPriority(
                    NotificationCompat.PRIORITY_HIGH
                )
                .setCategory(
                    NotificationCompat.CATEGORY_MESSAGE
                )
                .setAutoCancel(true)
                .setContentIntent(
                    createPendingIntent(
                        connectionId,
                        notificationId
                    )
                )
                .setDefaults(
                    NotificationCompat.DEFAULT_SOUND or
                        NotificationCompat.DEFAULT_VIBRATE
                )
                .build()

        postNotification(
            notificationId,
            notification
        )
    }

    private fun showEmergencyNotification(
        senderName: String,
        messagePreview: String,
        connectionId: String
    ) {
        if (!notificationsAllowed()) return

        createEmergencyNotificationChannel()

        val title =
            "🚨 ACİL DURUM - $senderName"

        val preview =
            messagePreview.ifBlank {
                "Acil durum mesajı alındı!"
            }

        val notificationKey =
            "emergency_${connectionId.ifBlank {
                System.currentTimeMillis().toString()
            }}"

        val notificationId =
            notificationKey.hashCode()

        val notification =
            NotificationCompat.Builder(
                this,
                EMERGENCY_CHANNEL_ID
            )
                .setSmallIcon(
                    R.drawable.ic_notification_wolf
                )
                .setContentTitle(title)
                .setContentText(preview)
                .setStyle(
                    NotificationCompat.BigTextStyle()
                        .bigText(preview)
                )
                .setPriority(
                    NotificationCompat.PRIORITY_MAX
                )
                .setCategory(
                    NotificationCompat.CATEGORY_ALARM
                )
                .setAutoCancel(false)
                .setOngoing(false)
                .setContentIntent(
                    createEmergencyPendingIntent(
                        notificationId
                    )
                )
                .setDefaults(
                    NotificationCompat.DEFAULT_SOUND or
                        NotificationCompat.DEFAULT_VIBRATE
                )
                .build()

        postNotification(
            notificationId,
            notification
        )
    }

    private fun postNotification(
        notificationId: Int,
        notification: Notification
    ) {
        try {
            NotificationManagerCompat.from(this)
                .notify(
                    notificationId,
                    notification
                )
        } catch (_: SecurityException) {
            // Bildirim izni yoksa uygulama kapanmaz.
        }
    }

    private fun createMessageNotificationChannel() {
        if (
            Build.VERSION.SDK_INT >=
                Build.VERSION_CODES.O
        ) {
            val channel = NotificationChannel(
                MESSAGE_CHANNEL_ID,
                MESSAGE_CHANNEL_NAME,
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description =
                    "Yeni özel mesaj bildirimleri"

                enableVibration(true)
            }

            getSystemService(
                NotificationManager::class.java
            ).createNotificationChannel(channel)
        }
    }

    private fun createEmergencyNotificationChannel() {
        if (
            Build.VERSION.SDK_INT >=
                Build.VERSION_CODES.O
        ) {
            val channel = NotificationChannel(
                EMERGENCY_CHANNEL_ID,
                EMERGENCY_CHANNEL_NAME,
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description =
                    "EmergencySafety acil durum bildirimleri"

                enableVibration(true)
                enableLights(true)
                lightColor = Color.RED
                setShowBadge(true)
            }

            getSystemService(
                NotificationManager::class.java
            ).createNotificationChannel(channel)
        }
    }
}
