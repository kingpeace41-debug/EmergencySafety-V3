package com.example.emergencysafety

import android.annotation.SuppressLint
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.os.Looper
import android.widget.Toast
import androidx.core.app.NotificationCompat
import com.google.android.gms.location.*
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import java.util.UUID

internal class ChatLocationService : Service() {
    companion object {
        const val STOP = "com.example.emergencysafety.STOP_CHAT_LOCATION"
        const val PREFIX = "es.live.location.v1"
        @Volatile var activeConnection: String? = null
            private set
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val fixes = Channel<android.location.Location>(Channel.CONFLATED)
    private val shareId = UUID.randomUUID().toString()
    private val fused by lazy { LocationServices.getFusedLocationProviderClient(this) }
    private var tracking = false
    private var boundSession: String? = null
    private var sentFix = false
    private val callback = object : LocationCallback() {
        override fun onLocationResult(result: LocationResult) {
            result.lastLocation?.let { fixes.trySend(it) }
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    @SuppressLint("MissingPermission")
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == STOP) {
            val connection = activeConnection
            val session = boundSession
            stopSharing()
            if (sentFix && connection != null && session != null) {
                CoroutineScope(Dispatchers.IO).launch {
                    runCatching { MatrixChatManager.enqueueText(applicationContext, connection,
                        "$PREFIX|$shareId|stop", session) }
                }
            }
            return START_NOT_STICKY
        }
        if (tracking) return START_NOT_STICKY
        val connection = intent?.getStringExtra("connection")
        val session = intent?.getStringExtra("session")
        if (connection == null || session == null || SecureSessionStore.getSessionId(this) != session) {
            stopSelf()
            return START_NOT_STICKY
        }
        val channel = "private_live_location"
        if (Build.VERSION.SDK_INT >= 26) {
            getSystemService(NotificationManager::class.java).createNotificationChannel(
                NotificationChannel(channel, "Canlı konum", NotificationManager.IMPORTANCE_LOW)
            )
        }
        val stop = PendingIntent.getService(this, 4811,
            Intent(this, ChatLocationService::class.java).setAction(STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val notification = NotificationCompat.Builder(this, channel)
            .setSmallIcon(R.drawable.ic_notification_wolf)
            .setContentTitle("Canlı konum paylaşımı açık")
            .setContentText(intent?.getStringExtra("name"))
            .setOngoing(true).setOnlyAlertOnce(true)
            .addAction(0, "Paylaşımı durdur", stop).build()
        try {
            startForeground(4811, notification)
            boundSession = session
            activeConnection = connection
            tracking = true
            val request = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 30000L)
                .setMinUpdateDistanceMeters(10f).build()
            fused.requestLocationUpdates(request, callback, Looper.getMainLooper())
                .addOnFailureListener { if (tracking) fail() }
            scope.launch {
                try {
                    for (fix in fixes) {
                        if (!tracking || SecureSessionStore.getSessionId(applicationContext) != session) break
                        val kind = if (sentFix) "update" else "start"
                        MatrixChatManager.enqueueText(applicationContext, connection,
                            "$PREFIX|$shareId|$kind|${fix.latitude}|${fix.longitude}", session)
                        sentFix = true
                    }
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    fail()
                } finally {
                    stopSelf()
                }
            }
        } catch (_: SecurityException) {
            fail()
        }
        return START_NOT_STICKY
    }

    private fun fail() {
        Toast.makeText(this, "Canlı konum paylaşımı kesildi.", Toast.LENGTH_LONG).show()
        stopSharing()
    }

    private fun stopSharing() {
        tracking = false
        activeConnection = null
        fused.removeLocationUpdates(callback)
        scope.cancel()
        stopSelf()
    }

    override fun onDestroy() {
        tracking = false
        activeConnection = null
        fused.removeLocationUpdates(callback)
        fixes.close()
        scope.cancel()
        super.onDestroy()
    }
}