
package com.example.emergencysafety

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log
import android.widget.Toast
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

class VoiceTriggerService : Service() {

    private lateinit var sirenManager: SirenManager
    private lateinit var voiceTriggerManager: VoiceTriggerManager

    private val serviceScope =
        CoroutineScope(
            SupervisorJob() + Dispatchers.Main.immediate
        )

    private var redCodeInProgress = false

    override fun onCreate() {
        super.onCreate()

        sirenManager = SirenManager(this)
        voiceTriggerManager = VoiceTriggerManager(this)

        setSirenActive(false)

        startForegroundServiceWithNotification()

        voiceTriggerManager.startListening(
            onRedStart = { triggerRedCode() },
            onRedStop = { stopRedCode() },
            onSirenStart = { triggerSirenCode() },
            onSirenStop = { stopSirenCode() }
        )
    }

    override fun onStartCommand(
        intent: Intent?,
        flags: Int,
        startId: Int
    ): Int {
        when (intent?.action) {
            "ACTION_STOP_SIREN" -> stopSirenCode()
            "ACTION_STOP_RED" -> stopRedCode()
        }

        return START_STICKY
    }

    private fun triggerRedCode() {
        if (redCodeInProgress) return

        redCodeInProgress = true

        startSosLocationService()

        sendEmergencyMessages()

        val screenIntent = Intent(
            this,
            FakeDeadActivity::class.java
        ).apply {
            addFlags(
                Intent.FLAG_ACTIVITY_NEW_TASK or
                    Intent.FLAG_ACTIVITY_SINGLE_TOP
            )
        }

        try {
            startActivity(screenIntent)
        } catch (e: Exception) {
            Log.e(
                "EmergencySafety",
                "Siyah ekran başlatılamadı.",
                e
            )
        }
    }

    private fun startSosLocationService() {
        val serviceIntent = Intent(
            this,
            SosLocationService::class.java
        )

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(serviceIntent)
            } else {
                startService(serviceIntent)
            }
        } catch (e: Exception) {
            Log.e(
                "EmergencySafety",
                "Konum servisi başlatılamadı.",
                e
            )
        }
    }

    private fun stopSosLocationService() {
        val serviceIntent = Intent(
            this,
            SosLocationService::class.java
        )

        try {
            stopService(serviceIntent)

            Log.i(
                "EmergencySafety",
                "SOS konum servisi durdurma istendi."
            )
        } catch (e: Exception) {
            Log.e(
                "EmergencySafety",
                "SOS konum servisi durdurulamadı.",
                e
            )
        }
    }

    private fun sendEmergencyMessages() {
        serviceScope.launch {
            try {
                val summary = EsEmergencySender.send(
                    applicationContext
                )

                val acceptedCount = summary.acceptedCount
                val failedCount = summary.failedCount

                val status = when {
                    summary.error != null -> {
                        summary.error
                    }

                    acceptedCount > 0 && failedCount == 0 -> {
                        "ES acil durum mesajı gönderim isteği " +
                            "$acceptedCount kişi için kabul edildi."
                    }

                    acceptedCount > 0 -> {
                        "ES acil durum mesajı: " +
                            "$acceptedCount kabul edildi, " +
                            "$failedCount başarısız."
                    }

                    else -> {
                        "ES acil durum mesajı gönderilemedi. " +
                            "Bağlantı ve kişi ayarlarını kontrol edin."
                    }
                }

                Log.i(
                    "EmergencySafety",
                    "Sesli acil durum: $status"
                )

                summary.results
                    .filter { !it.accepted }
                    .take(3)
                    .forEach { result ->
                        Log.e(
                            "EmergencySafety",
                            "${result.contactName}: " +
                                (
                                    result.error
                                        ?: "Gönderim başarısız."
                                )
                        )
                    }

                reportStatus(status)

            } catch (e: Exception) {
                Log.e(
                    "EmergencySafety",
                    "ES acil durum gönderim hatası.",
                    e
                )

                reportStatus(
                    "ES acil durum mesajı gönderilemedi. " +
                        "Lütfen bağlantıyı kontrol edin."
                )
            }
        }
    }

    private fun reportStatus(message: String) {
        Log.i("EmergencySafety", message)

        Toast.makeText(
            applicationContext,
            message,
            Toast.LENGTH_LONG
        ).show()

        val notificationManager =
            getSystemService(NOTIFICATION_SERVICE)
                as NotificationManager

        val notification = NotificationCompat.Builder(
            this,
            "voice_trigger_channel"
        )
            .setContentTitle("Emergency Safety")
            .setContentText(message)
            .setSmallIcon(R.drawable.ic_app_logo)
            .setAutoCancel(true)
            .build()

        try {
            notificationManager.notify(2, notification)
        } catch (e: SecurityException) {
            Log.w(
                "EmergencySafety",
                "Durum bildirimi izni yok.",
                e
            )
        }
    }

    private fun stopRedCode() {
        redCodeInProgress = false

        stopSosLocationService()

        getSharedPreferences(
            "AppSettings",
            MODE_PRIVATE
        ).edit()
            .putBoolean("is_voice_active", false)
            .apply()

        val stopBroadcast = Intent(
            "com.example.emergencysafety.CLOSE_FAKE_DEAD"
        ).apply {
            setPackage(packageName)
        }

        sendBroadcast(stopBroadcast)

        val closeIntent = Intent(
            this,
            FakeDeadActivity::class.java
        ).apply {
            addFlags(
                Intent.FLAG_ACTIVITY_NEW_TASK or
                    Intent.FLAG_ACTIVITY_SINGLE_TOP
            )

            putExtra("EXTRA_CLOSE", true)
        }

        try {
            startActivity(closeIntent)
        } catch (e: Exception) {
            Log.e(
                "EmergencySafety",
                "Siyah ekran kapatılamadı.",
                e
            )
        }

        stopSelf()
    }

    private fun triggerSirenCode() {
        sirenManager.startSiren()
        setSirenActive(true)
    }

    private fun stopSirenCode() {
        sirenManager.stopSiren()
        setSirenActive(false)
    }

    private fun setSirenActive(active: Boolean) {
        getSharedPreferences(
            "AppSettings",
            MODE_PRIVATE
        ).edit()
            .putBoolean("is_siren_active", active)
            .apply()
    }

    private fun startForegroundServiceWithNotification() {
        val channelId = "voice_trigger_channel"

        val notificationManager =
            getSystemService(NOTIFICATION_SERVICE)
                as NotificationManager

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                channelId,
                "Ses Algılama Servisi",
                NotificationManager.IMPORTANCE_LOW
            )

            notificationManager.createNotificationChannel(channel)
        }

        val notification: Notification =
            NotificationCompat.Builder(this, channelId)
                .setContentTitle("Emergency Safety Aktif")
                .setContentText("Ses komutları dinleniyor...")
                .setSmallIcon(R.drawable.ic_app_logo)
                .setOngoing(true)
                .build()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            startForeground(
                1,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
            )
        } else {
            startForeground(1, notification)
        }
    }

    override fun onDestroy() {
        if (::voiceTriggerManager.isInitialized) {
            voiceTriggerManager.stopListening()
        }

        if (::sirenManager.isInitialized) {
            sirenManager.stopSiren()
        }

        if (redCodeInProgress) {
            stopSosLocationService()
            redCodeInProgress = false
        }

        setSirenActive(false)

        serviceScope.cancel()

        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
