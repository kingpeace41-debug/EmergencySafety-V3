
package com.example.emergencysafety

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
            val senderName = ContactNames.getSavedName(
                this,
                senderEmail
            )?.takeUnless { it.contains("@") }
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

        if (
            ActiveChatTracker.isChatVisible(connectionId)
        ) {
            return
        }

        showPrivateNotification(
            senderUsername = senderUsername,
            senderEmail = senderEmail,
            messagePreview = messagePreview,
            connectionId = connectionId
        )
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
        senderEmail: String
    ): String {
        val name = if (senderEmail.isNotBlank()) {
            ContactNames.getDisplayName(
                this,
                senderEmail
            )
        } else {
            senderUsername
        }

        return name.ifBlank {
            "Emergency Safety"
        }
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
            senderEmail
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
