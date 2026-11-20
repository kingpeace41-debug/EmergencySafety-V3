
package com.example.emergencysafety

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.os.Looper
import android.util.Log
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
            stopLocationUpdates()
            stopForegroundCompat()
            stopSelf()
            return START_NOT_STICKY
        }

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

        startLocationUpdates()

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

                // Konum cihazda alınır.
                // Bu sürümde henüz sunucuya gönderilmez.
                // Güvenli konum aktarımı ayrıca kurulacak.

                Log.d(
                    TAG,
                    "SOS konum güncellemesi alındı."
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

        stopLocationUpdates()

        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? {
        return null
    }
}
