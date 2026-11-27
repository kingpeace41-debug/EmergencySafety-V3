package com.example.emergencysafety

import android.app.Activity
import android.app.AlertDialog
import android.media.MediaPlayer
import android.media.MediaRecorder
import android.widget.Toast
import java.io.File

internal class ChatVoiceNote(private val activity: Activity) {
    private var recorder: MediaRecorder? = null
    private var player: MediaPlayer? = null
    private var draft: File? = null
    private var dialog: AlertDialog? = null

    fun close() {
        dialog?.dismiss()
        dialog = null
        recorder?.release()
        recorder = null
        player?.release()
        player = null
        draft?.delete()
        draft = null
    }

    @Suppress("DEPRECATION")
    fun record(send: (ByteArray) -> Unit) {
        close()
        try {
            val file = File.createTempFile("voice-", ".m4a", activity.cacheDir)
            draft = file
            val mic = MediaRecorder()
            recorder = mic
            mic.setAudioSource(MediaRecorder.AudioSource.MIC)
            mic.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            mic.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
            mic.setAudioEncodingBitRate(64000)
            mic.setMaxFileSize(7L * 1024 * 1024)
            mic.setOutputFile(file.absolutePath)
            mic.setOnErrorListener { _, _, _ -> if (recorder === mic) failed("Mikrofon kaydı kesildi.") }
            mic.setOnInfoListener { _, what, _ ->
                if (recorder === mic && what == MediaRecorder.MEDIA_RECORDER_INFO_MAX_FILESIZE_REACHED) {
                    recorder = null
                    runCatching { mic.stop() }
                    mic.release()
                    dialog?.setMessage("Kayıt tamamlandı. Sesli mesajı gönderebilirsin.")
                }
            }
            mic.prepare()
            mic.start()
            dialog = AlertDialog.Builder(activity, R.style.YellowPhotoDialog)
                .setTitle("Sesli mesaj").setMessage("Ses kaydediliyor…")
                .setNegativeButton("Vazgeç") { _, _ -> close() }
                .setPositiveButton("Durdur ve gönder") { _, _ ->
                    try {
                        recorder?.stop()
                        require(file.length() in 1L..(8L * 1024 * 1024))
                        send(file.readBytes())
                    } catch (_: Exception) { toast("Ses kaydı gönderilemedi.") }
                    finally { close() }
                }.setOnCancelListener { close() }.show()
        } catch (_: Exception) { failed("Mikrofon açılamadı.") }
    }

    fun play(bytes: ByteArray) {
        close()
        try {
            val file = File.createTempFile("listen-", ".m4a", activity.cacheDir)
            draft = file
            file.writeBytes(bytes)
            val audio = MediaPlayer()
            player = audio
            file.inputStream().use { audio.setDataSource(it.fd) }
            file.delete()
            audio.setOnPreparedListener { if (player === audio) it.start() }
            audio.setOnCompletionListener { if (player === audio) close() }
            audio.setOnErrorListener { _, _, _ ->
                if (player === audio) failed("Sesli mesaj oynatılamadı.")
                true
            }
            dialog = AlertDialog.Builder(activity, R.style.YellowPhotoDialog)
                .setTitle("Sesli mesaj").setPositiveButton("Kapat") { _, _ -> close() }
                .setOnCancelListener { close() }.show()
            audio.prepareAsync()
        } catch (_: Exception) { failed("Sesli mesaj oynatılamadı.") }
    }

    private fun failed(message: String) {
        close()
        toast(message)
    }

    private fun toast(message: String) = Toast.makeText(activity, message, Toast.LENGTH_SHORT).show()
}
