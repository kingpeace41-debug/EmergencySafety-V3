package com.example.emergencysafety

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.os.Process
import android.util.Log
import kotlin.math.PI
import kotlin.math.sin

/**
 * Acil durum icin uygulama icinde uretilen iki tonlu siren.
 *
 * Bu sinif yalnizca sesi uretir. FCM bildirimi, servis yasam dongusu
 * ve kullanicinin alarmi kapatma islemi ayri adimlarda baglanacaktir.
 */
internal object EmergencySirenPlayer {
    private const val TAG = "ES_EMERGENCY_SIREN"
    private const val SAMPLE_RATE = 22050
    private const val CHUNK_SAMPLES = 2205
    private const val MAX_AMPLITUDE = 0.35
    private const val CYCLE_SECONDS = 1.6

    private val lock = Any()

    @Volatile
    private var playing = false

    private var worker: Thread? = null

    fun isPlaying(): Boolean = playing

    fun start() {
        synchronized(lock) {
            if (playing) return
            playing = true
            worker = Thread({ playLoop() }, "ES-EmergencySiren").apply {
                isDaemon = true
                start()
            }
        }
    }

    fun stop() {
        synchronized(lock) {
            playing = false
            worker?.interrupt()
            worker = null
        }
    }

    private fun playLoop() {
        var track: AudioTrack? = null
        try {
            Process.setThreadPriority(Process.THREAD_PRIORITY_AUDIO)

            val minBuffer = AudioTrack.getMinBufferSize(
                SAMPLE_RATE,
                AudioFormat.CHANNEL_OUT_MONO,
                AudioFormat.ENCODING_PCM_16BIT
            )
            if (minBuffer <= 0) return

            track = AudioTrack.Builder()
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_ALARM)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .build()
                )
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setSampleRate(SAMPLE_RATE)
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                        .build()
                )
                .setBufferSizeInBytes(maxOf(minBuffer, CHUNK_SAMPLES * 2))
                .setTransferMode(AudioTrack.MODE_STREAM)
                .build()

            if (track.state != AudioTrack.STATE_INITIALIZED) return

            track.play()
            val buffer = ShortArray(CHUNK_SAMPLES)
            var sampleIndex = 0L
            var phase = 0.0

            while (playing && !Thread.currentThread().isInterrupted) {
                for (i in buffer.indices) {
                    val seconds = sampleIndex.toDouble() / SAMPLE_RATE
                    val cycle = (seconds % CYCLE_SECONDS) / CYCLE_SECONDS
                    val sweep = if (cycle < 0.5) cycle * 2.0 else (1.0 - cycle) * 2.0
                    val frequency = 620.0 + 430.0 * sweep
                    phase += 2.0 * PI * frequency / SAMPLE_RATE
                    if (phase >= 2.0 * PI) phase -= 2.0 * PI

                    val envelope = minOf(1.0, (seconds % CYCLE_SECONDS) * 25.0)
                    buffer[i] = (sin(phase) * MAX_AMPLITUDE * envelope *
                        Short.MAX_VALUE).toInt().toShort()
                    sampleIndex++
                }

                var offset = 0
                while (offset < buffer.size && playing) {
                    val written = track.write(
                        buffer, offset, buffer.size - offset,
                        AudioTrack.WRITE_BLOCKING
                    )
                    if (written <= 0) break
                    offset += written
                }
                if (offset < buffer.size) break
            }
        } catch (error: Exception) {
            Log.e(TAG, "Siren sesi oynatilamadi.", error)
        } finally {
            try {
                track?.pause()
                track?.flush()
                track?.release()
            } catch (_: Exception) {
                // Ses kaynagi kapatilirken uygulama etkilenmesin.
            }
            synchronized(lock) {
                if (worker === Thread.currentThread()) {
                    playing = false
                    worker = null
                }
            }
        }
    }
}
