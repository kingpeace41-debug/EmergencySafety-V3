package com.example.emergencysafety

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat

class VoiceTriggerService : Service() {

    private lateinit var sirenManager: SirenManager
    private lateinit var voiceTriggerManager: VoiceTriggerManager

    override fun onCreate() {
        super.onCreate()
        sirenManager = SirenManager(this)
        voiceTriggerManager = VoiceTriggerManager(this)

        startForegroundServiceWithNotification()

        voiceTriggerManager.startListening(
            onRedStart = { triggerRedCode() },
            onRedStop = { stopRedCode() },
            onSirenStart = { triggerSirenCode() },
            onSirenStop = { stopSirenCode() }
        )
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            "ACTION_STOP_SIREN" -> stopSirenCode()
            "ACTION_STOP_RED" -> stopRedCode()
        }
        return START_STICKY
    }

    private fun triggerRedCode() {
        val intent = Intent(this, FakeDeadActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        }
        startActivity(intent)
    }

    private fun stopRedCode() {
        val stopBroadcast = Intent("com.example.emergencysafety.CLOSE_FAKE_DEAD").apply {
            setPackage(packageName)
        }
        sendBroadcast(stopBroadcast)

        val closeIntent = Intent(this, FakeDeadActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            putExtra("EXTRA_CLOSE", true)
        }
        startActivity(closeIntent)
    }

    private fun triggerSirenCode() {
        sirenManager.startSiren()
    }

    private fun stopSirenCode() {
        sirenManager.stopSiren()
    }

    private fun startForegroundServiceWithNotification() {
        val channelId = "voice_trigger_channel"
        val notificationManager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                channelId,
                "Ses Algılama Servisi",
                NotificationManager.IMPORTANCE_LOW
            )
            notificationManager.createNotificationChannel(channel)
        }

        val notification: Notification = NotificationCompat.Builder(this, channelId)
            .setContentTitle("Emergency Safety Aktif")
            .setContentText("Ses komutları dinleniyor...")
            .setSmallIcon(R.drawable.ic_app_logo)
            .setOngoing(true)
            .build()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            startForeground(1, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
        } else {
            startForeground(1, notification)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        voiceTriggerManager.stopListening()
        sirenManager.stopSiren()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
