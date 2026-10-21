package com.example.emergencysafety

import android.content.Context
import android.hardware.camera2.CameraManager
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.VibrationEffect
import android.os.Vibrator
import android.util.Log

class SirenManager(private val context: Context) {

    private var mediaPlayer: MediaPlayer? = null
    private var vibrator: Vibrator? = null
    private var cameraManager: CameraManager? = null
    private var cameraId: String? = null
    private var isSirenRunning = false

    private val flashHandler = Handler(Looper.getMainLooper())
    private var isFlashOn = false

    private val flashRunnable = object : Runnable {
        override fun run() {
            if (!isSirenRunning) return

            try {
                if (cameraId != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    isFlashOn = !isFlashOn
                    cameraManager?.setTorchMode(cameraId!!, isFlashOn)
                }
            } catch (e: Exception) {
                Log.e("SirenManager", "Flaş hatası: ${e.message}")
            }

            flashHandler.postDelayed(this, 250)
        }
    }

    fun startSiren() {
        if (isSirenRunning) return
        isSirenRunning = true

        // Uygulamanın kendi siren sesini tekrar tekrar çal.
        try {
            val soundUri = Uri.parse(
                "android.resource://${context.packageName}/${R.raw.emergency_alarm}"
            )

            mediaPlayer = MediaPlayer().apply {
                setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_ALARM)
                        .setContentType(
                            AudioAttributes.CONTENT_TYPE_SONIFICATION
                        )
                        .build()
                )
                setDataSource(context, soundUri)
                isLooping = true
                prepare()
                start()
            }
        } catch (e: Exception) {
            Log.e("SirenManager", "Siren sesi hatası: ${e.message}")
            mediaPlayer?.release()
            mediaPlayer = null
        }

        // Komut alındığını bildiren tek kısa titreşim.
        try {
            vibrator = context.getSystemService(
                Context.VIBRATOR_SERVICE
            ) as Vibrator

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                vibrator?.vibrate(
                    VibrationEffect.createOneShot(
                        120,
                        VibrationEffect.DEFAULT_AMPLITUDE
                    )
                )
            } else {
                @Suppress("DEPRECATION")
                vibrator?.vibrate(120)
            }
        } catch (e: Exception) {
            Log.e("SirenManager", "Titreşim hatası: ${e.message}")
        }

        // Kamera flaşı.
        try {
            cameraManager = context.getSystemService(
                Context.CAMERA_SERVICE
            ) as CameraManager

            cameraId = cameraManager?.cameraIdList?.firstOrNull()

            if (cameraId != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                flashHandler.post(flashRunnable)
            }
        } catch (e: Exception) {
            Log.e("SirenManager", "Flaş başlatılamadı: ${e.message}")
        }
    }

    fun stopSiren() {
        if (!isSirenRunning) return
        isSirenRunning = false

        flashHandler.removeCallbacks(flashRunnable)

        try {
            if (cameraId != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                cameraManager?.setTorchMode(cameraId!!, false)
            }
        } catch (e: Exception) {
            Log.e("SirenManager", "Flaş kapatılamadı: ${e.message}")
        }

        isFlashOn = false

        try {
            mediaPlayer?.let { player ->
                if (player.isPlaying) player.stop()
                player.release()
            }
            mediaPlayer = null
        } catch (e: Exception) {
            Log.e("SirenManager", "Siren durdurulamadı: ${e.message}")
            mediaPlayer = null
        }

        // Varsa titreşimi iptal et; sürekli titreşim artık kullanılmıyor.
        try {
            vibrator?.cancel()
            vibrator = null
        } catch (e: Exception) {
            Log.e("SirenManager", "Titreşim durdurulamadı: ${e.message}")
        }
    }
}
