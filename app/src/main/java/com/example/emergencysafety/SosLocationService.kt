
package com.example.emergencysafety

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.os.Looper
import androidx.core.app.NotificationCompat
import com.google.android.gms.location.*
import java.net.HttpURLConnection
import java.net.URL
import kotlin.concurrent.thread

class SosLocationService : Service() {

    private lateinit var fusedLocationClient: FusedLocationProviderClient
    private lateinit var locationCallback: LocationCallback

    // Yeni düşük öncelikli kanal.
    // Eski yüksek öncelikli kanal ayarından etkilenmemesi için adı değiştirildi.
    private val CHANNEL_ID = "SosServiceChannelSilent"

    // Mevcut sunucu adresi ayarı korunmuştur.
    private val SERVER_URL =
        "http://SUNUCU_IP_ADRESINIZ:3000/update-location"

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

        val notification: Notification =
            NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle("SOS konum hizmeti")
                .setContentText("Acil durum konum takibi çalışıyor.")
                .setSmallIcon(android.R.drawable.ic_menu_mylocation)
                .setOngoing(true)
                .setSilent(true)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .setCategory(NotificationCompat.CATEGORY_SERVICE)
                .setVisibility(NotificationCompat.VISIBILITY_SECRET)
                .build()

        startForeground(1, notification)

        startLocationUpdates()

        return START_STICKY
    }

    private fun startLocationUpdates() {

        val locationRequest =
            LocationRequest.Builder(
                Priority.PRIORITY_HIGH_ACCURACY,
                10000
            )
                .setMinUpdateIntervalMillis(5000)
                .build()

        locationCallback = object : LocationCallback() {

            override fun onLocationResult(
                locationResult: LocationResult
            ) {
                for (location in locationResult.locations) {
                    sendLocationToServer(
                        location.latitude,
                        location.longitude
                    )
                }
            }
        }

        try {
            fusedLocationClient.requestLocationUpdates(
                locationRequest,
                locationCallback,
                Looper.getMainLooper()
            )
        } catch (e: SecurityException) {
            e.printStackTrace()
        }
    }

    private fun sendLocationToServer(
        lat: Double,
        lng: Double
    ) {
        thread {
            try {
                val url = URL("$SERVER_URL?lat=$lat&lng=$lng")

                val connection =
                    url.openConnection() as HttpURLConnection

                connection.requestMethod = "POST"
                connection.connectTimeout = 5000
                connection.readTimeout = 5000

                connection.responseCode
                connection.disconnect()

            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    private fun createNotificationChannel() {

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {

            val serviceChannel = NotificationChannel(
                CHANNEL_ID,
                "SOS Konum Hizmeti",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Acil durum konum hizmeti"
                setShowBadge(false)
                enableVibration(false)
                setSound(null, null)
            }

            val manager =
                getSystemService(NotificationManager::class.java)

            manager?.createNotificationChannel(serviceChannel)
        }
    }

    override fun onDestroy() {

        if (::locationCallback.isInitialized) {
            fusedLocationClient.removeLocationUpdates(
                locationCallback
            )
        }

        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? {
        return null
    }
}
