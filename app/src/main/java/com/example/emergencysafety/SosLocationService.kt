
package com.example.emergencysafety

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.os.Looper
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import androidx.core.app.NotificationCompat
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority

class SosLocationService : Service() {

    companion object {
        const val ACTION_STOP_SOS =
            "com.example.emergencysafety.STOP_SOS_LOCATION"

        private const val CHANNEL_ID =
            "SosServiceChannelSilent"

        private const val NOTIFICATION_ID = 1

        private const val TAG = "SosLocationService"
    }

    private lateinit var fusedLocationClient:
        FusedLocationProviderClient

    private var locationCallback: LocationCallback? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val updateMutex = Mutex()
    private var sessionId: String? = null
    private var starting = false
    private var closing = false

    private fun selectedConnections(): List<String> {
        val prefs = getSharedPreferences("EmergencyContactSettings", MODE_PRIVATE)
        return (1..3).mapNotNull { slot ->
            prefs.getString("es_contact${slot}_id", null)
                ?.trim()?.takeIf { it.isNotEmpty() }
        }.distinct()
    }

    private fun beginTracking() {
        if (starting || sessionId != null || closing) return
        starting = true
        scope.launch {
            val ids = selectedConnections()
            val result = withContext(Dispatchers.IO) {
                ConnectionApi.startEmergencyLocation(applicationContext, ids)
            }
            starting = false
            if (closing) {
                result.data?.let { stopRemote(it) }
                return@launch
            }
            if (!result.success || result.data.isNullOrBlank()) {
                Log.e(TAG, "SOS konum oturumu baslatilamadi: ${result.message}")
                stopForegroundCompat()
                stopSelf()
                return@launch
            }
            sessionId = result.data
            val activeId = result.data
            // Notify only the explicitly selected emergency recipients.
            scope.launch(Dispatchers.IO) {
                ids.forEach { connectionId ->
                    val message = ConnectionApi.sendMessage(
                        applicationContext,
                        connectionId,
                        "📍 CANLI ACİL DURUM KONUMU\nES_LOCATION_SESSION:$activeId",
                        "location"
                    )
                    if (!message.success) {
                        Log.w(TAG, "SOS konum bildirimi gonderilemedi: ${message.message}")
                    }
                }
            }
            startLocationUpdates()
            // A recent cached fix can populate the map before the first new GPS callback.
            try {
                fusedLocationClient.lastLocation.addOnSuccessListener { location ->
                    if (location != null && !closing && sessionId == activeId) {
                        uploadLocation(
                            activeId,
                            location.latitude,
                            location.longitude,
                            location.accuracy
                        )
                    }
                }
            } catch (error: SecurityException) {
                Log.w(TAG, "Son konum okunamadi: izin gerekli.", error)
            }
        }
    }

    private fun uploadLocation(
        activeSession: String,
        latitude: Double,
        longitude: Double,
        accuracy: Float
    ) {
        if (closing) return
        scope.launch(Dispatchers.IO) {
            if (!updateMutex.tryLock()) return@launch
            try {
                if (closing || sessionId != activeSession) return@launch
                val response = ConnectionApi.updateEmergencyLocation(
                    applicationContext,
                    activeSession,
                    latitude,
                    longitude,
                    accuracy
                )
                if (!response.success) {
                    Log.w(TAG, "SOS konum aktarimi basarisiz: ${response.message}")
                } else {
                    Log.d(TAG, "SOS konum sunucuya guncellendi.")
                }
            } finally {
                updateMutex.unlock()
            }
        }
    }

    private fun stopRemote(id: String) {
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            val result = ConnectionApi.stopEmergencyLocation(applicationContext, id)
            if (!result.success) {
                Log.w(TAG, "SOS konum oturumu kapatilamadi: ${result.message}")
            }
        }
    }

    private fun finishTracking() {
        if (closing) return
        closing = true
        stopLocationUpdates()
        sessionId?.let { stopRemote(it) }
        sessionId = null
        stopForegroundCompat()
        stopSelf()
    }


    override fun onCreate() {
        super.onCreate()

        fusedLocationClient =
            LocationServices.getFusedLocationProviderClient(this)

        createNotificationChannel()
    }

    override fun onStartCommand(
        intent: Intent?,
        flags: Int,
        startId: Int
    ): Int {

        if (intent?.action == ACTION_STOP_SOS) {
            finishTracking()
            return START_NOT_STICKY
        }

        val stopIntent = Intent(this, SosLocationService::class.java).apply {
            action = ACTION_STOP_SOS
        }
        val stopPendingIntent = PendingIntent.getService(
            this, 4101, stopIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification: Notification =
            NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle("SOS konum hizmeti")
                .setContentText(
                    "Acil durum konum takibi etkin."
                )
                .setSmallIcon(
                    android.R.drawable.ic_menu_mylocation
                )
                .setOngoing(true)
                .addAction(
                    android.R.drawable.ic_media_pause,
                    "KONUMU DURDUR",
                    stopPendingIntent
                )
                .setSilent(true)
                .setPriority(
                    NotificationCompat.PRIORITY_LOW
                )
                .setCategory(
                    NotificationCompat.CATEGORY_SERVICE
                )
                .setVisibility(
                    NotificationCompat.VISIBILITY_SECRET
                )
                .build()

        startForeground(
            NOTIFICATION_ID,
            notification
        )

        beginTracking()

        return START_NOT_STICKY
    }

    private fun startLocationUpdates() {

        if (locationCallback != null) {
            return
        }

        val locationRequest =
            LocationRequest.Builder(
                Priority.PRIORITY_HIGH_ACCURACY,
                10000L
            )
                .setMinUpdateIntervalMillis(5000L)
                .build()

        val callback = object : LocationCallback() {

            override fun onLocationResult(
                locationResult: LocationResult
            ) {
                val location =
                    locationResult.lastLocation ?: return

                val activeSession = sessionId ?: return
                if (closing) return
                uploadLocation(
                    activeSession,
                    location.latitude,
                    location.longitude,
                    location.accuracy
                )
            }
        }

        locationCallback = callback

        try {
            fusedLocationClient.requestLocationUpdates(
                locationRequest,
                callback,
                Looper.getMainLooper()
            ).addOnFailureListener { error ->

                Log.e(
                    TAG,
                    "Konum güncellemesi başlatılamadı.",
                    error
                )

                if (locationCallback === callback) {
                    stopLocationUpdates()
                    stopForegroundCompat()
                    stopSelf()
                }
            }

        } catch (e: SecurityException) {

            Log.e(
                TAG,
                "Konum izni bulunamadı.",
                e
            )

            stopLocationUpdates()
            stopForegroundCompat()
            stopSelf()

        } catch (e: Exception) {

            Log.e(
                TAG,
                "Konum servisi hatası.",
                e
            )

            stopLocationUpdates()
            stopForegroundCompat()
            stopSelf()
        }
    }

    private fun stopLocationUpdates() {

        val callback = locationCallback ?: return

        locationCallback = null

        fusedLocationClient.removeLocationUpdates(
            callback
        )

        Log.i(
            TAG,
            "SOS konum takibi durduruldu."
        )
    }

    private fun stopForegroundCompat() {

        if (Build.VERSION.SDK_INT >=
            Build.VERSION_CODES.N
        ) {
            stopForeground(
                STOP_FOREGROUND_REMOVE
            )
        } else {
            @Suppress("DEPRECATION")
            stopForeground(true)
        }
    }

    private fun createNotificationChannel() {

        if (Build.VERSION.SDK_INT >=
            Build.VERSION_CODES.O
        ) {

            val serviceChannel = NotificationChannel(
                CHANNEL_ID,
                "SOS Konum Hizmeti",
                NotificationManager.IMPORTANCE_LOW
            ).apply {

                description =
                    "Acil durum konum hizmeti"

                setShowBadge(false)
                enableVibration(false)
                setSound(null, null)
            }

            val manager =
                getSystemService(
                    NotificationManager::class.java
                )

            manager?.createNotificationChannel(
                serviceChannel
            )
        }
    }

    override fun onDestroy() {
        if (!closing) {
            closing = true
            stopLocationUpdates()
            sessionId?.let { stopRemote(it) }
            sessionId = null
        }
        scope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? {
        return null
    }
}
