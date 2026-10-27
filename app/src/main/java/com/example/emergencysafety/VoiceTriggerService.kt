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
        CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private var redCodeInProgress = false

    override fun onCreate() {
        super.onCreate()

        sirenManager = SirenManager(this)
        voiceTriggerManager = VoiceTriggerManager(this)

        // Servis başladığında siren durumu kapalı olarak başlar.
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

        startActivity(screenIntent)
    }

    private fun sendEmergencyMessages() {
        serviceScope.launch {
            val contactsPreferences = getSharedPreferences(
                "EmergencyContactSettings",
                MODE_PRIVATE
            )

            val connectionPreferences = getSharedPreferences(
                "EmergencySafetyConnection",
                MODE_PRIVATE
            )

            val savedMessage = contactsPreferences
                .getString("emergency_message", "")
                .orEmpty()
                .trim()

            val apiKey = connectionPreferences
                .getString("worker_api_key", "")
                .orEmpty()
                .trim()

            if (savedMessage.isBlank()) {
                reportStatus(
                    "Acil durum mesajı kayıtlı değil."
                )
                return@launch
            }

            if (apiKey.isBlank()) {
                reportStatus(
                    "Worker API anahtarı kayıtlı değil. " +
                        "Manuel WhatsApp testinden anahtarı kaydedin."
                )
                return@launch
            }

            val contacts = (1..3).mapNotNull { index ->
                val phone = contactsPreferences
                    .getString("contact${index}_phone", "")
                    .orEmpty()
                    .trim()

                if (phone.isBlank()) {
                    null
                } else {
                    val name = contactsPreferences
                        .getString("contact${index}_name", "")
                        .orEmpty()
                        .trim()

                    EmergencyProtocolContact(
                        name = name.ifBlank { "Kişi $index" },
                        phone = phone
                    )
                }
            }

            if (contacts.isEmpty()) {
                reportStatus(
                    "Kayıtlı acil durum telefon numarası bulunamadı."
                )
                return@launch
            }

            // Ortak protokol mesajı ve konumu hazırlar.
            val finalMessage = EmergencyProtocol.prepareMessage(
                context = applicationContext,
                originalMessage = savedMessage
            )

            // Mevcut WhatsApp gönderim akışı korunmuştur.
            val results = EmergencyProtocol.sendToContacts(
                apiKey = apiKey,
                message = finalMessage,
                contacts = contacts
            )

            val rejectedKey = results.any {
                it.apiKeyRejected
            }

            if (rejectedKey) {
                reportStatus(
                    "Worker API anahtarı kabul edilmedi. " +
                        "Manuel WhatsApp testinden anahtarı kontrol edin."
                )
                return@launch
            }

            val acceptedCount = results.count {
                it.accepted
            }

            val errors = results.filter {
                !it.accepted
            }

            val status = when {
                acceptedCount == contacts.size ->
                    "WhatsApp gönderim istekleri kabul edildi: " +
                        "$acceptedCount kişi."

                acceptedCount > 0 ->
                    "Kabul edilen istek: " +
                        "$acceptedCount/${contacts.size}. " +
                        "Bazı gönderimler başarısız."

                else ->
                    "WhatsApp gönderimi başarısız. " +
                        "Bağlantıyı ve Worker ayarlarını kontrol edin."
            }

            Log.i(
                "EmergencySafety",
                "$status Hata sayısı: ${errors.size}"
            )

            errors.take(3).forEach { error ->
                Log.e(
                    "EmergencySafety",
                    "${error.contactName}: " +
                        (error.error ?: "Gönderim başarısız.")
                )
            }

            reportStatus(status)
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

        notificationManager.notify(2, notification)
    }

    private fun stopRedCode() {
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

        startActivity(closeIntent)

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

        setSirenActive(false)

        serviceScope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
