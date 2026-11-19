
package com.example.emergencysafety

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage

class FcmMessagingService : FirebaseMessagingService() {

    companion object {
        private const val CHANNEL_ID = "emergency_safety_messages"
        private const val CHANNEL_NAME =
            "Emergency Safety Bildirimleri"
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

        val messagePreview =
            message.data["messagePreview"].orEmpty().trim()

        val connectionId =
            message.data["connectionId"].orEmpty().trim()

        // Kullanıcı zaten bu sohbeti görüntülüyorsa
        // gereksiz üst bildirim oluşturma.
        if (ActiveChatTracker.isChatVisible(connectionId)) {
            return
        }

        showPrivateNotification(
            senderUsername,
            messagePreview,
            connectionId
        )
    }

    private fun showPrivateNotification(
        senderUsername: String,
        messagePreview: String,
        connectionId: String
    ) {

        if (
            Build.VERSION.SDK_INT >=
                Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.POST_NOTIFICATIONS
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            return
        }

        createNotificationChannel()

        val openAppIntent = Intent(
            this,
            YellowSafetyActivity::class.java
        ).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or
                Intent.FLAG_ACTIVITY_CLEAR_TOP

            putExtra("connectionId", connectionId)
        }

        // Her bağlantının bildirimi ayrı tutulur.
        val notificationKey = connectionId.ifBlank {
            System.currentTimeMillis().toString()
        }

        val notificationId = notificationKey.hashCode()

        val pendingIntent = PendingIntent.getActivity(
            this,
            notificationId,
            openAppIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or
                PendingIntent.FLAG_IMMUTABLE
        )

        val title = if (senderUsername.isNotBlank()) {
            "Emergency Safety · $senderUsername"
        } else {
            "Emergency Safety"
        }

        val preview = if (messagePreview.isNotBlank()) {
            messagePreview
        } else {
            "Yeni mesajınız var."
        }

        val notification = NotificationCompat.Builder(
            this,
            CHANNEL_ID
        )
            .setSmallIcon(
                android.R.drawable.ic_dialog_info
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
            .setContentIntent(pendingIntent)
            .setDefaults(
                NotificationCompat.DEFAULT_SOUND or
                    NotificationCompat.DEFAULT_VIBRATE
            )
            .build()

        try {
            NotificationManagerCompat.from(this).notify(
                notificationId,
                notification
            )
        } catch (_: SecurityException) {
            // Bildirim izni yoksa uygulama kapanmaz.
        }
    }

    private fun createNotificationChannel() {

        if (Build.VERSION.SDK_INT >=
            Build.VERSION_CODES.O
        ) {

            val channel = NotificationChannel(
                CHANNEL_ID,
                CHANNEL_NAME,
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description =
                    "Yeni özel mesaj bildirimleri"

                enableVibration(true)
            }

            val manager = getSystemService(
                NotificationManager::class.java
            )

            manager.createNotificationChannel(channel)
        }
    }
}
