
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
import org.json.JSONObject
import java.util.Locale

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

            val message = contactsPreferences
                .getString("emergency_message", "")
                .orEmpty()
                .trim()

            val apiKey = connectionPreferences
                .getString("worker_api_key", "")
                .orEmpty()
                .trim()

            if (message.isBlank()) {
                reportStatus(
                    "Acil durum mesajı kayıtlı değil."
                )
                return@launch
            }

            if (apiKey.isBlank()) {
                reportStatus(
                    "API anahtarı kayıtlı değil. Önce uygulamadaki WhatsApp testinden anahtarı kaydedin."
                )
                return@launch
            }

            val contacts = (1..3).mapNotNull { number ->
                val phone = contactsPreferences
                    .getString("contact${number}_phone", "")
                    .orEmpty()
                    .trim()

                if (phone.isBlank()) {
                    null
                } else {
                    phone
                }
            }

            if (contacts.isEmpty()) {
                reportStatus(
                    "Kayıtlı acil durum telefon numarası bulunamadı."
                )
                return@launch
            }

            // Acil mesajı göndermeden önce kullanılabilir konumu al.
            // Konum alınamazsa acil mesajın gönderimini engelleme.
            val emergencyLocation = try {
                EmergencyLocationProvider
                    .getBestAvailableLocation(applicationContext)
            } catch (e: Exception) {
                Log.e(
                    "EmergencySafety",
                    "Acil konum alınırken hata oluştu.",
                    e
                )
                null
            }

            val locationText = if (emergencyLocation == null) {
                "Konum şu anda alınamadı. Telefonun konum ayarlarını kontrol edin."
            } else {
                val location = emergencyLocation.location

                val coordinates = String.format(
                    Locale.US,
                    "%.6f,%.6f",
                    location.latitude,
                    location.longitude
                )

                val mapUrl =
                    "https://www.google.com/maps/search/?api=1&query=$coordinates"

                if (emergencyLocation.isLastKnown) {
                    val ageMillis = (
                        System.currentTimeMillis() - location.time
                    ).coerceAtLeast(0L)

                    val ageMinutes = ageMillis / 60000L

                    "Son bilinen konum (yaklaşık $ageMinutes dakika önce):\n$mapUrl"
                } else {
                    "Güncel konum:\n$mapUrl"
                }
            }

            // Kayıtlı mesajdaki konum alanını gerçek bağlantıyla değiştir.
            // Alan yoksa konum bilgisini mesajın sonuna ekle.
            val locationPlaceholder = Regex(
                "\\[Otomatik konum bağlantısı\\]",
                RegexOption.IGNORE_CASE
            )

            val finalMessage = if (
                locationPlaceholder.containsMatchIn(message)
            ) {
                message.replace(
                    locationPlaceholder,
                    locationText
                )
            } else {
                "$message\n\n$locationText"
            }

            var acceptedCount = 0
            val errors = mutableListOf<String>()

            for (phone in contacts) {
                try {
                    val result = WhatsAppWorkerClient.sendMessage(
                        phoneNumber = phone,
                        message = finalMessage,
                        apiKey = apiKey
                    )

                    result.fold(
                        onSuccess = { response ->
                            val accepted = try {
                                JSONObject(response)
                                    .optBoolean("success", false)
                            } catch (e: Exception) {
                                false
                            }

                            if (accepted) {
                                acceptedCount++
                            } else {
                                errors.add(
                                    "$phone: gönderim onayı alınamadı."
                                )
                                Log.e(
                                    "EmergencySafety",
                                    "Worker yanıtı başarılı olarak doğrulanamadı."
                                )
                            }
                        },
                        onFailure = { error ->
                            errors.add("$phone: ${error.message}")
                            Log.e(
                                "EmergencySafety",
                                "WhatsApp gönderim isteği başarısız.",
                                error
                            )
                        }
                    )
                } catch (e: Exception) {
                    errors.add("$phone: ${e.message}")
                    Log.e(
                        "EmergencySafety",
                        "WhatsApp gönderimi sırasında hata oluştu.",
                        e
                    )
                }
            }

            val status = when {
                acceptedCount == contacts.size ->
                    "WhatsApp gönderim istekleri kabul edildi: $acceptedCount kişi."

                acceptedCount > 0 ->
                    "Kabul edilen istek: $acceptedCount/${contacts.size}. Bazı gönderimler başarısız."

                else ->
                    "WhatsApp gönderimi başarısız. Hata kayıtlarını kontrol edin."
            }

            Log.i(
                "EmergencySafety",
                "$status Hata sayısı: ${errors.size}"
            )

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
    }

    private fun stopSirenCode() {
        sirenManager.stopSiren()
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
        voiceTriggerManager.stopListening()
        sirenManager.stopSiren()
        serviceScope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
